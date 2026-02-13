package com.clientcraftmk4;

import com.clientcraftmk4.mixin.RecipeResultCollectionAccessor;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.CraftingScreen;
import net.minecraft.client.gui.screen.recipebook.RecipeResultCollection;
import net.minecraft.client.recipebook.ClientRecipeBook;
import net.minecraft.client.recipebook.RecipeBookType;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.recipe.NetworkRecipeId;
import net.minecraft.recipe.RecipeDisplayEntry;
import net.minecraft.recipe.display.RecipeDisplay;
import net.minecraft.recipe.display.ShapedCraftingRecipeDisplay;
import net.minecraft.recipe.display.ShapelessCraftingRecipeDisplay;
import net.minecraft.recipe.display.SlotDisplay;
import net.minecraft.registry.RegistryKeys;

import java.util.*;

public class RecipeResolver {
    private static final int MAX_DEPTH = 10;
    private static final int MAX_REPEATS = 999;

    private static List<RecipeResultCollection> cachedResults = List.of();
    private static long lastCacheKey = 0;

    private static Map<Item, List<RecipeDisplayEntry>> recipesByOutput = Map.of();
    private static int currentGridSize = 3;
    private static Map<NetworkRecipeId, Integer> craftCounts = Map.of();
    private static Set<RecipeResultCollection> autoCraftCollections = Set.of();
    private static Map<Item, Integer> cachedInventory = Map.of();

    private static IngredientGrid activeIngredientGrid = null;

    // --- IngredientGrid ---

    public static class IngredientGrid {
        private final ItemStack[] items = new ItemStack[9];
        private final boolean[] craftable = new boolean[9];

        public ItemStack get(int index) { return items[index]; }
        public boolean hasCraftable(int index) { return craftable[index]; }
    }

    public static IngredientGrid getActiveIngredientGrid() { return activeIngredientGrid; }
    public static void clearActiveIngredientGrid() { activeIngredientGrid = null; }

    // --- Shared helpers ---

    private static int getGridSize() {
        MinecraftClient client = MinecraftClient.getInstance();
        return (client.currentScreen instanceof CraftingScreen) ? 3 : 2;
    }

    private static void prepareContext() {
        currentGridSize = getGridSize();
        ensureIndex();
        cachedInventory = snapshotInventory();
    }

    private static Map<Item, List<RecipeDisplayEntry>> buildRecipeIndex(List<RecipeResultCollection> allCrafting) {
        Map<Item, List<RecipeDisplayEntry>> index = new HashMap<>();
        for (RecipeResultCollection coll : allCrafting) {
            for (RecipeDisplayEntry entry : coll.getAllRecipes()) {
                Item out = getOutputItem(entry.display());
                if (out != null) index.computeIfAbsent(out, k -> new ArrayList<>()).add(entry);
            }
        }
        return index;
    }

    /**
     * Resolves a slot, returning null if empty or unresolvable.
     */
    private static ItemStack resolveNonEmpty(SlotDisplay slot) {
        if (slot instanceof SlotDisplay.EmptySlotDisplay) return null;
        ItemStack resolved = resolveSlot(slot);
        return (resolved != null && !resolved.isEmpty()) ? resolved : null;
    }

    private static ItemStack resolveNonEmpty(SlotDisplay slot, Map<Item, Integer> inventory) {
        if (slot instanceof SlotDisplay.EmptySlotDisplay) return null;
        ItemStack resolved = resolveSlot(slot, inventory);
        return (resolved != null && !resolved.isEmpty()) ? resolved : null;
    }

    // --- Public API ---

    public static List<RecipeResultCollection> resolveForTab(ClientRecipeBook recipeBook) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return List.of();

        int gridSize = getGridSize();
        long cacheKey = computeCacheKey(gridSize);
        if (cacheKey == lastCacheKey && !cachedResults.isEmpty()) return cachedResults;
        lastCacheKey = cacheKey;

        List<RecipeResultCollection> allCrafting = recipeBook.getResultsForCategory(RecipeBookType.CRAFTING);
        if (allCrafting.isEmpty()) return List.of();

        recipesByOutput = buildRecipeIndex(allCrafting);
        currentGridSize = gridSize;

        Map<Item, Integer> inventory = snapshotInventory();
        cachedInventory = inventory;
        List<RecipeResultCollection> result = new ArrayList<>();
        Map<NetworkRecipeId, Integer> counts = new HashMap<>();

        for (RecipeResultCollection coll : allCrafting) {
            List<RecipeDisplayEntry> allEntries = new ArrayList<>();
            List<RecipeDisplayEntry> craftable = new ArrayList<>();

            for (RecipeDisplayEntry entry : coll.getAllRecipes()) {
                if (!fitsInGrid(entry.display(), gridSize)) continue;
                if (hasCircularDependency(entry)) continue;
                allEntries.add(entry);

                if (resolve(entry, new HashMap<>(inventory), null, new HashSet<>(), 0)) {
                    craftable.add(entry);
                    int repeats = countRepeats(entry, inventory);
                    counts.put(entry.id(), repeats * getOutputCount(entry.display()));
                }
            }

            if (!allEntries.isEmpty()) {
                RecipeResultCollection nc = new RecipeResultCollection(allEntries);
                RecipeResultCollectionAccessor acc = (RecipeResultCollectionAccessor) nc;
                for (RecipeDisplayEntry e : allEntries) acc.getDisplayableRecipes().add(e.id());
                for (RecipeDisplayEntry e : craftable) acc.getCraftableRecipes().add(e.id());
                result.add(nc);
            }
        }

        craftCounts = counts;
        autoCraftCollections = new HashSet<>(result);
        cachedResults = result;
        return result;
    }

    public static List<NetworkRecipeId> buildCraftSequence(RecipeDisplayEntry target) {
        if (MinecraftClient.getInstance().player == null) return null;
        prepareContext();
        List<NetworkRecipeId> steps = new ArrayList<>();
        return resolve(target, new HashMap<>(cachedInventory), steps, new HashSet<>(), 0) ? steps : null;
    }

    /**
     * Builds separate craft cycles for each repeat, each resolved against
     * the simulated inventory state left by the previous cycle.
     */
    public static List<List<NetworkRecipeId>> buildAllCraftCycles(RecipeDisplayEntry target, int repeats) {
        if (MinecraftClient.getInstance().player == null) return null;
        prepareContext();
        Map<Item, Integer> available = new HashMap<>(cachedInventory);
        List<List<NetworkRecipeId>> cycles = new ArrayList<>();

        for (int r = 0; r < repeats; r++) {
            List<NetworkRecipeId> steps = new ArrayList<>();
            if (!resolve(target, available, steps, new HashSet<>(), 0)) break;
            cycles.add(steps);
        }
        return cycles.isEmpty() ? null : cycles;
    }

    public static int countMaxRepeats(RecipeDisplayEntry target) {
        if (MinecraftClient.getInstance().player == null) return 0;
        prepareContext();
        return countRepeats(target, cachedInventory);
    }

    public static ItemStack resolveResult(RecipeDisplay display) {
        ItemStack out = resolveSlot(display.result());
        return (out != null && !out.isEmpty()) ? out : ItemStack.EMPTY;
    }

    public static int getCraftCount(NetworkRecipeId id) {
        return craftCounts.getOrDefault(id, 0);
    }

    public static boolean isAutoCraftCollection(RecipeResultCollection collection) {
        return autoCraftCollections.contains(collection);
    }

    /**
     * Builds the ingredient grid for a recipe and stores it for the render mixin.
     * Returns a fake RecipeResultCollection with 9 entries so vanilla sizes the widget as 3x3.
     */
    public static RecipeResultCollection buildIngredientCollection(RecipeDisplayEntry originalEntry) {
        RecipeDisplay display = originalEntry.display();
        List<SlotDisplay> slots = getSlots(display);
        if (slots == null || slots.isEmpty()) return null;

        IngredientGrid grid = new IngredientGrid();
        Arrays.fill(grid.items, ItemStack.EMPTY);
        fillGrid(grid, display, slots);
        computeGridCraftability(grid);

        activeIngredientGrid = grid;
        return buildFakeCollection(grid, originalEntry);
    }

    public static Map<Item, Integer> snapshotInventory() {
        MinecraftClient client = MinecraftClient.getInstance();
        Map<Item, Integer> snapshot = new HashMap<>();
        if (client.player == null) return snapshot;
        var inv = client.player.getInventory();
        for (int i = 0; i < inv.size(); i++) {
            ItemStack stack = inv.getStack(i);
            if (!stack.isEmpty()) snapshot.merge(stack.getItem(), stack.getCount(), Integer::sum);
        }
        return snapshot;
    }

    public static void clearCache() {
        cachedResults = List.of();
        recipesByOutput = Map.of();
        craftCounts = Map.of();
        autoCraftCollections = Set.of();
        cachedInventory = Map.of();
        lastCacheKey = 0;
        activeIngredientGrid = null;
    }

    // --- Resolution engine ---

    private static boolean resolve(
            RecipeDisplayEntry entry, Map<Item, Integer> available,
            List<NetworkRecipeId> stepsOut, Set<Item> inProgress, int depth) {
        return resolve(entry, available, stepsOut, inProgress, depth, null);
    }

    private static boolean resolve(
            RecipeDisplayEntry entry, Map<Item, Integer> available,
            List<NetworkRecipeId> stepsOut, Set<Item> inProgress, int depth,
            Item rootOutput) {
        if (depth > MAX_DEPTH) return false;

        List<SlotDisplay> slots = getSlots(entry.display());
        if (slots == null || slots.isEmpty()) return false;

        Item outputItem = getOutputItem(entry.display());
        if (outputItem != null && !inProgress.add(outputItem)) return false;

        if (rootOutput == null) rootOutput = outputItem;

        Map<Item, Integer> working = new HashMap<>(available);
        List<NetworkRecipeId> workingSteps = stepsOut != null ? new ArrayList<>() : null;

        for (SlotDisplay slot : slots) {
            ItemStack resolved = resolveNonEmpty(slot, working);
            if (resolved == null) {
                if (slot instanceof SlotDisplay.EmptySlotDisplay) continue;
                if (outputItem != null) inProgress.remove(outputItem);
                return false;
            }

            Item item = resolved.getItem();
            int have = working.getOrDefault(item, 0);

            if (have >= 1) {
                working.put(item, have - 1);
                continue;
            }

            if (!trySubCraft(item, working, workingSteps, inProgress, depth, rootOutput)) {
                if (outputItem != null) inProgress.remove(outputItem);
                return false;
            }
        }

        available.clear();
        available.putAll(working);
        if (stepsOut != null) {
            stepsOut.addAll(workingSteps);
            stepsOut.add(entry.id());
        }
        if (outputItem != null) inProgress.remove(outputItem);
        return true;
    }

    private static boolean trySubCraft(
            Item item, Map<Item, Integer> working, List<NetworkRecipeId> workingSteps,
            Set<Item> inProgress, int depth, Item rootOutput) {
        for (RecipeDisplayEntry sub : recipesByOutput.getOrDefault(item, List.of())) {
            if (!fitsInGrid(sub.display(), currentGridSize)) continue;
            int subOutput = getOutputCount(sub.display());
            if (subOutput <= 0) continue;
            if (rootOutput != null && subCraftConsumesItem(sub, rootOutput)) continue;

            Map<Item, Integer> temp = new HashMap<>(working);
            List<NetworkRecipeId> tempSteps = workingSteps != null ? new ArrayList<>() : null;

            if (resolve(sub, temp, tempSteps, new HashSet<>(inProgress), depth + 1, rootOutput)) {
                working.clear();
                working.putAll(temp);
                working.merge(item, subOutput - 1, Integer::sum);
                if (workingSteps != null) workingSteps.addAll(tempSteps);
                return true;
            }
        }
        return false;
    }

    // --- Circular dependency detection ---

    /**
     * Only filters recipes where the output is directly one of its own ingredients.
     * All other circular loops (reversible recipes, multi-step chains) are handled
     * by the rootOutput guard in resolve() and by not feeding output back in countRepeats.
     */
    private static boolean hasCircularDependency(RecipeDisplayEntry entry) {
        Item outputItem = getOutputItem(entry.display());
        if (outputItem == null) return false;

        List<SlotDisplay> slots = getSlots(entry.display());
        if (slots == null) return false;

        for (SlotDisplay slot : slots) {
            ItemStack resolved = resolveNonEmpty(slot);
            if (resolved != null && resolved.getItem().equals(outputItem)) return true;
        }
        return false;
    }

    private static boolean subCraftConsumesItem(RecipeDisplayEntry recipe, Item item) {
        List<SlotDisplay> slots = getSlots(recipe.display());
        if (slots == null) return false;
        for (SlotDisplay slot : slots) {
            ItemStack resolved = resolveNonEmpty(slot);
            if (resolved != null && resolved.getItem().equals(item)) return true;
        }
        return false;
    }

    // --- Recipe display helpers ---

    private static List<SlotDisplay> getSlots(RecipeDisplay display) {
        if (display instanceof ShapedCraftingRecipeDisplay s) return s.ingredients();
        if (display instanceof ShapelessCraftingRecipeDisplay s) return s.ingredients();
        return null;
    }

    private static boolean fitsInGrid(RecipeDisplay display, int gridSize) {
        if (display instanceof ShapedCraftingRecipeDisplay s) {
            return s.width() <= gridSize && s.height() <= gridSize;
        } else if (display instanceof ShapelessCraftingRecipeDisplay s) {
            int count = 0;
            for (SlotDisplay d : s.ingredients()) {
                if (!(d instanceof SlotDisplay.EmptySlotDisplay)) count++;
            }
            return count <= gridSize * gridSize;
        }
        return false;
    }

    private static Item getOutputItem(RecipeDisplay display) {
        ItemStack out = resolveSlot(display.result());
        return (out != null && !out.isEmpty()) ? out.getItem() : null;
    }

    private static int getOutputCount(RecipeDisplay display) {
        ItemStack out = resolveSlot(display.result());
        return (out != null && !out.isEmpty()) ? out.getCount() : 0;
    }

    // --- Slot resolution ---

    private static ItemStack resolveSlot(SlotDisplay display) {
        return resolveSlot(display, cachedInventory);
    }

    private static ItemStack resolveSlot(SlotDisplay display, Map<Item, Integer> inventory) {
        if (display instanceof SlotDisplay.ItemSlotDisplay d) return new ItemStack(d.item());
        if (display instanceof SlotDisplay.StackSlotDisplay d) return d.stack();
        if (display instanceof SlotDisplay.TagSlotDisplay d) return resolveTag(d, inventory);
        if (display instanceof SlotDisplay.WithRemainderSlotDisplay d) return resolveSlot(d.input(), inventory);
        if (display instanceof SlotDisplay.CompositeSlotDisplay d) {
            ItemStack fallback = ItemStack.EMPTY;
            for (SlotDisplay sub : d.contents()) {
                ItemStack r = resolveSlot(sub, inventory);
                if (r != null && !r.isEmpty()) {
                    if (inventory.getOrDefault(r.getItem(), 0) > 0) return r;
                    if (fallback.isEmpty()) fallback = r;
                }
            }
            return fallback;
        }
        return ItemStack.EMPTY;
    }

    private static ItemStack resolveTag(SlotDisplay.TagSlotDisplay tagDisplay, Map<Item, Integer> inventory) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null) return ItemStack.EMPTY;
        var regOpt = client.world.getRegistryManager().getOptional(RegistryKeys.ITEM);
        if (regOpt.isEmpty()) return ItemStack.EMPTY;
        var entriesOpt = regOpt.get().getOptional(tagDisplay.tag());
        if (entriesOpt.isEmpty()) return ItemStack.EMPTY;

        Item fallback = null;
        for (var entry : entriesOpt.get()) {
            Item item = entry.value();
            if (fallback == null) fallback = item;
            if (inventory.getOrDefault(item, 0) > 0) return new ItemStack(item);
        }
        return fallback != null ? new ItemStack(fallback) : ItemStack.EMPTY;
    }

    // --- Repeat counting ---

    private static int countRepeats(RecipeDisplayEntry target, Map<Item, Integer> inventory) {
        Map<Item, Integer> available = new HashMap<>(inventory);
        int count = 0;
        while (count < MAX_REPEATS) {
            if (!resolve(target, available, null, new HashSet<>(), 0)) break;
            count++;
        }
        return count;
    }

    // --- Ingredient grid building ---

    private static void fillGrid(IngredientGrid grid, RecipeDisplay display, List<SlotDisplay> slots) {
        if (display instanceof ShapedCraftingRecipeDisplay shaped) {
            int w = shaped.width(), h = shaped.height();
            for (int row = 0; row < h; row++) {
                for (int col = 0; col < w; col++) {
                    int srcIdx = row * w + col;
                    if (srcIdx < slots.size()) {
                        ItemStack resolved = resolveNonEmpty(slots.get(srcIdx));
                        if (resolved != null) grid.items[row * 3 + col] = resolved;
                    }
                }
            }
        } else {
            int idx = 0;
            for (SlotDisplay slot : slots) {
                if (idx >= 9) break;
                ItemStack resolved = resolveNonEmpty(slot);
                if (resolved != null) {
                    grid.items[idx] = resolved;
                    idx++;
                } else if (!(slot instanceof SlotDisplay.EmptySlotDisplay)) {
                    idx++;
                }
            }
        }
    }

    private static void computeGridCraftability(IngredientGrid grid) {
        Map<Item, Integer> remaining = new HashMap<>(snapshotInventory());
        ensureIndex();
        for (int i = 0; i < 9; i++) {
            ItemStack stack = grid.items[i];
            if (stack.isEmpty()) {
                grid.craftable[i] = true;
                continue;
            }
            int have = remaining.getOrDefault(stack.getItem(), 0);
            if (have >= 1) {
                remaining.put(stack.getItem(), have - 1);
                grid.craftable[i] = true;
            } else {
                for (RecipeDisplayEntry sub : recipesByOutput.getOrDefault(stack.getItem(), List.of())) {
                    if (resolve(sub, new HashMap<>(remaining), null, new HashSet<>(), 0)) {
                        grid.craftable[i] = true;
                        break;
                    }
                }
            }
        }
    }

    private static RecipeResultCollection buildFakeCollection(IngredientGrid grid, RecipeDisplayEntry originalEntry) {
        List<RecipeDisplayEntry> entries = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            SlotDisplay ingredientSlot = grid.items[i].isEmpty()
                    ? SlotDisplay.EmptySlotDisplay.INSTANCE
                    : new SlotDisplay.StackSlotDisplay(grid.items[i]);
            ShapelessCraftingRecipeDisplay fakeDisplay = new ShapelessCraftingRecipeDisplay(
                    List.of(ingredientSlot), ingredientSlot, SlotDisplay.EmptySlotDisplay.INSTANCE);
            entries.add(new RecipeDisplayEntry(
                    new NetworkRecipeId(-(i + 1)), fakeDisplay,
                    OptionalInt.empty(), originalEntry.category(), Optional.empty()));
        }
        RecipeResultCollection collection = new RecipeResultCollection(entries);
        RecipeResultCollectionAccessor acc = (RecipeResultCollectionAccessor) collection;
        for (RecipeDisplayEntry e : entries) {
            acc.getDisplayableRecipes().add(e.id());
            acc.getCraftableRecipes().add(e.id());
        }
        return collection;
    }

    // --- Cache management ---

    private static long computeCacheKey(int gridSize) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return 0;
        long hash = gridSize;
        var inv = client.player.getInventory();
        for (int i = 0; i < inv.size(); i++) {
            ItemStack stack = inv.getStack(i);
            if (!stack.isEmpty()) {
                hash = hash * 31 + stack.getItem().hashCode();
                hash = hash * 31 + stack.getCount();
            }
        }
        return hash;
    }

    private static void ensureIndex() {
        if (!recipesByOutput.isEmpty()) return;
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return;
        recipesByOutput = buildRecipeIndex(
                client.player.getRecipeBook().getResultsForCategory(RecipeBookType.CRAFTING));
    }
}
