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

    /** Recursively collects the tags referenced by a slot tree. */
    public void collectTags(SlotDisplay slot) {
        if (slot instanceof SlotDisplay.TagSlotDisplay d) {
            knownTags.add(d.tag());
        } else if (slot instanceof SlotDisplay.Composite d) {
            for (SlotDisplay sub : d.contents()) collectTags(sub);
        } else if (slot instanceof SlotDisplay.WithRemainder d) {
            collectTags(d.input());
        }
    }

    /** Lazily memoised set of known tags an item belongs to. */
    public Set<TagKey<Item>> tagsOf(Item item) {
        Set<TagKey<Item>> existing = itemToTags.get(item);
        if (existing != null) return existing;
        Set<TagKey<Item>> tags = new HashSet<>();
        var holder = item.builtInRegistryHolder();
        for (TagKey<Item> tag : knownTags) {
            if (holder.is(tag)) tags.add(tag);
        }
        Set<TagKey<Item>> result = tags.isEmpty() ? Set.of() : tags;
        itemToTags.put(item, result);
        return result;
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
            List<Item> items = new ArrayList<>();
            for (var entry : entriesOpt.get()) items.add(entry.value());
            tagMembersCache.put(tag, items);
            return items;
        }
        // Headless fallback (tests / pre-world): the built-in registry is level-independent.
        var entriesOpt = BuiltInRegistries.ITEM.get(tag);
        if (entriesOpt.isEmpty()) return null;
        List<Item> items = new ArrayList<>();
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
        Item cached = tagFallbackItem.get(tag);
        if (cached != null) return new ItemStack(cached);
        List<Item> members = members(tag);
        if (members == null || members.isEmpty()) return ItemStack.EMPTY;
        tagFallbackItem.put(tag, members.getFirst());
        return new ItemStack(members.getFirst());
    }

    public Set<Item> inventoryTagMembers(TagKey<Item> tag) {
        return inventoryTagIndex.get(tag);
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
     */
    public void refreshInventory(Map<Item, Integer> inventory, Map<Item, Integer> container, RecipeIndex recipeIndex) {
        Set<Item> invKeys = inventory.keySet();
        Set<Item> contKeys = container.keySet();
        if (invKeys.equals(lastInventoryKeys) && contKeys.equals(lastContainerKeys)) return;

        inventoryTagIndex.clear();
        for (Item item : invKeys) {
            for (TagKey<Item> tag : tagsOf(item)) {
                inventoryTagIndex.computeIfAbsent(tag, k -> new HashSet<>()).add(item);
            }
        }

        containerTagIndex.clear();
        for (Item item : contKeys) {
            for (TagKey<Item> tag : tagsOf(item)) {
                containerTagIndex.computeIfAbsent(tag, k -> new HashSet<>()).add(item);
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
    }

    private boolean hasDirectInputs(Item item, Map<Item, Integer> inventory, RecipeIndex recipeIndex) {
        List<RecipeDisplayEntry> recipes = recipeIndex.get(item);
        if (recipes == null) return false;
        outer: for (RecipeDisplayEntry entry : recipes) {
            List<SlotDisplay> slots = RecipeDisplays.getSlots(entry.display());
            if (slots == null) continue;
            for (SlotDisplay slot : slots) {
                if (slot instanceof SlotDisplay.Empty) continue;
                if (slot instanceof SlotDisplay.TagSlotDisplay t) {
                    if (inventoryTagIndex.containsKey(t.tag())) continue;
                    continue outer;
                }
                if (slot instanceof SlotDisplay.Composite d) {
                    boolean found = false;
                    for (SlotDisplay sub : d.contents()) {
                        ItemStack r = RecipeDisplays.resolveSlot(sub, inventory, this, false);
                        if (!r.isEmpty() && inventory.getOrDefault(r.getItem(), 0) > 0) { found = true; break; }
                    }
                    if (found) continue;
                    continue outer;
                }
                if (slot instanceof SlotDisplay.WithRemainder d) {
                    SlotDisplay inner = d.input();
                    if (inner instanceof SlotDisplay.TagSlotDisplay t) {
                        if (inventoryTagIndex.containsKey(t.tag())) continue;
                        continue outer;
                    }
                }
                ItemStack r = RecipeDisplays.resolveSlot(slot, inventory, this, false);
                if (!r.isEmpty() && inventory.getOrDefault(r.getItem(), 0) > 0) continue;
                continue outer;
            }
            return true;
        }
        return false;
    }
}
