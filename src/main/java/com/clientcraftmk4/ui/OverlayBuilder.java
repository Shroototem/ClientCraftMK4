package com.clientcraftmk4.ui;

import com.clientcraftmk4.core.CraftModel;
import com.clientcraftmk4.core.GameContext;
import com.clientcraftmk4.core.RecipeGraph;
import com.clientcraftmk4.core.RecipeGraph.GraphFlatData;
import com.clientcraftmk4.core.RecipeIndex;
import com.clientcraftmk4.core.InventoryProvider;
import com.clientcraftmk4.core.InventorySnapshot;
import com.clientcraftmk4.core.RecipeDisplays;
import com.clientcraftmk4.core.TagIndex;
import com.clientcraftmk4.core.WorkMap;
import com.clientcraftmk4.core.resolver.ResolveContext;
import com.clientcraftmk4.mixin.accessor.RecipeCollectionAccessor;
import com.clientcraftmk4.pipeline.ResolvePipeline;
import com.clientcraftmk4.pipeline.ResolveResult;
import net.minecraft.client.gui.screens.recipebook.RecipeCollection;
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
        /** Tiles as filled by fillGrid; every craftability pass restarts from these. */
        private final ItemStack[] baseItems = new ItemStack[9];
        private final SlotDisplay[] slots = new SlotDisplay[9];
        private final boolean[] craftable = new boolean[9];
        private final boolean[] inContainer = new boolean[9];
        /** Output of the recipe this grid is showing; sub-crafts must not loop back to it. */
        private Item recipeOutput;
        /** Result panel stack, resolved once per build. */
        private ItemStack result = ItemStack.EMPTY;

        public ItemStack get(int index) { return items[index]; }
        public boolean hasCraftable(int index) { return craftable[index]; }
        public boolean isInContainer(int index) { return inContainer[index]; }
        public Item recipeOutput() { return recipeOutput; }
        public ItemStack result() { return result; }
    }

    private static IngredientGrid activeGrid;
    private static CraftabilityCache craftabilityCache;
    /** Client tick of the last per-frame craftability refresh. */
    private static long lastRefreshTick = -1;

    /**
     * Memoisation key for the craftability pass. Grid contents are pinned by
     * identity (a new grid instance is built for every variant switch), and the
     * resolve-result reference pins the container-available set — so the pass
     * reruns only when an input that can change its outcome has changed.
     */
    private record CraftabilityCache(IngredientGrid grid, long snapshotGeneration,
                                     long modelGeneration, int gridSize, ResolveResult result) {}

    private OverlayBuilder() {}

    public static IngredientGrid getActiveGrid() {
        return activeGrid;
    }

    public static void clearActiveGrid() {
        activeGrid = null;
        craftabilityCache = null;
        lastRefreshTick = -1;
    }

    public static RecipeCollection buildIngredientCollection(RecipeDisplayEntry originalEntry) {
        CraftModel model = CraftModel.current();
        if (model == null) return null;
        int gridSize = GameContext.gridSize();
        InventorySnapshot snap = InventoryProvider.current();
        model.tagIndex().ensureFresh(snap, model.recipeIndex());

        RecipeDisplay display = originalEntry.display();
        List<SlotDisplay> slots = RecipeDisplays.getSlots(originalEntry);
        if (slots == null || slots.isEmpty()) return null;

        IngredientGrid grid = new IngredientGrid();
        grid.recipeOutput = RecipeDisplays.getOutputItem(display, snap.inventory(), model.tagIndex());
        grid.result = RecipeDisplays.resolveResult(display, snap.inventory(), model.tagIndex());
        Arrays.fill(grid.items, ItemStack.EMPTY);
        fillGrid(grid, display, slots, model, gridSize, snap);
        System.arraycopy(grid.items, 0, grid.baseItems, 0, grid.items.length);

        activeGrid = grid;
        refreshActiveGridCraftability();
        return buildFakeCollection(grid, originalEntry);
    }

    /**
     * Refreshes the active grid's craftability/container tints. The memoisation
     * below skips the pass when nothing that feeds it has changed.
     */
    public static void refreshActiveGridCraftability() {
        IngredientGrid grid = activeGrid;
        if (grid == null) return;
        CraftModel model = CraftModel.current();
        if (model == null) return;
        int gridSize = GameContext.gridSize();
        InventorySnapshot snap = InventoryProvider.current();
        model.tagIndex().ensureFresh(snap, model.recipeIndex());
        ResolveResult result = ResolvePipeline.current();
        CraftabilityCache cached = craftabilityCache;
        if (cached != null && cached.grid() == grid
                && cached.snapshotGeneration() == snap.generation()
                && cached.modelGeneration() == model.modelGeneration()
                && cached.gridSize() == gridSize
                && cached.result() == result) {
            return;
        }
        // Container-visible items for the purple tint: items physically inside
        // containers (the snapshot's container map) ∪ outputs of container-craftable
        // recipes (items obtainable only by crafting from container contents).
        // The recipe-output set alone never contains raw materials without a
        // crafting recipe (cobblestone, diamonds in a shulker) — those sat in a
        // container yet tinted gray instead of purple (issue #9).
        Set<Item> containerAvailable = result.containerAvailableItems();
        if (!snap.container().isEmpty()) {
            if (containerAvailable.isEmpty()) {
                containerAvailable = snap.container().keySet();
            } else {
                Set<Item> merged = new HashSet<>(containerAvailable.size() + snap.container().size());
                merged.addAll(containerAvailable);
                merged.addAll(snap.container().keySet());
                containerAvailable = merged;
            }
        }
        computeGridCraftability(grid, model, gridSize, snap, containerAvailable);
        craftabilityCache = new CraftabilityCache(
                grid, snap.generation(), model.modelGeneration(), gridSize, result);
    }

    /**
     * Per-frame entry point for the render path: runs the craftability pass at most
     * once per client tick.
     */
    public static void refreshActiveGridForFrame() {
        CraftabilityCache cached = craftabilityCache;
        long tick = GameContext.clientTick();
        if (tick == lastRefreshTick && cached != null && cached.grid() == activeGrid) return;
        lastRefreshTick = tick;
        refreshActiveGridCraftability();
    }

    /** True when the planner could craft {@code target} right now from the physical inventory. */
    public static boolean canCraft(RecipeDisplayEntry target) {
        CraftModel model = CraftModel.current();
        if (model == null) return false;
        InventorySnapshot snap = InventoryProvider.current();
        model.tagIndex().ensureFresh(snap, model.recipeIndex());
        WorkMap work = WorkMap.from(snap.inventory(), model.graph());
        return ResolveContext.of(model, GameContext.gridSize()).resolve(target, work, null, new HashSet<>(), 0, null);
    }

    // --- Grid filling (plan §5.8 G1/G2) ---

    private static void fillGrid(IngredientGrid grid, RecipeDisplay display, List<SlotDisplay> slots,
                                 CraftModel model, int gridSize, InventorySnapshot snap) {
        SubCraftCtx ctx = new SubCraftCtx(model, gridSize, grid.recipeOutput());
        if (display instanceof ShapedCraftingRecipeDisplay shaped) {
            int w = shaped.width(), h = shaped.height();
            for (int row = 0; row < h; row++) for (int col = 0; col < w; col++) {
                int srcIdx = row * w + col;
                if (srcIdx >= slots.size()) continue;
                SlotDisplay slot = slots.get(srcIdx);
                if (slot instanceof SlotDisplay.Empty) continue;
                ItemStack resolved = resolveGridSlot(slot, ctx, snap);
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
                ItemStack resolved = resolveGridSlot(slot, ctx, snap);
                if (!resolved.isEmpty()) {
                    grid.items[idx] = resolved;
                    grid.slots[idx] = slot;
                }
                idx++;
            }
        }
    }

    private static ItemStack resolveGridSlot(SlotDisplay slot, SubCraftCtx ctx, InventorySnapshot snap) {
        TagIndex tags = ctx.model.tagIndex();
        // First try: item already in inventory
        ItemStack direct = RecipeDisplays.resolveSlot(slot, snap.inventory(), tags, false);
        if (!direct.isEmpty() && snap.inventory().getOrDefault(direct.getItem(), 0) > 0) return direct;

        // Second try: find an item we can sub-craft that satisfies this slot
        ItemStack craftable = findCraftableForSlot(slot, ctx, snap);
        if (craftable != null) return craftable;

        // Fallback: resolveSlot is deterministic, so recomputing an empty result would
        // just yield empty again — return what we already have.
        return direct;
    }

    /**
     * Per-overlay-build resolve state: the old code rebuilt the graph handle,
     * resolve context and in-progress set per sub-recipe attempt. Hoisting is
     * outcome-identical (same model/gridSize throughout one build), except
     * {@code rootOutput} — the viewed recipe's output, passed through to the
     * resolver so looped sub-crafts are rejected like everywhere else.
     */
    private static final class SubCraftCtx {
        final CraftModel model;
        final int gridSize;
        final RecipeGraph graph;
        final GraphFlatData flat;
        final ResolveContext resolveCtx;
        final Item rootOutput;
        final Set<Item> inProgress = new HashSet<>();
        /** Working inventory for sub-recipe attempts; reset from the caller's map before each one. */
        final WorkMap work;

        SubCraftCtx(CraftModel model, int gridSize, Item rootOutput) {
            this.model = model;
            this.gridSize = gridSize;
            this.graph = model.graph();
            this.flat = graph != null ? graph.flat() : null;
            this.resolveCtx = ResolveContext.of(model, gridSize);
            this.rootOutput = rootOutput;
            this.work = new WorkMap(flat != null ? Math.max(16, flat.n()) : 16);
        }
    }

    private static ItemStack findCraftableForSlot(SlotDisplay slot, SubCraftCtx ctx, InventorySnapshot snap) {
        TagIndex tags = ctx.model.tagIndex();
        // Inventory copy for the sub-craft attempts, made on first use. A failed attempt
        // leaves its map untouched, so one copy serves every candidate.
        Map<Item, Integer> scratch = null;
        if (slot instanceof SlotDisplay.TagSlotDisplay d) {
            TagKey<Item> tag = RecipeDisplays.getSlotTag(d);
            Set<Item> invMatches = tags.inventoryTagMembers(tag);
            if (invMatches != null && !invMatches.isEmpty()) {
                // Show the member the craft would actually draw first: the most
                // abundant pool member (issue #8), not an arbitrary set-order pick.
                Item best = null;
                int bestCount = 0, bestId = Integer.MAX_VALUE;
                for (Item item : invMatches) {
                    int count = snap.inventory().getOrDefault(item, 0);
                    if (count <= 0) continue;
                    int id = BuiltInRegistries.ITEM.getId(item);
                    if (count > bestCount || (count == bestCount && id < bestId)) {
                        best = item;
                        bestCount = count;
                        bestId = id;
                    }
                }
                if (best != null) return new ItemStack(best);
            }
            List<Item> craftable = tags.craftableMembers(tag);
            if (craftable != null) {
                for (Item item : craftable) {
                    if (scratch == null) scratch = sizedCopy(snap.inventory());
                    if (tryConsumeSubCraft(item, scratch, ctx)) return new ItemStack(item);
                }
            }
        } else if (slot instanceof SlotDisplay.Composite d) {
            ItemStack fallback = ItemStack.EMPTY;
            for (SlotDisplay sub : d.contents()) {
                ItemStack r = RecipeDisplays.resolveSlot(sub, snap.inventory(), tags, false);
                if (r.isEmpty()) continue;
                if (fallback.isEmpty()) fallback = r;
                if (snap.inventory().getOrDefault(r.getItem(), 0) > 0) return r;
                if (scratch == null) scratch = sizedCopy(snap.inventory());
                if (tryConsumeSubCraft(r.getItem(), scratch, ctx)) return r;
            }
            if (!fallback.isEmpty()) return fallback;
        } else if (slot instanceof SlotDisplay.WithRemainder d) {
            return findCraftableForSlot(d.input(), ctx, snap);
        }
        return null;
    }

    /** Sized copy of an inventory map (avoids rehash growth on the overlay path). */
    private static HashMap<Item, Integer> sizedCopy(Map<Item, Integer> inventory) {
        HashMap<Item, Integer> copy = new HashMap<>((int) (inventory.size() / 0.75f) + 2);
        copy.putAll(inventory);
        return copy;
    }

    // --- Craftability / container tints (plan §5.8 G3/G4) ---

    private static void computeGridCraftability(IngredientGrid grid, CraftModel model, int gridSize,
                                                InventorySnapshot snap, Set<Item> containerAvailable) {
        Map<Item, Integer> remaining = sizedCopy(snap.inventory());
        boolean hasContainer = !containerAvailable.isEmpty();
        SubCraftCtx ctx = new SubCraftCtx(model, gridSize, grid.recipeOutput());
        RecipeIndex index = model.recipeIndex();
        TagIndex tags = model.tagIndex();

        // Reset the shown items and both tints per pass: a previous refresh may have
        // swapped a tile's item or set either flag (see the steps below).
        for (int i = 0; i < 9; i++) {
            grid.items[i] = grid.baseItems[i];
            grid.craftable[i] = false;
            grid.inContainer[i] = false;
            if (grid.items[i].isEmpty()) grid.craftable[i] = true;
        }

        // Issue #8: evaluate tiles in the planner's resolve order — least-flexible
        // (type-specific) ingredients first, tag-flexible ones last. Grid display order
        // is unchanged; only the consumption order moves, so the tints and the click
        // gate agree with the plan the crafter will actually execute (a flexible tile
        // must not report green by eating planks the specific tile still needs, nor
        // red because the old order already spent them elsewhere).
        int[] rank = new int[9];
        int[] order = new int[9];
        int n = 0;
        for (int i = 0; i < 9; i++) {
            if (grid.items[i].isEmpty()) continue;
            rank[i] = grid.slots[i] != null ? index.slotRank(grid.slots[i]) : 0;
            order[n++] = i;
        }
        for (int k = 1; k < n; k++) {          // stable insertion sort by rank
            int idx = order[k], r = rank[idx];
            int j = k - 1;
            while (j >= 0 && rank[order[j]] > r) {
                order[j + 1] = order[j];
                j--;
            }
            order[j + 1] = idx;
        }

        for (int k = 0; k < n; k++) {
            int i = order[k];
            Item base = grid.baseItems[i].getItem();
            SlotDisplay slot = grid.slots[i];
            // 1. In stock: the tile's item, else the best-stocked other option of its slot.
            Item stocked = remaining.getOrDefault(base, 0) >= 1 ? base : slotOptionInStock(slot, remaining, tags);
            if (stocked != null) {
                remaining.merge(stocked, -1, Integer::sum);
                grid.craftable[i] = true;
                show(grid, i, stocked);
                continue;
            }
            // 2. Sub-craft before the container probe: a tile producible from the
            // physical inventory is craftable even when a copy of the same item
            // also sits in a container — the craft never touches container
            // contents, so it must not read as container-only (a false purple
            // would also tint a tile the planner can craft).
            if (tryConsumeSubCraft(base, remaining, ctx)) {
                grid.craftable[i] = true;
                continue;
            }
            Item crafted = subCraftSlotOption(slot, base, remaining, ctx, tags);
            if (crafted != null) {
                grid.craftable[i] = true;
                show(grid, i, crafted);
                continue;
            }
            // 3. Container-only → purple: the item (or a tag sibling) is physically
            // inside a container, or craftable exclusively from container
            // contents (a containerCraftable recipe's output).
            if (hasContainer) {
                Item found = findInSet(slot, base, containerAvailable, tags);
                if (found != null) {
                    grid.inContainer[i] = true;
                    show(grid, i, found);
                }
            }
        }
    }

    /**
     * Tests if {@code item} can be sub-crafted from {@code available}; if so, deducts consumed
     * resources. {@code ctx.rootOutput} (the viewed recipe's output) is passed as the resolver's
     * root output, so sub-chains that loop back to the recipe's own output are rejected — the
     * same guard the planner applies. Without it, an iron-block recipe whose ingredient grid
     * resolves ingots via the iron-block uncrafting recipe shows every tile as craftable.
     */
    private static boolean tryConsumeSubCraft(Item item, Map<Item, Integer> available, SubCraftCtx ctx) {
        List<RecipeDisplayEntry> subs = ctx.model.recipeIndex().get(item);
        if (subs == null || ctx.graph == null || ctx.flat == null) return false;
        for (RecipeDisplayEntry sub : subs) {
            if (!RecipeDisplays.fitsInGrid(sub.display(), ctx.gridSize)) continue;
            // Mirror of the resolver's trySubCraft guard, applied to the candidate itself:
            // a recipe that strictly requires the viewed recipe's output would loop
            // (iron ingot <- iron block while crafting an iron block), and the main
            // craft view already rejects it — the variant grid must agree.
            if (ctx.rootOutput != null
                    && ctx.model.recipeIndex().requiredItems(sub).contains(ctx.rootOutput)) continue;
            ctx.work.resetTo(available, ctx.graph);
            ctx.inProgress.clear();
            if (ctx.resolveCtx.resolve(sub, ctx.work, null, ctx.inProgress, 0, ctx.rootOutput)) {
                // Write the journal-backed changes back into the plain map. Only ids in
                // the present list can differ: untouched ids read 0 in the work map only
                // if they were never filled — but fill covers exactly the map's keys, so
                // any id outside present has count 0 == map default. Equivalent to the
                // old full 0..n scan at a fraction of the iterations.
                GraphFlatData f = ctx.flat;
                for (int k = 0; k < ctx.work.presentSize(); k++) {
                    int id = ctx.work.presentIdAt(k);
                    int c = ctx.work.get(id);
                    Item it = f.idToItem()[id];
                    if (c != available.getOrDefault(it, 0)) available.put(it, c);
                }
                // Same live-inventory basis as the old getOutputCount(display, tags) call,
                // via the context memo instead of a fresh slot resolution per success.
                int outputCount = ctx.resolveCtx.outCount(sub);
                if (outputCount > 1) available.merge(item, outputCount - 1, Integer::sum);
                return true;
            }
        }
        return false;
    }

    /** Shows {@code item} in tile {@code i}, unless the tile already shows it. */
    private static void show(IngredientGrid grid, int i, Item item) {
        if (grid.items[i].getItem() != item) grid.items[i] = new ItemStack(item);
    }

    /**
     * The option of {@code slot} with the most copies in {@code remaining} (ties: lowest
     * registry id), or null when none is in stock.
     */
    private static Item slotOptionInStock(SlotDisplay slot, Map<Item, Integer> remaining, TagIndex tags) {
        Item best = null;
        if (slot instanceof SlotDisplay.TagSlotDisplay d) {
            TagKey<Item> tag = RecipeDisplays.getSlotTag(d);
            Set<Item> inv = tags.inventoryTagMembers(tag);
            if (inv != null) {
                for (Item item : inv) best = betterInStock(best, item, remaining);
            }
            // Craftable members cover sub-crafted leftovers.
            List<Item> craftable = tags.craftableMembers(tag);
            if (craftable != null) {
                for (Item item : craftable) best = betterInStock(best, item, remaining);
            }
        } else if (slot instanceof SlotDisplay.Composite d) {
            for (SlotDisplay sub : d.contents()) {
                best = betterInStock(best, slotOptionInStock(sub, remaining, tags), remaining);
            }
        } else if (slot instanceof SlotDisplay.WithRemainder d) {
            best = slotOptionInStock(d.input(), remaining, tags);
        } else if (slot instanceof SlotDisplay.ItemSlotDisplay d) {
            best = betterInStock(null, d.item().value(), remaining);
        } else if (slot instanceof SlotDisplay.ItemStackSlotDisplay d) {
            best = betterInStock(null, d.stack().item().value(), remaining);
        }
        return best;
    }

    /** The better of {@code best} and {@code candidate}: more copies in {@code remaining}, then lower registry id. */
    private static Item betterInStock(Item best, Item candidate, Map<Item, Integer> remaining) {
        if (candidate == null) return best;
        int count = remaining.getOrDefault(candidate, 0);
        if (count <= 0) return best;
        if (best == null) return candidate;
        int bestCount = remaining.getOrDefault(best, 0);
        if (count != bestCount) return count > bestCount ? candidate : best;
        return BuiltInRegistries.ITEM.getId(candidate) < BuiltInRegistries.ITEM.getId(best) ? candidate : best;
    }

    /**
     * Sub-crafts the first option of {@code slot} other than {@code alreadyTried} that succeeds,
     * consuming its inputs from {@code remaining}. Returns that option, or null.
     */
    private static Item subCraftSlotOption(SlotDisplay slot, Item alreadyTried, Map<Item, Integer> remaining,
                                           SubCraftCtx ctx, TagIndex tags) {
        if (slot instanceof SlotDisplay.TagSlotDisplay d) {
            List<Item> craftable = tags.craftableMembers(RecipeDisplays.getSlotTag(d));
            if (craftable != null) {
                for (Item item : craftable) {
                    if (item != alreadyTried && tryConsumeSubCraft(item, remaining, ctx)) return item;
                }
            }
        } else if (slot instanceof SlotDisplay.Composite d) {
            for (SlotDisplay sub : d.contents()) {
                Item item = subCraftSlotOption(sub, alreadyTried, remaining, ctx, tags);
                if (item != null) return item;
            }
        } else if (slot instanceof SlotDisplay.WithRemainder d) {
            return subCraftSlotOption(d.input(), alreadyTried, remaining, ctx, tags);
        } else if (slot instanceof SlotDisplay.ItemSlotDisplay d) {
            Item item = d.item().value();
            if (item != alreadyTried && tryConsumeSubCraft(item, remaining, ctx)) return item;
        } else if (slot instanceof SlotDisplay.ItemStackSlotDisplay d) {
            Item item = d.stack().item().value();
            if (item != alreadyTried && tryConsumeSubCraft(item, remaining, ctx)) return item;
        }
        return null;
    }

    private static Item findInSet(SlotDisplay slot, Item resolved, Set<Item> items, TagIndex tags) {
        if (items.contains(resolved)) return resolved;
        if (slot instanceof SlotDisplay.TagSlotDisplay d) {
            Set<Item> contMatches = tags.containerTagMembers(RecipeDisplays.getSlotTag(d));
            if (contMatches != null) {
                for (Item item : contMatches) if (items.contains(item)) return item;
            }
            List<Item> members = tags.members(RecipeDisplays.getSlotTag(d));
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
