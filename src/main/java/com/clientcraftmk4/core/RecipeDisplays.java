package com.clientcraftmk4.core;

import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.display.RecipeDisplay;
import net.minecraft.world.item.crafting.display.RecipeDisplayEntry;
import net.minecraft.world.item.crafting.display.ShapedCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.ShapelessCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.SlotDisplay;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Pure helpers over vanilla {@link SlotDisplay} / {@link RecipeDisplay} trees.
 * Everything here is a byte-equivalent port of MK4's RecipeResolver helpers;
 * the only structural change is that the tag caches are passed in explicitly
 * instead of being read from static fields.
 */
public final class RecipeDisplays {
    private RecipeDisplays() {}

    public static List<SlotDisplay> getSlots(RecipeDisplay display) {
        if (display instanceof ShapedCraftingRecipeDisplay s) return s.ingredients();
        if (display instanceof ShapelessCraftingRecipeDisplay s) return s.ingredients();
        return null;
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
            TagKey<Item> tag = d.tag();
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

    /** True if any ingredient slot of the entry's recipe strictly requires {@code target}. */
    public static boolean recipeConsumesItem(RecipeDisplayEntry entry, Item target) {
        List<SlotDisplay> slots = getSlots(entry.display());
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
        if (slot instanceof SlotDisplay.TagSlotDisplay d) return d.tag();
        if (slot instanceof SlotDisplay.WithRemainder d) return getSlotTag(d.input());
        return null;
    }
}
