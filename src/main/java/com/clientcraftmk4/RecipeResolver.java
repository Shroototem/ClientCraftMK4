package com.clientcraftmk4;

import com.clientcraftmk4.mixin.RecipeResultCollectionAccessor;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.CraftingScreen;
import net.minecraft.client.gui.screen.recipebook.RecipeResultCollection;
import net.minecraft.client.recipebook.ClientRecipeBook;
import net.minecraft.client.recipebook.RecipeBookType;
import net.minecraft.component.DataComponentTypes;
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
    private static boolean recipeIndexDirty = true;
    private static int currentGridSize = 3;
    private static Map<NetworkRecipeId, Integer> craftCounts = Map.of();
    private static Set<RecipeResultCollection> autoCraftCollections = Set.of();

    private static Map<Item, Integer> cachedInventory = Map.of();
    private static long inventorySnapshotTick = -1;

    private static Map<Item, String> lowerCaseNameCache = new HashMap<>();
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
        return (MinecraftClient.getInstance().currentScreen instanceof CraftingScreen) ? 3 : 2;
    }

    private static Map<Item, Integer> getOrSnapshotInventory() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null || client.player == null) return Map.of();
        long tick = client.world.getTime();
        if (tick != inventorySnapshotTick) {
            Map<Item, Integer> snapshot = new HashMap<>();
            var inv = client.player.getInventory();
            for (int i = 0; i < inv.size(); i++) {
                ItemStack stack = inv.getStack(i);
                if (!stack.isEmpty() && !stack.contains(DataComponentTypes.CUSTOM_NAME))
                    snapshot.merge(stack.getItem(), stack.getCount(), Integer::sum);
            }
            cachedInventory = snapshot;
            inventorySnapshotTick = tick;
        }
        return cachedInventory;
    }

    private static void ensureIndex() {
        if (!recipeIndexDirty && !recipesByOutput.isEmpty()) return;
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return;
        Map<Item, List<RecipeDisplayEntry>> index = new HashMap<>();
        for (RecipeResultCollection coll : client.player.getRecipeBook().getResultsForCategory(RecipeBookType.CRAFTING)) {
            for (RecipeDisplayEntry entry : coll.getAllRecipes()) {
                Item out = getOutputItem(entry.display());
                if (out != null) index.computeIfAbsent(out, k -> new ArrayList<>()).add(entry);
            }
        }
        recipesByOutput = index;
        recipeIndexDirty = false;
    }

    private static void prepareContext() {
        currentGridSize = getGridSize();
        ensureIndex();
        getOrSnapshotInventory();
    }

    // --- Public API ---

    public static List<RecipeResultCollection> resolveForTab(ClientRecipeBook recipeBook) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.world == null) return List.of();

        int gridSize = getGridSize();
        long cacheKey = client.world.getTime() * 7L + gridSize;
        if (cacheKey == lastCacheKey && !cachedResults.isEmpty()) return cachedResults;
        lastCacheKey = cacheKey;

        currentGridSize = gridSize;
        ensureIndex();
        Map<Item, Integer> inventory = getOrSnapshotInventory();
        if (recipesByOutput.isEmpty()) return List.of();

        List<RecipeResultCollection> allCrafting = recipeBook.getResultsForCategory(RecipeBookType.CRAFTING);
        if (allCrafting.isEmpty()) return List.of();

        List<RecipeResultCollection> result = new ArrayList<>();
        Map<NetworkRecipeId, Integer> counts = new HashMap<>();

        for (RecipeResultCollection coll : allCrafting) {
            List<RecipeDisplayEntry> allEntries = new ArrayList<>();
            List<RecipeDisplayEntry> craftable = new ArrayList<>();

            for (RecipeDisplayEntry entry : coll.getAllRecipes()) {
                if (!fitsInGrid(entry.display(), gridSize)) continue;
                if (hasCircularDependency(entry)) continue;
                allEntries.add(entry);

                Map<Item, Integer> simulated = new HashMap<>(inventory);
                if (resolve(entry, simulated, null, new HashSet<>(), 0)) {
                    craftable.add(entry);
                    int repeats = estimateRepeats(entry, inventory, simulated);
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

    /**
     * Unified craft method: verifies, computes repeats, and builds all cycles in one pass.
     */
    public static List<List<NetworkRecipeId>> buildCraftCyclesForMode(RecipeDisplayEntry target, AutoCrafter.Mode mode) {
        if (MinecraftClient.getInstance().player == null) return null;
        prepareContext();

        Map<Item, Integer> available = new HashMap<>(cachedInventory);
        List<NetworkRecipeId> firstSteps = new ArrayList<>();
        if (!resolve(target, available, firstSteps, new HashSet<>(), 0)) return null;

        ItemStack output = resolveResult(target.display());
        int outputCount = output.getCount();

        int maxRepeats = switch (mode) {
            case ONCE -> 1;
            case STACK -> (output.getMaxCount() + outputCount - 1) / outputCount;
            case ALL -> MAX_REPEATS;
        };
        if (maxRepeats <= 0) return null;

        List<List<NetworkRecipeId>> cycles = new ArrayList<>();
        cycles.add(firstSteps);

        for (int r = 1; r < maxRepeats; r++) {
            List<NetworkRecipeId> steps = new ArrayList<>();
            if (!resolve(target, available, steps, new HashSet<>(), 0)) break;
            cycles.add(steps);
        }
        return cycles;
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

    public static String getLowerCaseName(Item item) {
        return lowerCaseNameCache.computeIfAbsent(item,
                i -> new ItemStack(i).getName().getString().toLowerCase(Locale.ROOT));
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

    public static void clearCache() {
        cachedResults = List.of();
        recipesByOutput = Map.of();
        recipeIndexDirty = true;
        craftCounts = Map.of();
        autoCraftCollections = Set.of();
        cachedInventory = Map.of();
        inventorySnapshotTick = -1;
        lastCacheKey = 0;
        activeIngredientGrid = null;
        lowerCaseNameCache.clear();
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

        // Track changes for rollback on failure
        List<Map.Entry<Item, Integer>> savedEntries = new ArrayList<>();
        int stepsStart = stepsOut != null ? stepsOut.size() : 0;

        boolean success = true;
        for (SlotDisplay slot : slots) {
            if (slot instanceof SlotDisplay.EmptySlotDisplay) continue;

            Item item = resolveSlotItem(slot, available);
            if (item == null) { success = false; break; }

            int have = available.getOrDefault(item, 0);
            if (have >= 1) {
                savedEntries.add(Map.entry(item, have));
                available.put(item, have - 1);
                continue;
            }

            if (!trySubCraft(item, available, stepsOut, inProgress, depth, rootOutput)) {
                success = false;
                break;
            }
        }

        if (!success) {
            // Rollback direct consumption changes
            for (Map.Entry<Item, Integer> saved : savedEntries) {
                available.put(saved.getKey(), saved.getValue());
            }
            if (stepsOut != null) {
                while (stepsOut.size() > stepsStart) stepsOut.removeLast();
            }
            if (outputItem != null) inProgress.remove(outputItem);
            return false;
        }

        if (stepsOut != null) stepsOut.add(entry.id());
        if (outputItem != null) inProgress.remove(outputItem);
        return true;
    }

    private static boolean trySubCraft(
            Item item, Map<Item, Integer> working, List<NetworkRecipeId> stepsOut,
            Set<Item> inProgress, int depth, Item rootOutput) {
        for (RecipeDisplayEntry sub : recipesByOutput.getOrDefault(item, List.of())) {
            if (!fitsInGrid(sub.display(), currentGridSize)) continue;
            int subOutput = getOutputCount(sub.display());
            if (subOutput <= 0) continue;
            if (rootOutput != null && subCraftConsumesItem(sub, rootOutput)) continue;

            Map<Item, Integer> temp = new HashMap<>(working);
            List<NetworkRecipeId> tempSteps = stepsOut != null ? new ArrayList<>() : null;

            if (resolve(sub, temp, tempSteps, inProgress, depth + 1, rootOutput)) {
                working.clear();
                working.putAll(temp);
                working.merge(item, subOutput - 1, Integer::sum);
                if (stepsOut != null) stepsOut.addAll(tempSteps);
                return true;
            }
        }
        return false;
    }

    // --- Repeat estimation ---

    /**
     * Estimates max repeats from the net consumption of a single resolve pass.
     * Uses the delta between the original inventory and post-resolve inventory.
     */
    private static int estimateRepeats(RecipeDisplayEntry target, Map<Item, Integer> original, Map<Item, Integer> afterOne) {
        int maxByMath = MAX_REPEATS;
        for (Map.Entry<Item, Integer> e : original.entrySet()) {
            int consumed = e.getValue() - afterOne.getOrDefault(e.getKey(), 0);
            if (consumed > 0) {
                maxByMath = Math.min(maxByMath, e.getValue() / consumed);
            }
        }
        return Math.max(1, maxByMath);
    }

    // --- Circular dependency detection ---

    private static boolean hasCircularDependency(RecipeDisplayEntry entry) {
        Item outputItem = getOutputItem(entry.display());
        if (outputItem == null) return false;

        List<SlotDisplay> slots = getSlots(entry.display());
        if (slots == null) return false;

        for (SlotDisplay slot : slots) {
            Item item = resolveSlotItem(slot, cachedInventory);
            if (item != null && item.equals(outputItem)) return true;
        }
        return false;
    }

    private static boolean subCraftConsumesItem(RecipeDisplayEntry recipe, Item target) {
        List<SlotDisplay> slots = getSlots(recipe.display());
        if (slots == null) return false;
        for (SlotDisplay slot : slots) {
            Item item = resolveSlotItem(slot, cachedInventory);
            if (item != null && item.equals(target)) return true;
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
        return resolveSlotItem(display.result(), cachedInventory);
    }

    private static int getOutputCount(RecipeDisplay display) {
        ItemStack out = resolveSlot(display.result());
        return (out != null && !out.isEmpty()) ? out.getCount() : 0;
    }

    // --- Item-level slot resolution (no ItemStack allocation) ---

    private static Item resolveSlotItem(SlotDisplay display, Map<Item, Integer> inventory) {
        if (display instanceof SlotDisplay.EmptySlotDisplay) return null;
        if (display instanceof SlotDisplay.ItemSlotDisplay d) return d.item().value();
        if (display instanceof SlotDisplay.StackSlotDisplay d) return d.stack().getItem();
        if (display instanceof SlotDisplay.TagSlotDisplay d) return resolveTagItem(d, inventory);
        if (display instanceof SlotDisplay.WithRemainderSlotDisplay d) return resolveSlotItem(d.input(), inventory);
        if (display instanceof SlotDisplay.CompositeSlotDisplay d) {
            Item fallback = null;
            for (SlotDisplay sub : d.contents()) {
                Item item = resolveSlotItem(sub, inventory);
                if (item != null) {
                    if (inventory.getOrDefault(item, 0) > 0) return item;
                    if (fallback == null) fallback = item;
                }
            }
            return fallback;
        }
        return null;
    }

    private static Item resolveTagItem(SlotDisplay.TagSlotDisplay tagDisplay, Map<Item, Integer> inventory) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null) return null;
        var regOpt = client.world.getRegistryManager().getOptional(RegistryKeys.ITEM);
        if (regOpt.isEmpty()) return null;
        var entriesOpt = regOpt.get().getOptional(tagDisplay.tag());
        if (entriesOpt.isEmpty()) return null;

        Item fallback = null;
        for (var entry : entriesOpt.get()) {
            Item item = entry.value();
            if (fallback == null) fallback = item;
            if (inventory.getOrDefault(item, 0) > 0) return item;
        }
        return fallback;
    }

    // --- ItemStack-level slot resolution (for display/rendering only) ---

    private static ItemStack resolveSlot(SlotDisplay display) {
        return resolveSlot(display, cachedInventory);
    }

    private static ItemStack resolveSlot(SlotDisplay display, Map<Item, Integer> inventory) {
        if (display instanceof SlotDisplay.ItemSlotDisplay d) return new ItemStack(d.item());
        if (display instanceof SlotDisplay.StackSlotDisplay d) {
            // Strip custom names/NBT - return clean ItemStack
            return new ItemStack(d.stack().getItem(), d.stack().getCount());
        }
        if (display instanceof SlotDisplay.TagSlotDisplay d) {
            Item item = resolveTagItem(d, inventory);
            return item != null ? new ItemStack(item) : ItemStack.EMPTY;
        }
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

    // --- Ingredient grid building ---

    private static void fillGrid(IngredientGrid grid, RecipeDisplay display, List<SlotDisplay> slots) {
        if (display instanceof ShapedCraftingRecipeDisplay shaped) {
            int w = shaped.width(), h = shaped.height();
            for (int row = 0; row < h; row++) {
                for (int col = 0; col < w; col++) {
                    int srcIdx = row * w + col;
                    if (srcIdx < slots.size()) {
                        ItemStack resolved = resolveSlotStack(slots.get(srcIdx));
                        if (resolved != null) grid.items[row * 3 + col] = resolved;
                    }
                }
            }
        } else {
            int idx = 0;
            for (SlotDisplay slot : slots) {
                if (idx >= 9) break;
                ItemStack resolved = resolveSlotStack(slot);
                if (resolved != null) {
                    grid.items[idx] = resolved;
                    idx++;
                } else if (!(slot instanceof SlotDisplay.EmptySlotDisplay)) {
                    idx++;
                }
            }
        }
    }

    private static ItemStack resolveSlotStack(SlotDisplay slot) {
        if (slot instanceof SlotDisplay.EmptySlotDisplay) return null;
        ItemStack resolved = resolveSlot(slot);
        return (resolved != null && !resolved.isEmpty()) ? resolved : null;
    }

    private static void computeGridCraftability(IngredientGrid grid) {
        Map<Item, Integer> remaining = new HashMap<>(getOrSnapshotInventory());
        ensureIndex();
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
            } else {
                grid.craftable[i] = recipesByOutput.containsKey(item);
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
}
