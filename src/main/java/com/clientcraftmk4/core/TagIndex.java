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
 *   <li>{@code knownTags} / {@code itemToTags} — recipe-set determined, built at model time;</li>
 *   <li>{@code inventoryTagIndex} / {@code containerTagIndex} / {@code craftableTagIndex} —
 *       inventory determined, refreshed by {@link #refreshInventory}.</li>
 * </ul>
 *
 * A fresh instance is created per {@link CraftModel} rebuild, so the inventory-dependent
 * indices are automatically rebuilt against the new recipe set on the next snapshot read.
 */
public final class TagIndex {
    private final Set<TagKey<Item>> knownTags = new HashSet<>();
    private final Map<Item, Set<TagKey<Item>>> itemToTags = new HashMap<>();
    private final Map<TagKey<Item>, Set<Item>> inventoryTagIndex = new HashMap<>();
    private final Map<TagKey<Item>, Set<Item>> containerTagIndex = new HashMap<>();
    private final Map<TagKey<Item>, List<Item>> craftableTagIndex = new HashMap<>();
    private final Map<TagKey<Item>, List<Item>> tagMembersCache = new ConcurrentHashMap<>();
    private final Map<TagKey<Item>, Item> tagFallbackItem = new ConcurrentHashMap<>();
    private Set<Item> lastInventoryKeys = Set.of();
    private Set<Item> lastContainerKeys = Set.of();

    // Flat tag data, built once per model by GraphBuilder (which owns the id map).
    // Registry holder.is(tag) checks are expensive — worse in modpacks with deep tag
    // hierarchies — and the resolver fallback scans did one per present item per slot.
    // These structures answer the same questions with array reads. Memory scales in
    // BITS per tag (BitSet), not objects, so hundreds of tags stay cheap.
    // Published via the volatile flag (written last); safe for render+worker readers.
    private volatile boolean flatTagsReady = false;
    private IdentityHashMap<Item, Integer> flatIdMap = null;
    @SuppressWarnings("unchecked")
    private Set<TagKey<Item>>[] tagsByFlatId = new Set[0];
    private Map<TagKey<Item>, BitSet> tagBits = Map.of();

    // Flat member-id arrays for the resolver hot loop: same iteration order as the member
    // sets (snapshot once per indices version), so picks are identical to iterating the
    // sets — minus per-candidate map hashing. Bumped wherever the indices rebuild; the
    // single-entry versions below invalidate the caches on change.
    private volatile long tagIdsVersion = 0;
    private long invIdsVersion = -1;
    private final Map<TagKey<Item>, int[]> invMemberIds = new ConcurrentHashMap<>();
    private long craftIdsVersion = -1;
    private final Map<TagKey<Item>, int[]> craftMemberIds = new ConcurrentHashMap<>();

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
        this.flatIdMap = idMap;
        this.tagsByFlatId = tagsById;
        this.tagBits = bitsByTag;
        this.itemToTags.clear();
        this.flatTagsReady = true;
    }

    /**
     * Flat-id membership test for a tag: array read instead of a registry
     * {@code holder.is(tag)} walk. Returns null when flat data isn't installed yet
     * (pre-graph) — callers fall back to the registry check.
     */
    public BitSet flatTagBits(TagKey<Item> tag) {
        if (!flatTagsReady) return null;
        return tagBits.get(tag);
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
        if (flatTagsReady) {
            Integer id = flatIdMap.get(item);
            if (id != null && id >= 0 && id < tagsByFlatId.length) {
                Set<TagKey<Item>> flat = tagsByFlatId[id];
                return flat != null ? flat : Set.of();
            }
            // Item outside the graph (e.g. a pure inventory item): fall through to registry.
        }
        // Items carry few tags; don't pay default-16 for each.
        Set<TagKey<Item>> tags = new HashSet<>(4);
        var holder = item.builtInRegistryHolder();
        for (TagKey<Item> tag : knownTags) {
            if (tag != null && holder.is(tag)) tags.add(tag);
        }
        return tags.isEmpty() ? Set.of() : tags;
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
        return inventoryTagIndex.get(tag);
    }

    /**
     * Flat ids of {@link #inventoryTagMembers} in set order (null pre-graph, when there is
     * no id map yet — callers use the set path). Non-graph members are dropped (their work
     * count reads 0 anyway). Cached per indices version: same sets ⟹ same order ⟹ identical
     * picks to the uncached path.
     */
    public int[] inventoryTagMemberIds(TagKey<Item> tag) {
        if (flatIdMap == null || tag == null) return null;
        if (invIdsVersion != tagIdsVersion) {
            invMemberIds.clear();
            craftMemberIds.clear();
            invIdsVersion = craftIdsVersion = tagIdsVersion;
        }
        int[] ids = invMemberIds.get(tag);
        if (ids == null) {
            ids = toFlatIds(inventoryTagIndex.get(tag));
            invMemberIds.put(tag, ids);
        }
        return ids;
    }

    /** Flat ids of {@link #craftableMembers} in list order (null pre-graph, see above). */
    public int[] craftableTagMemberIds(TagKey<Item> tag) {
        if (flatIdMap == null || tag == null) return null;
        if (craftIdsVersion != tagIdsVersion) {
            invMemberIds.clear();
            craftMemberIds.clear();
            invIdsVersion = craftIdsVersion = tagIdsVersion;
        }
        int[] ids = craftMemberIds.get(tag);
        if (ids == null) {
            ids = toFlatIds(craftableTagIndex.get(tag));
            craftMemberIds.put(tag, ids);
        }
        return ids;
    }

    private int[] toFlatIds(Collection<Item> items) {
        if (items == null || items.isEmpty()) return new int[0];
        int[] ids = new int[items.size()];
        int n = 0;
        for (Item item : items) {
            Integer id = flatIdMap.get(item);
            if (id != null) ids[n++] = id;
        }
        return n == ids.length ? ids : Arrays.copyOf(ids, n);
    }

    public Set<Item> containerTagMembers(TagKey<Item> tag) {
        return containerTagIndex.get(tag);
    }

    /** Recipe outputs carrying the tag, direct-input items first. */
    public List<Item> craftableMembers(TagKey<Item> tag) {
        return craftableTagIndex.get(tag);
    }

    public int sumTagInventory(TagKey<Item> tag, Map<Item, Integer> inventory) {
        int total = 0;
        Set<Item> matches = inventoryTagIndex.get(tag);
        if (matches != null) {
            for (Item item : matches) {
                total += inventory.getOrDefault(item, 0);
            }
        }
        return total;
    }

    /**
     * Rebuilds the inventory/container/craftable indices (key-set guarded, like MK4).
     * Called on every snapshot read; internally cheap when nothing changed.
     *
     * @return tags whose craftable-member list changed (order-sensitive: resolver picks
     *         depend on it). Empty when nothing rebuilt. Feeds incremental dirtiness.
     */
    public Set<TagKey<Item>> refreshInventory(Map<Item, Integer> inventory, Map<Item, Integer> container, RecipeIndex recipeIndex) {
        Set<Item> invKeys = inventory.keySet();
        Set<Item> contKeys = container.keySet();
        if (invKeys.equals(lastInventoryKeys) && contKeys.equals(lastContainerKeys)) return Set.of();
        // Indices rebuild below: invalidate the cached member-id arrays (version-checked).
        tagIdsVersion++;
        // Snapshot the old craftable lists (replaced, never mutated, during the rebuild).
        Map<TagKey<Item>, List<Item>> prevCraftable = new HashMap<>(craftableTagIndex);

        inventoryTagIndex.clear();
        for (Item item : invKeys) {
            for (TagKey<Item> tag : tagsOf(item)) {
                inventoryTagIndex.computeIfAbsent(tag, k -> new HashSet<>(4)).add(item);
            }
        }

        containerTagIndex.clear();
        for (Item item : contKeys) {
            for (TagKey<Item> tag : tagsOf(item)) {
                containerTagIndex.computeIfAbsent(tag, k -> new HashSet<>(4)).add(item);
            }
        }

        lastInventoryKeys = new HashSet<>(invKeys);
        lastContainerKeys = new HashSet<>(contKeys);

        craftableTagIndex.clear();
        Map<TagKey<Item>, List<Item>> hasInputsMap = new HashMap<>();
        Map<TagKey<Item>, List<Item>> noInputsMap = new HashMap<>();
        for (Item item : recipeIndex.outputs()) {
            Set<TagKey<Item>> tags = tagsOf(item);
            if (tags.isEmpty()) continue;
            boolean directInputs = hasDirectInputs(item, inventory, recipeIndex);
            Map<TagKey<Item>, List<Item>> target = directInputs ? hasInputsMap : noInputsMap;
            for (TagKey<Item> tag : tags) {
                target.computeIfAbsent(tag, k -> new ArrayList<>()).add(item);
            }
        }
        for (TagKey<Item> tag : knownTags) {
            List<Item> has = hasInputsMap.get(tag);
            List<Item> no = noInputsMap.get(tag);
            if (has != null || no != null) {
                List<Item> combined = new ArrayList<>();
                if (has != null) combined.addAll(has);
                if (no != null) combined.addAll(no);
                craftableTagIndex.put(tag, combined);
            }
        }

        // Order-sensitive diff against the pre-rebuild lists: any content or order change
        // can alter resolver picks for that tag's slots.
        Set<TagKey<Item>> changed = null;
        Set<TagKey<Item>> all = new HashSet<>(prevCraftable.keySet());
        all.addAll(craftableTagIndex.keySet());
        for (TagKey<Item> tag : all) {
            if (!Objects.equals(prevCraftable.get(tag), craftableTagIndex.get(tag))) {
                if (changed == null) changed = new HashSet<>(4);
                changed.add(tag);
            }
        }
        return changed == null ? Set.of() : Set.copyOf(changed);
    }

    private boolean hasDirectInputs(Item item, Map<Item, Integer> inventory, RecipeIndex recipeIndex) {
        List<RecipeDisplayEntry> recipes = recipeIndex.get(item);
        if (recipes == null) return false;
        outer: for (RecipeDisplayEntry entry : recipes) {
            List<SlotDisplay> slots = RecipeDisplays.getSlots(entry);
            if (slots == null) continue;
            for (SlotDisplay slot : slots) {
                if (slot instanceof SlotDisplay.Empty) continue;
                if (slot instanceof SlotDisplay.TagSlotDisplay t) {
                    if (inventoryTagIndex.containsKey(RecipeDisplays.getSlotTag(t))) continue;
                    continue outer;
                }
                if (slot instanceof SlotDisplay.Composite d) {
                    boolean found = false;
                    for (SlotDisplay sub : d.contents()) {
                        Item r = RecipeDisplays.resolveSlotItem(sub, inventory, this, false);
                        if (r != null && inventory.getOrDefault(r, 0) > 0) { found = true; break; }
                    }
                    if (found) continue;
                    continue outer;
                }
                if (slot instanceof SlotDisplay.WithRemainder d) {
                    SlotDisplay inner = d.input();
                    if (inner instanceof SlotDisplay.TagSlotDisplay t) {
                        if (inventoryTagIndex.containsKey(RecipeDisplays.getSlotTag(t))) continue;
                        continue outer;
                    }
                }
                Item r = RecipeDisplays.resolveSlotItem(slot, inventory, this, false);
                if (r != null && inventory.getOrDefault(r, 0) > 0) continue;
                continue outer;
            }
            return true;
        }
        return false;
    }
}
