package com.clientcraftmk4;

import com.clientcraftmk4.mixin.RecipeResultCollectionAccessor;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.CraftingScreen;
import net.minecraft.client.gui.screen.recipebook.RecipeResultCollection;
import net.minecraft.client.recipebook.ClientRecipeBook;
import net.minecraft.client.recipebook.RecipeBookType;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ContainerComponent;
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
    private static Set<NetworkRecipeId> containerCraftable = Set.of();
    private static Set<Item> containerAvailableItems = Set.of();
    private static Map<RecipeResultCollection, Integer> collectionRanks = Map.of();
    private static Set<RecipeResultCollection> autoCraftCollections = Set.of();

    private static Map<Item, Integer> cachedInventory = Map.of();
    private static Map<Item, Integer> cachedContainerInventory = Map.of();
    private static long inventorySnapshotTick = -1;

    private static Map<Item, String> lowerCaseNameCache = new HashMap<>();
    private static IngredientGrid activeIngredientGrid = null;

    // --- IngredientGrid ---

    public static class IngredientGrid {
        private final ItemStack[] items = new ItemStack[9];
        private final SlotDisplay[] slots = new SlotDisplay[9];
        private final boolean[] craftable = new boolean[9];
        private final boolean[] inContainer = new boolean[9];

        public ItemStack get(int index) { return items[index]; }
        public boolean hasCraftable(int index) { return craftable[index]; }
        public boolean isInContainer(int index) { return inContainer[index]; }
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
            Map<Item, Integer> containerSnapshot = new HashMap<>();
            var inv = client.player.getInventory();
            for (int i = 0; i < inv.size(); i++) {
                ItemStack stack = inv.getStack(i);
                if (stack.isEmpty()) continue;

                if (ClientCraftConfig.searchContainers) {
                    ContainerComponent container = stack.get(DataComponentTypes.CONTAINER);
                    if (container != null) {
                        for (ItemStack contained : container.iterateNonEmpty()) {
                            containerSnapshot.merge(contained.getItem(), contained.getCount(), Integer::sum);
                        }
                    }
                }

                if (stack.contains(DataComponentTypes.CUSTOM_NAME)) continue;
                snapshot.merge(stack.getItem(), stack.getCount(), Integer::sum);
            }
            cachedInventory = snapshot;
            cachedContainerInventory = containerSnapshot;
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

    private static Map<Item, Integer> mergeMaps(Map<Item, Integer> a, Map<Item, Integer> b) {
        Map<Item, Integer> m = new HashMap<>(a);
        b.forEach((k, v) -> m.merge(k, v, Integer::sum));
        return m;
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
        Set<NetworkRecipeId> containerSet = new HashSet<>();
        Map<RecipeResultCollection, Integer> ranks = new IdentityHashMap<>();

        boolean checkContainers = ClientCraftConfig.searchContainers && !cachedContainerInventory.isEmpty();
        Map<Item, Integer> combined = checkContainers ? mergeMaps(inventory, cachedContainerInventory) : null;
        Set<Item> containerItemSet = checkContainers ? new HashSet<>(cachedContainerInventory.keySet()) : Set.of();

        for (RecipeResultCollection coll : allCrafting) {
            List<RecipeDisplayEntry> allEntries = new ArrayList<>();
            List<RecipeDisplayEntry> craftable = new ArrayList<>();
            boolean hasDirect = false;
            boolean hasContainer = false;

            for (RecipeDisplayEntry entry : coll.getAllRecipes()) {
                if (!fitsInGrid(entry.display(), gridSize)) continue;
                Item out = getOutputItem(entry.display());
                if (out != null && recipeConsumesItem(entry, out)) continue;
                allEntries.add(entry);

                int repeats = countRepeats(entry, inventory);
                if (repeats > 0) {
                    craftable.add(entry);
                    hasDirect = true;
                    counts.put(entry.id(), repeats * getOutputCount(entry.display()));
                } else if (combined != null) {
                    Map<Item, Integer> temp = new HashMap<>(combined);
                    if (resolve(entry, temp, null, new HashSet<>(), 0, null)) {
                        containerSet.add(entry.id());
                        craftable.add(entry);
                        hasContainer = true;
                        if (out != null) containerItemSet.add(out);
                    }
                }
            }

            if (!allEntries.isEmpty()) {
                RecipeResultCollection nc = new RecipeResultCollection(allEntries);
                RecipeResultCollectionAccessor acc = (RecipeResultCollectionAccessor) nc;
                for (RecipeDisplayEntry e : allEntries) acc.getDisplayableRecipes().add(e.id());
                for (RecipeDisplayEntry e : craftable) acc.getCraftableRecipes().add(e.id());
                ranks.put(nc, hasDirect ? 0 : hasContainer ? 1 : 2);
                result.add(nc);
            }
        }

        craftCounts = counts;
        containerCraftable = containerSet;
        collectionRanks = ranks;
        autoCraftCollections = new HashSet<>(result);
        containerAvailableItems = containerItemSet;

        cachedResults = result;
        return result;
    }

    public static List<List<NetworkRecipeId>> buildCraftCyclesForMode(RecipeDisplayEntry target, AutoCrafter.Mode mode) {
        if (MinecraftClient.getInstance().player == null) return null;
        prepareContext();

        Map<Item, Integer> available = new HashMap<>(cachedInventory);
        List<NetworkRecipeId> firstSteps = new ArrayList<>();
        if (!resolve(target, available, firstSteps, new HashSet<>(), 0, null)) return null;

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
            if (!resolve(target, available, steps, new HashSet<>(), 0, null)) break;
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

    public static boolean isContainerCraftable(NetworkRecipeId id) {
        return containerCraftable.contains(id);
    }

    public static int getCollectionRank(RecipeResultCollection coll) {
        return collectionRanks.getOrDefault(coll, 2);
    }

    public static String getLowerCaseName(Item item) {
        return lowerCaseNameCache.computeIfAbsent(item,
                i -> new ItemStack(i).getName().getString().toLowerCase(Locale.ROOT));
    }

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
        containerCraftable = Set.of();
        containerAvailableItems = Set.of();
        collectionRanks = Map.of();
        autoCraftCollections = Set.of();
        cachedInventory = Map.of();
        cachedContainerInventory = Map.of();
        inventorySnapshotTick = -1;
        lastCacheKey = 0;
        activeIngredientGrid = null;
        lowerCaseNameCache.clear();
    }

    // --- Resolution engine ---

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

        Map<Item, Integer> snapshot = new HashMap<>(available);
        int stepsStart = stepsOut != null ? stepsOut.size() : 0;

        boolean success = true;
        for (SlotDisplay slot : slots) {
            if (slot instanceof SlotDisplay.EmptySlotDisplay) continue;

            ItemStack resolved = resolveSlot(slot, available);
            if (resolved.isEmpty()) { success = false; break; }
            Item item = resolved.getItem();

            int have = available.getOrDefault(item, 0);
            if (have >= 1) {
                available.put(item, have - 1);
                continue;
            }

            if (!trySubCraft(item, available, stepsOut, inProgress, depth, rootOutput)) {
                success = false;
                break;
            }
        }

        if (!success) {
            available.clear();
            available.putAll(snapshot);
            if (stepsOut != null) while (stepsOut.size() > stepsStart) stepsOut.removeLast();
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
        List<RecipeDisplayEntry> subs = recipesByOutput.get(item);
        if (subs == null) return false;
        for (int i = 0, len = subs.size(); i < len; i++) {
            RecipeDisplayEntry sub = subs.get(i);
            if (!fitsInGrid(sub.display(), currentGridSize)) continue;
            int subOutput = getOutputCount(sub.display());
            if (subOutput <= 0) continue;
            if (rootOutput != null && recipeConsumesItem(sub, rootOutput)) continue;

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

    // --- Repeat counting ---

    private static int countRepeats(RecipeDisplayEntry target, Map<Item, Integer> inventory) {
        Map<Item, Integer> sim = new HashMap<>(inventory);
        int count = 0;
        while (count < MAX_REPEATS) {
            if (!resolve(target, sim, null, new HashSet<>(), 0, null)) break;
            count++;
        }
        return count;
    }

    // --- Recipe helpers ---

    private static boolean recipeConsumesItem(RecipeDisplayEntry entry, Item target) {
        List<SlotDisplay> slots = getSlots(entry.display());
        if (slots == null) return false;
        for (SlotDisplay slot : slots) {
            ItemStack resolved = resolveSlot(slot, cachedInventory);
            if (!resolved.isEmpty() && resolved.getItem().equals(target)) return true;
        }
        return false;
    }

    private static List<SlotDisplay> getSlots(RecipeDisplay display) {
        if (display instanceof ShapedCraftingRecipeDisplay s) return s.ingredients();
        if (display instanceof ShapelessCraftingRecipeDisplay s) return s.ingredients();
        return null;
    }

    private static boolean fitsInGrid(RecipeDisplay display, int gridSize) {
        if (display instanceof ShapedCraftingRecipeDisplay s) {
            return s.width() <= gridSize && s.height() <= gridSize;
        } else if (display instanceof ShapelessCraftingRecipeDisplay s) {
            List<SlotDisplay> ingredients = s.ingredients();
            int count = 0;
            for (int i = 0, len = ingredients.size(); i < len; i++) {
                if (!(ingredients.get(i) instanceof SlotDisplay.EmptySlotDisplay)) count++;
            }
            return count <= gridSize * gridSize;
        }
        return false;
    }

    private static Item getOutputItem(RecipeDisplay display) {
        ItemStack out = resolveSlot(display.result());
        return out.isEmpty() ? null : out.getItem();
    }

    private static int getOutputCount(RecipeDisplay display) {
        ItemStack out = resolveSlot(display.result());
        return out.isEmpty() ? 0 : out.getCount();
    }

    // --- Slot resolution ---

    private static ItemStack resolveSlot(SlotDisplay display) {
        return resolveSlot(display, cachedInventory);
    }

    private static ItemStack resolveSlot(SlotDisplay display, Map<Item, Integer> inventory) {
        if (display instanceof SlotDisplay.EmptySlotDisplay) return ItemStack.EMPTY;
        if (display instanceof SlotDisplay.ItemSlotDisplay d) return new ItemStack(d.item());
        if (display instanceof SlotDisplay.StackSlotDisplay d) {
            return new ItemStack(d.stack().getItem(), d.stack().getCount());
        }
        if (display instanceof SlotDisplay.TagSlotDisplay d) {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.world == null) return ItemStack.EMPTY;
            var regOpt = client.world.getRegistryManager().getOptional(RegistryKeys.ITEM);
            if (regOpt.isEmpty()) return ItemStack.EMPTY;
            var entriesOpt = regOpt.get().getOptional(d.tag());
            if (entriesOpt.isEmpty()) return ItemStack.EMPTY;
            Item fallback = null;
            for (var entry : entriesOpt.get()) {
                Item item = entry.value();
                if (fallback == null) fallback = item;
                if (inventory.getOrDefault(item, 0) > 0) return new ItemStack(item);
            }
            return fallback != null ? new ItemStack(fallback) : ItemStack.EMPTY;
        }
        if (display instanceof SlotDisplay.WithRemainderSlotDisplay d) return resolveSlot(d.input(), inventory);
        if (display instanceof SlotDisplay.CompositeSlotDisplay d) {
            ItemStack fallback = ItemStack.EMPTY;
            for (SlotDisplay sub : d.contents()) {
                ItemStack r = resolveSlot(sub, inventory);
                if (!r.isEmpty()) {
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
            for (int row = 0; row < h; row++) for (int col = 0; col < w; col++) {
                int srcIdx = row * w + col;
                if (srcIdx >= slots.size()) continue;
                SlotDisplay slot = slots.get(srcIdx);
                if (slot instanceof SlotDisplay.EmptySlotDisplay) continue;
                ItemStack resolved = resolveGridSlot(slot);
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
                if (slot instanceof SlotDisplay.EmptySlotDisplay) continue;
                ItemStack resolved = resolveGridSlot(slot);
                if (!resolved.isEmpty()) {
                    grid.items[idx] = resolved;
                    grid.slots[idx] = slot;
                }
                idx++;
            }
        }
    }

    private static ItemStack resolveGridSlot(SlotDisplay slot) {
        // First try: item already in inventory
        ItemStack direct = resolveSlot(slot, cachedInventory);
        if (!direct.isEmpty() && cachedInventory.getOrDefault(direct.getItem(), 0) > 0) return direct;

        // Second try: find a member we can actually sub-craft
        if (slot instanceof SlotDisplay.TagSlotDisplay d) {
            return resolveByCraftability(d);
        } else if (slot instanceof SlotDisplay.CompositeSlotDisplay d) {
            ItemStack fallback = ItemStack.EMPTY;
            for (SlotDisplay sub : d.contents()) {
                ItemStack r = resolveSlot(sub, cachedInventory);
                if (r.isEmpty()) continue;
                if (fallback.isEmpty()) fallback = r;
                if (cachedInventory.getOrDefault(r.getItem(), 0) > 0) return r;
                if (canSubCraft(r.getItem(), cachedInventory)) return r;
            }
            return fallback;
        } else if (slot instanceof SlotDisplay.WithRemainderSlotDisplay d) {
            return resolveGridSlot(d.input());
        }

        // Fallback: return whatever resolveSlot gives
        return direct.isEmpty() ? resolveSlot(slot) : direct;
    }

    private static ItemStack resolveByCraftability(SlotDisplay.TagSlotDisplay d) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null) return ItemStack.EMPTY;
        var regOpt = client.world.getRegistryManager().getOptional(RegistryKeys.ITEM);
        if (regOpt.isEmpty()) return ItemStack.EMPTY;
        var entriesOpt = regOpt.get().getOptional(d.tag());
        if (entriesOpt.isEmpty()) return ItemStack.EMPTY;

        Item fallback = null;
        for (var entry : entriesOpt.get()) {
            Item item = entry.value();
            if (fallback == null) fallback = item;
            if (cachedInventory.getOrDefault(item, 0) > 0) return new ItemStack(item);
            if (canSubCraft(item, cachedInventory)) return new ItemStack(item);
        }
        return fallback != null ? new ItemStack(fallback) : ItemStack.EMPTY;
    }

    private static void computeGridCraftability(IngredientGrid grid) {
        Map<Item, Integer> remaining = new HashMap<>(getOrSnapshotInventory());
        ensureIndex();
        boolean hasContainer = !containerAvailableItems.isEmpty();

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
                Item found = findInSet(grid.slots[i], item, containerAvailableItems);
                if (found != null) {
                    grid.inContainer[i] = true;
                    if (found != item) grid.items[i] = new ItemStack(found);
                } else {
                    grid.craftable[i] = canSubCraft(item, remaining);
                }
            } else {
                grid.craftable[i] = canSubCraft(item, remaining);
            }
        }
    }

    private static boolean canSubCraft(Item item, Map<Item, Integer> available) {
        List<RecipeDisplayEntry> subs = recipesByOutput.get(item);
        if (subs == null) return false;
        for (RecipeDisplayEntry sub : subs) {
            if (!fitsInGrid(sub.display(), currentGridSize)) continue;
            Map<Item, Integer> temp = new HashMap<>(available);
            if (resolve(sub, temp, null, new HashSet<>(), 0, null)) return true;
        }
        return false;
    }

    private static Item findInSet(SlotDisplay slot, Item resolved, Set<Item> items) {
        if (items.contains(resolved)) return resolved;
        if (slot instanceof SlotDisplay.TagSlotDisplay tag) {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.world == null) return null;
            var regOpt = client.world.getRegistryManager().getOptional(RegistryKeys.ITEM);
            if (regOpt.isEmpty()) return null;
            var entriesOpt = regOpt.get().getOptional(tag.tag());
            if (entriesOpt.isEmpty()) return null;
            for (var entry : entriesOpt.get()) {
                if (items.contains(entry.value())) return entry.value();
            }
        } else if (slot instanceof SlotDisplay.CompositeSlotDisplay comp) {
            for (SlotDisplay sub : comp.contents()) {
                Item item = findInSet(sub, resolved, items);
                if (item != null) return item;
            }
        } else if (slot instanceof SlotDisplay.WithRemainderSlotDisplay rem) {
            return findInSet(rem.input(), resolved, items);
        }
        return null;
    }

    private static RecipeResultCollection buildFakeCollection(IngredientGrid grid, RecipeDisplayEntry originalEntry) {
        List<RecipeDisplayEntry> entries = new ArrayList<>(9);
        for (int i = 0; i < 9; i++) {
            SlotDisplay ingredientSlot = grid.items[i].isEmpty()
                    ? SlotDisplay.EmptySlotDisplay.INSTANCE
                    : new SlotDisplay.StackSlotDisplay(grid.items[i]);
            entries.add(new RecipeDisplayEntry(
                    new NetworkRecipeId(-(i + 1)),
                    new ShapelessCraftingRecipeDisplay(List.of(ingredientSlot), ingredientSlot, SlotDisplay.EmptySlotDisplay.INSTANCE),
                    OptionalInt.empty(), originalEntry.category(), Optional.empty()));
        }
        RecipeResultCollection collection = new RecipeResultCollection(entries);
        RecipeResultCollectionAccessor acc = (RecipeResultCollectionAccessor) collection;
        for (RecipeDisplayEntry e : entries) { acc.getDisplayableRecipes().add(e.id()); acc.getCraftableRecipes().add(e.id()); }
        return collection;
    }
}
