package com.clientcraftmk4.ui;

import com.clientcraftmk4.core.CraftModel;
import com.clientcraftmk4.core.GameContext;
import com.clientcraftmk4.core.RecipeGraph.GraphFlatData;
import com.clientcraftmk4.core.InventoryProvider;
import com.clientcraftmk4.core.InventorySnapshot;
import com.clientcraftmk4.core.RecipeDisplays;
import com.clientcraftmk4.core.TagIndex;
import com.clientcraftmk4.core.WorkMap;
import com.clientcraftmk4.core.resolver.ResolveContext;
import com.clientcraftmk4.mixin.accessor.RecipeCollectionAccessor;
import com.clientcraftmk4.pipeline.ResolvePipeline;
import net.minecraft.client.gui.screens.recipebook.RecipeCollection;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.display.RecipeDisplay;
import net.minecraft.world.item.crafting.display.RecipeDisplayEntry;
import net.minecraft.world.item.crafting.display.RecipeDisplayId;
import net.minecraft.world.item.crafting.display.ShapedCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.ShapelessCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.SlotDisplay;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;

/**
 * Builds the fake ingredient collection for the overlay (plan §13.2) and the
 * 3×3 ingredient grid with per-slot craftability/container tints (plan §5.8).
 * The grid is fed to vanilla's {@code OverlayRecipeComponent} via synthesized
 * negative-id {@link RecipeDisplayEntry}s — the same hijack strategy as MK4,
 * with the layout logic living here instead of in a mixin.
 */
public final class OverlayBuilder {
    public static class IngredientGrid {
        private final ItemStack[] items = new ItemStack[9];
        private final SlotDisplay[] slots = new SlotDisplay[9];
        private final boolean[] craftable = new boolean[9];
        private final boolean[] inContainer = new boolean[9];

        public ItemStack get(int index) { return items[index]; }
        public boolean hasCraftable(int index) { return craftable[index]; }
        public boolean isInContainer(int index) { return inContainer[index]; }
    }

    private static IngredientGrid activeGrid;

    private OverlayBuilder() {}

    public static IngredientGrid getActiveGrid() {
        return activeGrid;
    }

    public static void clearActiveGrid() {
        activeGrid = null;
    }

    public static RecipeCollection buildIngredientCollection(RecipeDisplayEntry originalEntry) {
        CraftModel model = CraftModel.current();
        if (model == null) return null;
        int gridSize = GameContext.gridSize();
        InventorySnapshot snap = InventoryProvider.current();

        RecipeDisplay display = originalEntry.display();
        List<SlotDisplay> slots = RecipeDisplays.getSlots(display);
        if (slots == null || slots.isEmpty()) return null;

        IngredientGrid grid = new IngredientGrid();
        Arrays.fill(grid.items, ItemStack.EMPTY);
        fillGrid(grid, display, slots, model, gridSize, snap);
        computeGridCraftability(grid, model, gridSize, snap);

        activeGrid = grid;
        return buildFakeCollection(grid, originalEntry);
    }

    public static void refreshActiveGridCraftability() {
        if (activeGrid != null) {
            CraftModel model = CraftModel.current();
            if (model == null) return;
            computeGridCraftability(activeGrid, model, GameContext.gridSize(), InventoryProvider.current());
        }
    }

    // --- Grid filling (plan §5.8 G1/G2) ---

    private static void fillGrid(IngredientGrid grid, RecipeDisplay display, List<SlotDisplay> slots,
                                 CraftModel model, int gridSize, InventorySnapshot snap) {
        if (display instanceof ShapedCraftingRecipeDisplay shaped) {
            int w = shaped.width(), h = shaped.height();
            for (int row = 0; row < h; row++) for (int col = 0; col < w; col++) {
                int srcIdx = row * w + col;
                if (srcIdx >= slots.size()) continue;
                SlotDisplay slot = slots.get(srcIdx);
                if (slot instanceof SlotDisplay.Empty) continue;
                ItemStack resolved = resolveGridSlot(slot, model, gridSize, snap);
                if (!resolved.isEmpty()) {
                    int gridIdx = row * 3 + col;
                    grid.items[gridIdx] = resolved;
                    grid.slots[gridIdx] = slot;
                }
            }
        } else {
            int idx = 0;
            for (int i = 0, len = slots.size(); i < len && idx < 9; i++) {
                SlotDisplay slot = slots.get(i);
                if (slot instanceof SlotDisplay.Empty) continue;
                ItemStack resolved = resolveGridSlot(slot, model, gridSize, snap);
                if (!resolved.isEmpty()) {
                    grid.items[idx] = resolved;
                    grid.slots[idx] = slot;
                }
                idx++;
            }
        }
    }

    private static ItemStack resolveGridSlot(SlotDisplay slot, CraftModel model, int gridSize, InventorySnapshot snap) {
        TagIndex tags = model.tagIndex();
        // First try: item already in inventory
        ItemStack direct = RecipeDisplays.resolveSlot(slot, snap.inventory(), tags, false);
        if (!direct.isEmpty() && snap.inventory().getOrDefault(direct.getItem(), 0) > 0) return direct;

        // Second try: find an item we can sub-craft that satisfies this slot
        ItemStack craftable = findCraftableForSlot(slot, model, gridSize, snap);
        if (craftable != null) return craftable;

        // Fallback: return whatever resolveSlot gives
        return direct.isEmpty() ? RecipeDisplays.resolveSlot(slot, snap.inventory(), tags, false) : direct;
    }

    private static ItemStack findCraftableForSlot(SlotDisplay slot, CraftModel model, int gridSize, InventorySnapshot snap) {
        TagIndex tags = model.tagIndex();
        if (slot instanceof SlotDisplay.TagSlotDisplay d) {
            TagKey<Item> tag = d.tag();
            Set<Item> invMatches = tags.inventoryTagMembers(tag);
            if (invMatches != null && !invMatches.isEmpty()) {
                return new ItemStack(invMatches.iterator().next());
            }
            List<Item> craftable = tags.craftableMembers(tag);
            if (craftable != null) {
                for (Item item : craftable) {
                    if (tryConsumeSubCraft(item, new HashMap<>(snap.inventory()), model, gridSize)) return new ItemStack(item);
                }
            }
        } else if (slot instanceof SlotDisplay.Composite d) {
            ItemStack fallback = ItemStack.EMPTY;
            for (SlotDisplay sub : d.contents()) {
                ItemStack r = RecipeDisplays.resolveSlot(sub, snap.inventory(), tags, false);
                if (r.isEmpty()) continue;
                if (fallback.isEmpty()) fallback = r;
                if (snap.inventory().getOrDefault(r.getItem(), 0) > 0) return r;
                if (tryConsumeSubCraft(r.getItem(), new HashMap<>(snap.inventory()), model, gridSize)) return r;
            }
            if (!fallback.isEmpty()) return fallback;
        } else if (slot instanceof SlotDisplay.WithRemainder d) {
            return findCraftableForSlot(d.input(), model, gridSize, snap);
        }
        return null;
    }

    // --- Craftability / container tints (plan §5.8 G3/G4) ---

    private static void computeGridCraftability(IngredientGrid grid, CraftModel model, int gridSize, InventorySnapshot snap) {
        Map<Item, Integer> remaining = new HashMap<>(snap.inventory());
        Set<Item> containerAvailable = ResolvePipeline.current().containerAvailableItems();
        boolean hasContainer = !containerAvailable.isEmpty();

        for (int i = 0; i < 9; i++) {
            ItemStack stack = grid.items[i];
            if (stack.isEmpty()) {
                grid.craftable[i] = true;
                continue;
            }
            Item item = stack.getItem();
            int have = remaining.getOrDefault(item, 0);
            if (have >= 1) {
                remaining.put(item, have - 1);
                grid.craftable[i] = true;
            } else if (hasContainer) {
                Item found = findInSet(grid.slots[i], item, containerAvailable, model.tagIndex());
                if (found != null) {
                    grid.inContainer[i] = true;
                    if (found != item) grid.items[i] = new ItemStack(found);
                } else {
                    grid.craftable[i] = tryConsumeSubCraft(item, remaining, model, gridSize);
                }
            } else {
                grid.craftable[i] = tryConsumeSubCraft(item, remaining, model, gridSize);
            }
        }
    }

    /** Tests if {@code item} can be sub-crafted from {@code available}; if so, deducts consumed resources. */
    private static boolean tryConsumeSubCraft(Item item, Map<Item, Integer> available, CraftModel model, int gridSize) {
        List<RecipeDisplayEntry> subs = model.recipeIndex().get(item);
        if (subs == null) return false;
        for (RecipeDisplayEntry sub : subs) {
            if (!RecipeDisplays.fitsInGrid(sub.display(), gridSize)) continue;
            WorkMap work = WorkMap.from(available, model.graph());
            if (ResolveContext.of(model, gridSize).resolve(sub, work, null, new HashSet<>(), 0, null)) {
                // Write the journal-backed changes back into the plain map.
                GraphFlatData f = model.graph().flat();
                for (int id = 0; id < f.n(); id++) {
                    int c = work.get(id);
                    Item it = f.idToItem()[id];
                    if (c != available.getOrDefault(it, 0)) available.put(it, c);
                }
                int outputCount = RecipeDisplays.getOutputCount(sub.display(), model.tagIndex());
                if (outputCount > 1) available.merge(item, outputCount - 1, Integer::sum);
                return true;
            }
        }
        return false;
    }

    private static Item findInSet(SlotDisplay slot, Item resolved, Set<Item> items, TagIndex tags) {
        if (items.contains(resolved)) return resolved;
        if (slot instanceof SlotDisplay.TagSlotDisplay d) {
            Set<Item> contMatches = tags.containerTagMembers(d.tag());
            if (contMatches != null) {
                for (Item item : contMatches) if (items.contains(item)) return item;
            }
            List<Item> members = tags.members(d.tag());
            if (members != null) {
                for (Item member : members) if (items.contains(member)) return member;
            }
        } else if (slot instanceof SlotDisplay.Composite comp) {
            for (SlotDisplay sub : comp.contents()) {
                Item item = findInSet(sub, resolved, items, tags);
                if (item != null) return item;
            }
        } else if (slot instanceof SlotDisplay.WithRemainder rem) {
            return findInSet(rem.input(), resolved, items, tags);
        }
        return null;
    }

    // --- Fake collection (plan §13.2) ---

    private static RecipeCollection buildFakeCollection(IngredientGrid grid, RecipeDisplayEntry originalEntry) {
        List<RecipeDisplayEntry> entries = new ArrayList<>(9);
        for (int i = 0; i < 9; i++) {
            SlotDisplay ingredientSlot = grid.items[i].isEmpty()
                    ? SlotDisplay.Empty.INSTANCE
                    : new SlotDisplay.ItemSlotDisplay(grid.items[i].getItem().builtInRegistryHolder());
            entries.add(new RecipeDisplayEntry(
                    new RecipeDisplayId(-(i + 1)),
                    new ShapelessCraftingRecipeDisplay(List.of(ingredientSlot), ingredientSlot, SlotDisplay.Empty.INSTANCE),
                    OptionalInt.empty(), originalEntry.category(), Optional.empty()));
        }
        RecipeCollection collection = new RecipeCollection(entries);
        RecipeCollectionAccessor acc = (RecipeCollectionAccessor) collection;
        for (RecipeDisplayEntry e : entries) { acc.displayable().add(e.id()); acc.craftable().add(e.id()); }
        return collection;
    }
}
