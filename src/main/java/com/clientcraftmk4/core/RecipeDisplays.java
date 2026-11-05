package com.clientcraftmk4.core;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.display.RecipeDisplay;
import net.minecraft.world.item.crafting.display.RecipeDisplayEntry;
import net.minecraft.world.item.crafting.display.RecipeDisplayId;
import net.minecraft.world.item.crafting.display.ShapedCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.ShapelessCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.SlotDisplay;

import java.util.Collection;
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
     * wrappers on every call (Stream + toList + tree allocs, ~5000x/refresh); the result
     * depends only on the display structure, so it is cached per {@link RecipeDisplayId} and
     * cleared on recipe reload via {@link #clearSlotsCache()} (called from
     * {@code CraftModel.markDirty/reset}).
     */
    private record CachedSlots(RecipeDisplay display, List<SlotDisplay> slots) {}

    /** A server change can reuse an id for another recipe, so a hit must also match the display object. */
    private static final ConcurrentHashMap<RecipeDisplayId, CachedSlots> SLOTS_CACHE =
            new ConcurrentHashMap<>();

    /** Drops all cached normalized slot lists (recipe set changed). */
    public static void clearSlotsCache() {
        SLOTS_CACHE.clear();
    }

    /** Cached variant for callers that have the entry (hot paths prefer this). */
    public static List<SlotDisplay> getSlots(RecipeDisplayEntry entry) {
        RecipeDisplay display = entry.display();
        CachedSlots cached = SLOTS_CACHE.get(entry.id());
        if (cached != null && cached.display() == display) return cached.slots();
        List<SlotDisplay> slots = getSlots(display);
        if (slots != null) SLOTS_CACHE.put(entry.id(), new CachedSlots(display, slots));
        return slots;
    }

    public static List<SlotDisplay> getSlots(RecipeDisplay display) {
        if (display instanceof ShapedCraftingRecipeDisplay s) {
            return s.ingredients().stream().map(RecipeDisplays::normalize).toList();
        }
        if (display instanceof ShapelessCraftingRecipeDisplay s) {
            return s.ingredients().stream().map(RecipeDisplays::normalize).toList();
        }
        return null;
    }

    /**
     * Unwraps the slot wrappers the walkers don't handle: WithAnyPotion and OnlyWithComponent
     * become their inner slot, and on 26.3+ a tag slot holding a direct item list becomes item
     * slots (26.2 built them as Composites). Composite and WithRemainder inputs are unwrapped
     * recursively.
     */
    public static SlotDisplay normalize(SlotDisplay slot) {
        if (slot instanceof SlotDisplay.WithAnyPotion p) return normalize(p.display());
        if (slot instanceof SlotDisplay.OnlyWithComponent c) return normalize(c.source());
        if (slot instanceof SlotDisplay.Composite c) {
            return new SlotDisplay.Composite(c.contents().stream().map(RecipeDisplays::normalize).toList());
        }
        if (slot instanceof SlotDisplay.WithRemainder r) {
            return new SlotDisplay.WithRemainder(normalize(r.input()), r.remainder());
        }
        //? if >=26.3 {
        if (slot instanceof SlotDisplay.TagSlotDisplay d && d.tag().unwrapKey().isEmpty()) {
            return new SlotDisplay.Composite(d.tag().stream()
                    .map(h -> (SlotDisplay) new SlotDisplay.ItemSlotDisplay(h))
                    .toList());
        }
        //?}
        return slot;
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
     * <p>Issue #8: pool draws (1) and (2) prefer the member with the largest count
     * instead of iteration order — a chest drawing from {@code #planks} consumes the
     * most abundant plank first (200 oak logs before 2 birch logs) and leaves scarce
     * types for type-specific recipes that may still need them. Ties fall back to
     * registry id so picks stay deterministic across runs.
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
                ItemStack best = mostAbundant(matches, inventory);
                if (!best.isEmpty()) return best;
            }
            if (workingCopy) {
                List<Item> craft = tags.craftableMembers(tag);
                if (craft != null) {
                    ItemStack best = mostAbundant(craft, inventory);
                    if (!best.isEmpty()) return best;
                }
            }
            List<Item> craftable = tags.craftableMembers(tag);
            if (craftable != null && !craftable.isEmpty()) return new ItemStack(craftable.getFirst());
            return tags.anyTagMember(tag);
        }
        if (display instanceof SlotDisplay.WithAnyPotion d) return resolveSlot(d.display(), inventory, tags, workingCopy);
        if (display instanceof SlotDisplay.WithRemainder d) return resolveSlot(d.input(), inventory, tags, workingCopy);
        if (display instanceof SlotDisplay.Composite d) {
            ItemStack fallback = ItemStack.EMPTY;
            ItemStack best = ItemStack.EMPTY;
            int bestCount = 0;
            for (SlotDisplay sub : d.contents()) {
                ItemStack r = resolveSlot(sub, inventory, tags, workingCopy);
                if (r.isEmpty()) continue;
                int count = inventory.getOrDefault(r.getItem(), 0);
                if (count > bestCount) {
                    best = r;
                    bestCount = count;      // ties keep the earlier content (list order)
                }
                if (bestCount == 0 && fallback.isEmpty()) fallback = r;
            }
            return bestCount > 0 ? best : fallback;
        }
        // DyedSlotDemo stays empty: resolving it would let a dyed chestplate stand in for a helmet.
        return ItemStack.EMPTY;
    }

    /** The member of {@code candidates} with the largest count in {@code inventory} (ties: registry id). */
    private static ItemStack mostAbundant(Collection<Item> candidates, Map<Item, Integer> inventory) {
        Item best = null;
        int bestCount = 0, bestId = Integer.MAX_VALUE;
        for (Item item : candidates) {
            int count = inventory.getOrDefault(item, 0);
            if (count <= 0) continue;
            int id = BuiltInRegistries.ITEM.getId(item);
            if (count > bestCount || (count == bestCount && id < bestId)) {
                best = item;
                bestCount = count;
                bestId = id;
            }
        }
        return best != null ? new ItemStack(best) : ItemStack.EMPTY;
    }

    /**
     * Allocation-free {@link #resolveSlot}: returns the resolved item directly (null = empty)
     * so hot loops avoid {@code new ItemStack} per slot per branch (100k+/resolve GC).
     * Tag logic identical to {@link #resolveSlot} — pool draws prefer the most abundant
     * member (issue #8), ties by registry id.
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
                Item best = mostAbundantItem(matches, inventory);
                if (best != null) return best;
            }
            if (workingCopy) {
                List<Item> craft = tags.craftableMembers(tag);
                if (craft != null) {
                    Item best = mostAbundantItem(craft, inventory);
                    if (best != null) return best;
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
            Item best = null;
            int bestCount = 0;
            for (SlotDisplay sub : d.contents()) {
                Item r = resolveSlotItem(sub, inventory, tags, workingCopy);
                if (r == null) continue;
                int count = inventory.getOrDefault(r, 0);
                if (count > bestCount) {
                    best = r;
                    bestCount = count;      // ties keep the earlier content (list order)
                }
                if (bestCount == 0 && fallback == null) fallback = r;
            }
            return bestCount > 0 ? best : fallback;
        }
        return null;
    }

    /** The candidate with the largest count in {@code inventory} (ties: registry id); null if none present. */
    private static Item mostAbundantItem(Collection<Item> candidates, Map<Item, Integer> inventory) {
        Item best = null;
        int bestCount = 0, bestId = Integer.MAX_VALUE;
        for (Item item : candidates) {
            int count = inventory.getOrDefault(item, 0);
            if (count <= 0) continue;
            int id = BuiltInRegistries.ITEM.getId(item);
            if (count > bestCount || (count == bestCount && id < bestId)) {
                best = item;
                bestCount = count;
                bestId = id;
            }
        }
        return best;
    }

    /** Registry id for equal-count tie breaks, so every resolver path picks the same item. */
    private static int registryId(Item item) {
        return BuiltInRegistries.ITEM.getId(item);
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
            // Precomputed member ids: same candidates as iterating the member
            // collections, minus per-candidate map hashing. Null pre-graph → legacy path.
            // Issue #8: pool draws prefer the most abundant member (ties by registry id,
            // so picks are deterministic) — scarce types stay available for the
            // type-specific recipes resolved before this flexible slot.
            int[] matchIds = tags.inventoryTagMemberIds(tag);
            if (matchIds != null && idToItem != null) {
                int best = -1, bestCount = 0;
                for (int mid : matchIds) {
                    int c = work.get(mid);
                    if (c <= 0) continue;
                    if (c > bestCount || (c == bestCount && registryId(idToItem[mid]) < registryId(idToItem[best]))) {
                        best = mid;
                        bestCount = c;
                    }
                }
                if (best >= 0) return idToItem[best];
            } else {
                Set<Item> matches = tags.inventoryTagMembers(tag);
                if (matches != null) {
                    Item best = null;
                    int bestCount = 0, bestId = Integer.MAX_VALUE;
                    for (Item item : matches) {
                        int c = work.get(graph.id(item));
                        if (c <= 0) continue;
                        int id = BuiltInRegistries.ITEM.getId(item);
                        if (c > bestCount || (c == bestCount && id < bestId)) {
                            best = item;
                            bestCount = c;
                            bestId = id;
                        }
                    }
                    if (best != null) return best;
                }
            }
            int[] craftIds = tags.craftableTagMemberIds(tag);
            if (craftIds != null && idToItem != null) {
                int best = -1, bestCount = 0;
                for (int cid : craftIds) {
                    int c = work.get(cid);
                    if (c <= 0) continue;
                    if (c > bestCount || (c == bestCount && registryId(idToItem[cid]) < registryId(idToItem[best]))) {
                        best = cid;
                        bestCount = c;
                    }
                }
                if (best >= 0) return idToItem[best];
            } else {
                List<Item> craft = tags.craftableMembers(tag);
                if (craft != null) {
                    Item best = null;
                    int bestCount = 0, bestId = Integer.MAX_VALUE;
                    for (Item item : craft) {
                        int c = work.get(graph.id(item));
                        if (c <= 0) continue;
                        int id = BuiltInRegistries.ITEM.getId(item);
                        if (c > bestCount || (c == bestCount && id < bestId)) {
                            best = item;
                            bestCount = c;
                            bestId = id;
                        }
                    }
                    if (best != null) return best;
                }
            }
            List<Item> craftable = tags.craftableMembers(tag);
            if (craftable != null && !craftable.isEmpty()) return craftable.getFirst();
            return tags.tagFallbackItem(tag);
        }
        if (display instanceof SlotDisplay.WithRemainder d) return resolveSlotItem(d.input(), work, graph, idToItem, tags);
        if (display instanceof SlotDisplay.Composite d) {
            Item fallback = null;
            Item best = null;
            int bestCount = 0;
            for (SlotDisplay sub : d.contents()) {
                Item r = resolveSlotItem(sub, work, graph, idToItem, tags);
                if (r == null) continue;
                int count = work.get(graph.id(r));
                if (count > bestCount) {
                    best = r;
                    bestCount = count;      // ties keep the earlier content (list order)
                }
                if (bestCount == 0 && fallback == null) fallback = r;
            }
            return bestCount > 0 ? best : fallback;
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

    public static TagKey<Item> getSlotTag(SlotDisplay slot) {
        if (slot instanceof SlotDisplay.TagSlotDisplay d) {
            //? if >=26.3 {
            return d.tag().unwrapKey().orElse(null);
            //?}
            //? if <26.3 {
            /*return d.tag();
            *///?}
        }
        if (slot instanceof SlotDisplay.WithRemainder d) return getSlotTag(d.input());
        return null;
    }
}
