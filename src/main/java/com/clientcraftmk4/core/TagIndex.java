package com.clientcraftmk4.core;

import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.display.RecipeDisplayEntry;
import net.minecraft.world.item.crafting.display.SlotDisplay;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * All tag caches for one recipe set (plan §8.4):
 * <ul>
 *   <li>{@code knownTags} / {@code itemToTags} / flat tag data — recipe-set determined, built at
 *       model time;</li>
 *   <li>{@link InventoryTags} — inventory determined, built off-model by {@link #ensureFresh} and
 *       published as one immutable object.</li>
 * </ul>
 *
 * A fresh instance is created per {@link CraftModel} rebuild, so the inventory-dependent
 * data is automatically rebuilt against the new recipe set on the next {@link #ensureFresh}.
 */
public final class TagIndex {
    private final Set<TagKey<Item>> knownTags = new HashSet<>();
    private final Map<Item, Set<TagKey<Item>>> itemToTags = new ConcurrentHashMap<>();
    private final Map<TagKey<Item>, List<Item>> tagMembersCache = new ConcurrentHashMap<>();
    private final Map<TagKey<Item>, Item> tagFallbackItem = new ConcurrentHashMap<>();

    // Flat tag data, built once per model by GraphBuilder (which owns the id map).
    // Registry holder.is(tag) checks are expensive — worse in modpacks with deep tag
    // hierarchies — and the resolver fallback scans did one per present item per slot.
    // These structures answer the same questions with array reads. Memory scales in
    // BITS per tag (BitSet), not objects, so hundreds of tags stay cheap.
    // Published as one volatile record; safe for render+worker readers.
    private record FlatTags(IdentityHashMap<Item, Integer> idMap, Set<TagKey<Item>>[] tagsById,
                            Map<TagKey<Item>, BitSet> bits) {}
    private volatile FlatTags flat;

    /**
     * Immutable inventory-derived tag data, published whole by {@link #ensureFresh}. The member-id
     * caches fill lazily; they are idempotent, so racing readers at worst compute the same array twice.
     */
    public static final class InventoryTags {
        private static final InventoryTags EMPTY = new InventoryTags(-1, Set.of(), Set.of(),
                Collections.emptyMap(), Collections.emptyMap(), Collections.emptyMap());
        final long generation;
        final Set<Item> inventoryKeys, containerKeys;
        final Map<TagKey<Item>, Set<Item>> inventory, container;
        final Map<TagKey<Item>, List<Item>> craftable;
        final Map<TagKey<Item>, int[]> inventoryIds = new ConcurrentHashMap<>();
        final Map<TagKey<Item>, int[]> craftableIds = new ConcurrentHashMap<>();

        private InventoryTags(long generation, Set<Item> inventoryKeys, Set<Item> containerKeys,
                              Map<TagKey<Item>, Set<Item>> inventory,
                              Map<TagKey<Item>, Set<Item>> container,
                              Map<TagKey<Item>, List<Item>> craftable) {
            this.generation = generation;
            this.inventoryKeys = inventoryKeys;
            this.containerKeys = containerKeys;
            this.inventory = inventory;
            this.container = container;
            this.craftable = craftable;
        }
    }

    private volatile InventoryTags tags = InventoryTags.EMPTY;

    /**
     * Brings the inventory-derived tag data up to date with {@code snapshot}. Only key sets
     * matter: counts are used as "> 0", which key presence already implies.
     */
    public void ensureFresh(InventorySnapshot snapshot, RecipeIndex recipeIndex) {
        InventoryTags cur = tags;
        Set<Item> invKeys = snapshot.inventory().keySet();
        Set<Item> contKeys = snapshot.container().keySet();
        if (cur.inventoryKeys.equals(invKeys) && cur.containerKeys.equals(contKeys)) return;
        if (snapshot.generation() < cur.generation) return;   // a newer snapshot already published its data
        InventoryTags next = build(snapshot.generation(), invKeys, contKeys, snapshot.inventory(), recipeIndex);
        synchronized (this) {
            if (tags.generation <= next.generation) tags = next;
        }
    }

    /** The currently published inventory-derived tag data (never null). */
    public InventoryTags currentTags() {
        return tags;
    }

    /**
     * Tags whose craftable-member list differs between {@code previous} and the current data
     * (order-sensitive: resolver picks depend on it).
     */
    public Set<TagKey<Item>> craftableChangedSince(InventoryTags previous) {
        InventoryTags current = tags;
        if (previous == null || previous == current) return Set.of();
        Set<TagKey<Item>> all = new HashSet<>(previous.craftable.keySet());
        all.addAll(current.craftable.keySet());
        Set<TagKey<Item>> changed = new HashSet<>();
        for (TagKey<Item> tag : all) {
            if (!Objects.equals(previous.craftable.get(tag), current.craftable.get(tag))) changed.add(tag);
        }
        return changed.isEmpty() ? Set.of() : Set.copyOf(changed);
    }

    /** Recursively collects the tags referenced by a slot tree. */
    public void collectTags(SlotDisplay slot) {
        if (slot instanceof SlotDisplay.TagSlotDisplay d) {
            TagKey<Item> tag = RecipeDisplays.getSlotTag(d);
            if (tag != null) knownTags.add(tag);
        } else if (slot instanceof SlotDisplay.Composite d) {
            for (SlotDisplay sub : d.contents()) collectTags(sub);
        } else if (slot instanceof SlotDisplay.WithRemainder d) {
            collectTags(d.input());
        }
    }

    /** Tags referenced by the recipe set (read by the graph builder for flat tag data). */
    public Set<TagKey<Item>> knownTags() {
        return knownTags;
    }

    /**
     * Installs id-indexed tag data for this model. Called once from
     * {@link GraphBuilder#build} after the flat id map exists; clears the
     * registry-derived memo (same content, but the flat copy is canonical).
     */
    public void setFlatTagData(IdentityHashMap<Item, Integer> idMap,
                               Set<TagKey<Item>>[] tagsById,
                               Map<TagKey<Item>, BitSet> bitsByTag) {
        flat = new FlatTags(idMap, tagsById, bitsByTag);
        this.itemToTags.clear();
    }

    /**
     * Flat-id membership test for a tag: array read instead of a registry
     * {@code holder.is(tag)} walk. Returns null when flat data isn't installed yet
     * (pre-graph) — callers fall back to the registry check.
     */
    public BitSet flatTagBits(TagKey<Item> tag) {
        FlatTags f = flat;
        return f == null ? null : f.bits().get(tag);
    }

    /** Lazily memoised set of known tags an item belongs to. */
    public Set<TagKey<Item>> tagsOf(Item item) {
        Set<TagKey<Item>> existing = itemToTags.get(item);
        if (existing != null) return existing;
        Set<TagKey<Item>> result = computeTagsOf(item);
        itemToTags.put(item, result);
        return result;
    }

    private Set<TagKey<Item>> computeTagsOf(Item item) {
        FlatTags f = flat;
        if (f != null) {
            Integer id = f.idMap().get(item);
            if (id != null && id >= 0 && id < f.tagsById().length) {
                Set<TagKey<Item>> flatTags = f.tagsById()[id];
                return flatTags != null ? flatTags : Set.of();
            }
            // Item outside the graph (e.g. a pure inventory item): fall through to registry.
        }
        // Headless tests have no tag bindings, so tags() can throw; with no known tags there is
        // nothing to match anyway.
        if (knownTags.isEmpty()) return Set.of();
        // Items carry few tags; don't pay default-16 for each.
        Set<TagKey<Item>> found = new HashSet<>(4);
        item.builtInRegistryHolder().tags().forEach(tag -> {
            if (knownTags.contains(tag)) found.add(tag);
        });
        return found.isEmpty() ? Set.of() : found;
    }

    /** Registry members of a tag, cached; null if the lookup fails. */
    public List<Item> members(TagKey<Item> tag) {
        List<Item> cached = tagMembersCache.get(tag);
        if (cached != null) return cached;
        Minecraft client = Minecraft.getInstance();
        if (client != null && client.level != null) {
            var regOpt = client.level.registryAccess().lookupOrThrow(Registries.ITEM);
            var entriesOpt = regOpt.get(tag);
            if (entriesOpt.isEmpty()) return null;
            List<Item> items = new ArrayList<>(entriesOpt.get().size());
            for (var entry : entriesOpt.get()) items.add(entry.value());
            tagMembersCache.put(tag, items);
            return items;
        }
        // Headless fallback (tests / pre-world): the built-in registry is level-independent.
        var entriesOpt = BuiltInRegistries.ITEM.get(tag);
        if (entriesOpt.isEmpty()) return null;
        List<Item> items = new ArrayList<>(entriesOpt.get().size());
        for (var entry : entriesOpt.get()) items.add(entry.value());
        tagMembersCache.put(tag, items);
        return items;
    }

    /** Number of tags referenced by the recipe set (debug logging). */
    public int knownTagCount() {
        return knownTags.size();
    }

    /** Display fallback: the first registry member of a tag. */
    public ItemStack anyTagMember(TagKey<Item> tag) {
        Item item = tagFallbackItem(tag);
        return item != null ? new ItemStack(item) : ItemStack.EMPTY;
    }

    /** Allocation-free fallback item for hot loops (null = no members). */
    public Item tagFallbackItem(TagKey<Item> tag) {
        Item cached = tagFallbackItem.get(tag);
        if (cached != null) return cached;
        List<Item> members = members(tag);
        if (members == null || members.isEmpty()) return null;
        tagFallbackItem.put(tag, members.getFirst());
        return members.getFirst();
    }

    public Set<Item> inventoryTagMembers(TagKey<Item> tag) {
        return tags.inventory.get(tag);
    }

    /**
     * Flat ids of {@link #inventoryTagMembers} in set order (null pre-graph, when there is
     * no id map yet — callers use the set path). Non-graph members are dropped (their work
     * count reads 0 anyway). Cached per published {@link InventoryTags}: same sets ⟹ same
     * order ⟹ identical picks to the uncached path.
     */
    public int[] inventoryTagMemberIds(TagKey<Item> tag) {
        FlatTags f = flat;
        if (f == null || tag == null) return null;
        InventoryTags t = tags;
        return t.inventoryIds.computeIfAbsent(tag, k -> toFlatIds(f, t.inventory.get(k)));
    }

    /** Flat ids of {@link #craftableMembers} in list order (null pre-graph, see above). */
    public int[] craftableTagMemberIds(TagKey<Item> tag) {
        FlatTags f = flat;
        if (f == null || tag == null) return null;
        InventoryTags t = tags;
        return t.craftableIds.computeIfAbsent(tag, k -> toFlatIds(f, t.craftable.get(k)));
    }

    private static int[] toFlatIds(FlatTags f, Collection<Item> items) {
        if (items == null || items.isEmpty()) return new int[0];
        int[] ids = new int[items.size()];
        int n = 0;
        for (Item item : items) {
            Integer id = f.idMap().get(item);
            if (id != null) ids[n++] = id;
        }
        return n == ids.length ? ids : Arrays.copyOf(ids, n);
    }

    public Set<Item> containerTagMembers(TagKey<Item> tag) {
        return tags.container.get(tag);
    }

    /** Recipe outputs carrying the tag, direct-input items first. */
    public List<Item> craftableMembers(TagKey<Item> tag) {
        return tags.craftable.get(tag);
    }

    public int sumTagInventory(TagKey<Item> tag, Map<Item, Integer> inventory) {
        int total = 0;
        Set<Item> matches = tags.inventory.get(tag);
        if (matches != null) {
            for (Item item : matches) {
                total += inventory.getOrDefault(item, 0);
            }
        }
        return total;
    }

    /**
     * Derives all inventory-dependent tag data into local maps, so the caller publishes a
     * complete object and no reader can observe a partial rebuild.
     */
    private InventoryTags build(long generation, Set<Item> invKeys, Set<Item> contKeys,
                                Map<Item, Integer> inventory, RecipeIndex recipeIndex) {
        Map<TagKey<Item>, Set<Item>> invTags = new HashMap<>();
        for (Item item : invKeys) {
            for (TagKey<Item> tag : tagsOf(item)) {
                invTags.computeIfAbsent(tag, k -> new HashSet<>(4)).add(item);
            }
        }

        Map<TagKey<Item>, Set<Item>> contTags = new HashMap<>();
        for (Item item : contKeys) {
            for (TagKey<Item> tag : tagsOf(item)) {
                contTags.computeIfAbsent(tag, k -> new HashSet<>(4)).add(item);
            }
        }

        Map<TagKey<Item>, List<Item>> hasInputsMap = new HashMap<>();
        Map<TagKey<Item>, List<Item>> noInputsMap = new HashMap<>();
        for (Item item : recipeIndex.outputs()) {
            Set<TagKey<Item>> itemTags = tagsOf(item);
            if (itemTags.isEmpty()) continue;
            boolean directInputs = hasDirectInputs(item, inventory, invTags, recipeIndex);
            Map<TagKey<Item>, List<Item>> target = directInputs ? hasInputsMap : noInputsMap;
            for (TagKey<Item> tag : itemTags) {
                target.computeIfAbsent(tag, k -> new ArrayList<>()).add(item);
            }
        }
        Map<TagKey<Item>, List<Item>> craftable = new HashMap<>();
        for (TagKey<Item> tag : knownTags) {
            List<Item> has = hasInputsMap.get(tag);
            List<Item> no = noInputsMap.get(tag);
            if (has != null || no != null) {
                List<Item> combined = new ArrayList<>();
                if (has != null) combined.addAll(has);
                if (no != null) combined.addAll(no);
                craftable.put(tag, combined);
            }
        }
        return new InventoryTags(generation, invKeys, contKeys, invTags, contTags, craftable);
    }

    /** True when some recipe for {@code item} has every ingredient slot satisfied by the inventory. */
    private static boolean hasDirectInputs(Item item, Map<Item, Integer> inventory,
                                           Map<TagKey<Item>, Set<Item>> invTags, RecipeIndex recipeIndex) {
        List<RecipeDisplayEntry> recipes = recipeIndex.get(item);
        if (recipes == null) return false;
        outer: for (RecipeDisplayEntry entry : recipes) {
            List<SlotDisplay> slots = RecipeDisplays.getSlots(entry);
            if (slots == null) continue;
            for (SlotDisplay slot : slots) {
                if (!slotHasDirect(slot, inventory, invTags)) continue outer;
            }
            return true;
        }
        return false;
    }

    private static boolean slotHasDirect(SlotDisplay slot, Map<Item, Integer> inventory,
                                         Map<TagKey<Item>, Set<Item>> invTags) {
        if (slot instanceof SlotDisplay.Empty) return true;
        if (slot instanceof SlotDisplay.TagSlotDisplay t) {
            return invTags.containsKey(RecipeDisplays.getSlotTag(t));
        }
        if (slot instanceof SlotDisplay.Composite d) {
            for (SlotDisplay sub : d.contents()) {
                if (slotHasDirect(sub, inventory, invTags)) return true;
            }
            return false;
        }
        if (slot instanceof SlotDisplay.WithRemainder d) return slotHasDirect(d.input(), inventory, invTags);
        if (slot instanceof SlotDisplay.ItemSlotDisplay d) return inventory.getOrDefault(d.item().value(), 0) > 0;
        if (slot instanceof SlotDisplay.ItemStackSlotDisplay d) {
            return inventory.getOrDefault(d.stack().item().value(), 0) > 0;
        }
        return false;
    }
}
