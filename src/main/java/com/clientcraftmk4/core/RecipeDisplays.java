package com.clientcraftmk4.core;

import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.display.RecipeDisplay;
import net.minecraft.world.item.crafting.display.RecipeDisplayEntry;
import net.minecraft.world.item.crafting.display.RecipeDisplayId;
import net.minecraft.world.item.crafting.display.ShapedCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.ShapelessCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.SlotDisplay;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Pure helpers over vanilla {@link SlotDisplay} / {@link RecipeDisplay} trees.
 * Everything here is a byte-equivalent port of MK4's RecipeResolver helpers;
 * the only structural change is that the tag caches are passed in explicitly
 * instead of being read from static fields.
 */
public final class RecipeDisplays {
    private RecipeDisplays() {}

    /**
     * Normalized slot lists per recipe entry. {@link #normalize} rebuilds Composite/WithRemainder
     * wrappers on every call (Stream + toList + tree allocs, ~5000x/refresh on 26.3+); the result
     * depends only on the display structure, so it is cached per {@link RecipeDisplayId} and
     * cleared on recipe reload via {@link #clearSlotsCache()} (called from
     * {@code CraftModel.markDirty/reset}).
     */
    private static final ConcurrentHashMap<RecipeDisplayId, List<SlotDisplay>> SLOTS_CACHE =
            new ConcurrentHashMap<>();

    /** Drops all cached normalized slot lists (recipe set changed). */
    public static void clearSlotsCache() {
        SLOTS_CACHE.clear();
        clearTagCache();
    }

    /** Cached variant for callers that have the entry (hot paths prefer this). */
    public static List<SlotDisplay> getSlots(RecipeDisplayEntry entry) {
        List<SlotDisplay> cached = SLOTS_CACHE.get(entry.id());
        if (cached != null) return cached;
        List<SlotDisplay> slots = getSlots(entry.display());
        if (slots != null) SLOTS_CACHE.put(entry.id(), slots);
        return slots;
    }

    public static List<SlotDisplay> getSlots(RecipeDisplay display) {
        if (display instanceof ShapedCraftingRecipeDisplay s) {
            //? if >=26.3 {
            return s.ingredients().stream().map(RecipeDisplays::normalize).toList();
            //?}
            //? if <26.3 {
            /*return s.ingredients();*/
            //?}
        }
        if (display instanceof ShapelessCraftingRecipeDisplay s) {
            //? if >=26.3 {
            return s.ingredients().stream().map(RecipeDisplays::normalize).toList();
            //?}
            //? if <26.3 {
            /*return s.ingredients();*/
            //?}
        }
        return null;
    }

    /**
     * 26.3 tag slots may hold a direct item list (no tag key); unwrap those into
     * item slots so tag-only logic never sees them (26.2 built them as Composites).
     */
    public static SlotDisplay normalize(SlotDisplay slot) {
        //? if >=26.3 {
        if (slot instanceof SlotDisplay.TagSlotDisplay d && d.tag().unwrapKey().isEmpty()) {
            return new SlotDisplay.Composite(d.tag().stream()
                    .map(h -> (SlotDisplay) new SlotDisplay.ItemSlotDisplay(h))
                    .toList());
        }
        if (slot instanceof SlotDisplay.Composite c) {
            return new SlotDisplay.Composite(c.contents().stream().map(RecipeDisplays::normalize).toList());
        }
        if (slot instanceof SlotDisplay.WithRemainder r) {
            return new SlotDisplay.WithRemainder(normalize(r.input()), r.remainder());
        }
        return slot;
        //?}
        //? if <26.3 {
        /*return slot;*/
        //?}
    }

    public static boolean fitsInGrid(RecipeDisplay display, int gridSize) {
        if (display instanceof ShapedCraftingRecipeDisplay s) {
            return s.width() <= gridSize && s.height() <= gridSize;
        } else if (display instanceof ShapelessCraftingRecipeDisplay s) {
            List<SlotDisplay> ingredients = s.ingredients();
            int count = 0;
            for (int i = 0, len = ingredients.size(); i < len; i++) {
                if (!(ingredients.get(i) instanceof SlotDisplay.Empty)) count++;
            }
            return count <= gridSize * gridSize;
        }
        return false;
    }

    /** Resolves the result slot of a recipe display to its item (or null). */
    public static Item getOutputItem(RecipeDisplay display, Map<Item, Integer> inventory, TagIndex tags) {
        ItemStack out = resolveSlot(display.result(), inventory, tags, false);
        return out.isEmpty() ? null : out.getItem();
    }

    /** Resolves the result slot of a recipe display to its output count (0 if unresolvable). */
    public static int getOutputCount(RecipeDisplay display, Map<Item, Integer> inventory, TagIndex tags) {
        ItemStack out = resolveSlot(display.result(), inventory, tags, false);
        return out.isEmpty() ? 0 : out.getCount();
    }

    public static ItemStack resolveResult(RecipeDisplay display, Map<Item, Integer> inventory, TagIndex tags) {
        ItemStack out = resolveSlot(display.result(), inventory, tags, false);
        return out.isEmpty() ? ItemStack.EMPTY : out;
    }

    /** Convenience overloads against the live inventory snapshot (MK4's static-field behaviour). */
    public static Item getOutputItem(RecipeDisplay display, TagIndex tags) {
        return getOutputItem(display, InventoryProvider.latest().inventory(), tags);
    }

    public static int getOutputCount(RecipeDisplay display, TagIndex tags) {
        return getOutputCount(display, InventoryProvider.latest().inventory(), tags);
    }

    public static ItemStack resolveResult(RecipeDisplay display, TagIndex tags) {
        return resolveResult(display, InventoryProvider.latest().inventory(), tags);
    }

    /**
     * Resolves the "best" option for a slot given an inventory (byte-equivalent to MK4):
     * 1. inventory members matching a tag slot (via the inventory tag index);
     * 2. craftable members present in working copies (sub-crafted leftovers);
     * 3. the first sub-craftable member, so {@code trySubCraft} can handle it;
     * 4. any registry member, as a display fallback.
     *
     * @param workingCopy true when {@code inventory} is a simulation working copy
     *                    rather than the live cached snapshot (MK4 compared map identity).
     */
    public static ItemStack resolveSlot(SlotDisplay display, Map<Item, Integer> inventory, TagIndex tags, boolean workingCopy) {
        if (display instanceof SlotDisplay.Empty) return ItemStack.EMPTY;
        if (display instanceof SlotDisplay.ItemSlotDisplay d) return new ItemStack(d.item());
        if (display instanceof SlotDisplay.ItemStackSlotDisplay d) {
            return new ItemStack(d.stack().item().value(), d.stack().count());
        }
        if (display instanceof SlotDisplay.TagSlotDisplay d) {
            TagKey<Item> tag = getSlotTag(d);
            Set<Item> matches = tags.inventoryTagMembers(tag);
            if (matches != null) {
                for (Item item : matches) {
                    if (inventory.getOrDefault(item, 0) > 0) return new ItemStack(item);
                }
            }
            if (workingCopy) {
                List<Item> craft = tags.craftableMembers(tag);
                if (craft != null) {
                    for (Item item : craft) {
                        if (inventory.getOrDefault(item, 0) > 0) return new ItemStack(item);
                    }
                }
            }
            List<Item> craftable = tags.craftableMembers(tag);
            if (craftable != null && !craftable.isEmpty()) return new ItemStack(craftable.getFirst());
            return tags.anyTagMember(tag);
        }
        if (display instanceof SlotDisplay.WithRemainder d) return resolveSlot(d.input(), inventory, tags, workingCopy);
        if (display instanceof SlotDisplay.Composite d) {
            ItemStack fallback = ItemStack.EMPTY;
            for (SlotDisplay sub : d.contents()) {
                ItemStack r = resolveSlot(sub, inventory, tags, workingCopy);
                if (!r.isEmpty()) {
                    if (inventory.getOrDefault(r.getItem(), 0) > 0) return r;
                    if (fallback.isEmpty()) fallback = r;
                }
            }
            return fallback;
        }
        return ItemStack.EMPTY;
    }

    /**
     * Allocation-free {@link #resolveSlot}: returns the resolved item directly (null = empty)
     * so hot loops avoid {@code new ItemStack} per slot per branch (100k+/resolve GC).
     * Single-scan tag logic identical to {@link #resolveSlot}.
     */
    public static Item resolveSlotItem(SlotDisplay display, Map<Item, Integer> inventory,
                                       TagIndex tags, boolean workingCopy) {
        if (display instanceof SlotDisplay.Empty) return null;
        if (display instanceof SlotDisplay.ItemSlotDisplay d) return d.item().value();
        if (display instanceof SlotDisplay.ItemStackSlotDisplay d) return d.stack().item().value();
        if (display instanceof SlotDisplay.TagSlotDisplay d) {
            TagKey<Item> tag = getSlotTag(d);
            Set<Item> matches = tags.inventoryTagMembers(tag);
            if (matches != null) {
                for (Item item : matches) {
                    if (inventory.getOrDefault(item, 0) > 0) return item;
                }
            }
            if (workingCopy) {
                List<Item> craft = tags.craftableMembers(tag);
                if (craft != null) {
                    for (Item item : craft) {
                        if (inventory.getOrDefault(item, 0) > 0) return item;
                    }
                }
            }
            List<Item> craftable = tags.craftableMembers(tag);
            if (craftable != null && !craftable.isEmpty()) return craftable.getFirst();
            return tags.tagFallbackItem(tag);
        }
        if (display instanceof SlotDisplay.WithRemainder d)
            return resolveSlotItem(d.input(), inventory, tags, workingCopy);
        if (display instanceof SlotDisplay.Composite d) {
            Item fallback = null;
            for (SlotDisplay sub : d.contents()) {
                Item r = resolveSlotItem(sub, inventory, tags, workingCopy);
                if (r != null) {
                    if (inventory.getOrDefault(r, 0) > 0) return r;
                    if (fallback == null) fallback = r;
                }
            }
            return fallback;
        }
        return null;
    }

    /**
     * WorkMap-backed {@link #resolveSlotItem} for the resolver hot loops: tag membership is
     * tested against live working counts instead of a snapshot map.
     */
    public static Item resolveSlotItem(SlotDisplay display, WorkMap work, RecipeGraph graph, TagIndex tags) {
        RecipeGraph.GraphFlatData f = graph.flat();
        return resolveSlotItem(display, work, graph, f != null ? f.idToItem() : null, tags);
    }

    private static Item resolveSlotItem(SlotDisplay display, WorkMap work, RecipeGraph graph,
                                        Item[] idToItem, TagIndex tags) {
        if (display instanceof SlotDisplay.Empty) return null;
        if (display instanceof SlotDisplay.ItemSlotDisplay d) return d.item().value();
        if (display instanceof SlotDisplay.ItemStackSlotDisplay d) return d.stack().item().value();
        if (display instanceof SlotDisplay.TagSlotDisplay d) {
            TagKey<Item> tag = getSlotTag(d);
            // Precomputed member ids in set/list order: same picks as iterating the member
            // collections, minus per-candidate map hashing. Null pre-graph → legacy path.
            int[] matchIds = tags.inventoryTagMemberIds(tag);
            if (matchIds != null && idToItem != null) {
                for (int mid : matchIds) {
                    if (work.get(mid) > 0) return idToItem[mid];
                }
            } else {
                Set<Item> matches = tags.inventoryTagMembers(tag);
                if (matches != null) {
                    for (Item item : matches) {
                        if (work.get(graph.id(item)) > 0) return item;
                    }
                }
            }
            int[] craftIds = tags.craftableTagMemberIds(tag);
            if (craftIds != null && idToItem != null) {
                for (int cid : craftIds) {
                    if (work.get(cid) > 0) return idToItem[cid];
                }
            } else {
                List<Item> craft = tags.craftableMembers(tag);
                if (craft != null) {
                    for (Item item : craft) {
                        if (work.get(graph.id(item)) > 0) return item;
                    }
                }
            }
            List<Item> craftable = tags.craftableMembers(tag);
            if (craftable != null && !craftable.isEmpty()) return craftable.getFirst();
            return tags.tagFallbackItem(tag);
        }
        if (display instanceof SlotDisplay.WithRemainder d) return resolveSlotItem(d.input(), work, graph, idToItem, tags);
        if (display instanceof SlotDisplay.Composite d) {
            Item fallback = null;
            for (SlotDisplay sub : d.contents()) {
                Item r = resolveSlotItem(sub, work, graph, idToItem, tags);
                if (r != null) {
                    if (work.get(graph.id(r)) > 0) return r;
                    if (fallback == null) fallback = r;
                }
            }
            return fallback;
        }
        return null;
    }

    /** True if any ingredient slot of the entry's recipe strictly requires {@code target}. */
    public static boolean recipeConsumesItem(RecipeDisplayEntry entry, Item target) {
        List<SlotDisplay> slots = getSlots(entry);
        if (slots == null) return false;
        for (SlotDisplay slot : slots) {
            if (slotRequiresItem(slot, target)) return true;
        }
        return false;
    }

    /** True only if every option for this slot resolves to the target item. */
    private static boolean slotRequiresItem(SlotDisplay slot, Item target) {
        if (slot instanceof SlotDisplay.Empty) return false;
        if (slot instanceof SlotDisplay.ItemSlotDisplay d) return d.item().value().equals(target);
        if (slot instanceof SlotDisplay.ItemStackSlotDisplay d) return d.stack().item().value().equals(target);
        if (slot instanceof SlotDisplay.TagSlotDisplay) return false;
        if (slot instanceof SlotDisplay.Composite d) {
            for (SlotDisplay sub : d.contents()) {
                if (!slotRequiresItem(sub, target)) return false;
            }
            return !d.contents().isEmpty();
        }
        if (slot instanceof SlotDisplay.WithRemainder d) return slotRequiresItem(d.input(), target);
        return false;
    }

    /**
     * Slot → tag cache. On 26.3+ every call allocates an {@code Optional} plus a HolderSet
     * dereference, per tag slot per attempt. Slot→tag is static, so memoise it: identity
     * semantics (records would hash DEEP — structural hash of the whole subtree per lookup,
     * worse than the Optional). Vanilla slot objects are stable per recipe set; cleared with
     * the slots cache on reload so nothing leaks across datapack syncs.
     */
    private static final Map<SlotDisplay, TagKey<Item>> SLOT_TAG_CACHE =
            Collections.synchronizedMap(new IdentityHashMap<>());

    /** Drops the slot→tag memo alongside the normalized-slots cache (recipe set changed). */
    static void clearTagCache() {
        SLOT_TAG_CACHE.clear();
    }

    public static TagKey<Item> getSlotTag(SlotDisplay slot) {
        if (slot instanceof SlotDisplay.TagSlotDisplay d) {
            //? if >=26.3 {
            TagKey<Item> cached = SLOT_TAG_CACHE.get(slot);
            if (cached != null || SLOT_TAG_CACHE.containsKey(slot)) return cached;
            TagKey<Item> tag = d.tag().unwrapKey().orElse(null);
            SLOT_TAG_CACHE.put(slot, tag);
            return tag;
            //?}
            //? if <26.3 {
            /*return d.tag();
            *///?}
        }
        if (slot instanceof SlotDisplay.WithRemainder d) return getSlotTag(d.input());
        return null;
    }
}
