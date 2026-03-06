package com.clientcraftmk4;

import com.clientcraftmk4.mixin.RecipeResultCollectionAccessor;
import com.clientcraftmk4.tree.*;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.CraftingScreen;
import net.minecraft.client.gui.screen.recipebook.RecipeResultCollection;
import net.minecraft.client.recipebook.ClientRecipeBook;
import net.minecraft.client.recipebook.RecipeBookGroup;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.recipe.Ingredient;
import net.minecraft.recipe.RecipeEntry;
import net.minecraft.recipe.ShapedRecipe;
import net.minecraft.recipe.ShapelessRecipe;
import net.minecraft.registry.DynamicRegistryManager;
import net.minecraft.util.Identifier;
import net.minecraft.util.collection.DefaultedList;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class RecipeResolver {
    private static final Logger LOG = LoggerFactory.getLogger("ClientCraftMK4");
    private static final ExecutorService RESOLVER_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "ClientCraft-Resolver");
        t.setDaemon(true);
        return t;
    });

    // --- State ---
    private static RecipeTree tree = null;
    private static volatile boolean building = false;
    private static final InventoryTracker tracker = new InventoryTracker();

    private static List<RecipeResultCollection> cachedResults = List.of();
    private static Map<Identifier, Integer> craftCounts = Map.of();
    private static Map<Item, Integer> craftCountsByItem = new HashMap<>();
    private static Set<Identifier> containerCraftable = Set.of();
    private static Map<RecipeResultCollection, Integer> collectionRanks = Map.of();
    private static Set<RecipeResultCollection> autoCraftCollections = Set.of();
    private static int lastGridSize = -1;
    private static long lastGeneration = -1;
    private static int lastRecipeCount = -1;

    private static volatile Runnable onResultsPublished = null;
    public static boolean lastTabWasClientCraft = false;

    private static Map<Item, String> lowerCaseNameCache = new HashMap<>();
    private static IngredientGrid activeIngredientGrid = null;

    // --- IngredientGrid ---

    public static class IngredientGrid {
        private final ItemStack[] items = new ItemStack[9];
        private final Ingredient[] slots = new Ingredient[9];
        private final boolean[] craftable = new boolean[9];
        private final boolean[] inContainer = new boolean[9];

        public ItemStack get(int index) { return items[index]; }
        public boolean hasCraftable(int index) { return craftable[index]; }
        public boolean isInContainer(int index) { return inContainer[index]; }
    }

    public static IngredientGrid getActiveIngredientGrid() { return activeIngredientGrid; }
    public static void clearActiveIngredientGrid() { activeIngredientGrid = null; }

    public static void setOnResultsPublished(Runnable callback) { onResultsPublished = callback; }

    // --- Helpers ---

    private static int getGridSize() {
        return (MinecraftClient.getInstance().currentScreen instanceof CraftingScreen) ? 3 : 2;
    }

    private static DynamicRegistryManager getRegistryManager() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null) return null;
        return client.world.getRegistryManager();
    }

    // --- Public API ---

    public static List<RecipeResultCollection> resolveForTab(ClientRecipeBook recipeBook) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.world == null) return List.of();

        DynamicRegistryManager registryManager = getRegistryManager();
        if (registryManager == null) return List.of();

        int gridSize = getGridSize();

        // Build tree if needed
        if (tree == null) {
            if (!building) {
                building = true;
                List<RecipeResultCollection> allCrafting = new ArrayList<>(
                        recipeBook.getResultsForGroup(RecipeBookGroup.CRAFTING_SEARCH));
                lastRecipeCount = allCrafting.size();

                // Build placeholder results
                List<RecipeResultCollection> placeholder = new ArrayList<>();
                for (RecipeResultCollection coll : allCrafting) {
                    List<RecipeEntry<?>> entries = coll.getAllRecipes();
                    if (!entries.isEmpty()) {
                        RecipeResultCollection nc = new RecipeResultCollection(registryManager, entries);
                        RecipeResultCollectionAccessor acc = (RecipeResultCollectionAccessor) nc;
                        for (RecipeEntry<?> e : entries) acc.getFittingRecipes().add(e);
                        placeholder.add(nc);
                    }
                }
                cachedResults = placeholder;

                DynamicRegistryManager bgRegistry = registryManager;
                RESOLVER_EXECUTOR.submit(() -> {
                    try {
                        long startTime = System.nanoTime();
                        RecipeTree built = RecipeTreeBuilder.build(allCrafting, bgRegistry);
                        long elapsed = (System.nanoTime() - startTime) / 1_000_000;
                        client.execute(() -> {
                            tree = built;
                            building = false;
                            lastGeneration = -1;
                            LOG.info("[CC] Tree built in {}ms ({} recipe groups)", elapsed, lastRecipeCount);
                            Runnable cb = onResultsPublished;
                            if (cb != null) cb.run();
                        });
                    } catch (Exception e) {
                        LOG.error("[CC] Tree build failed", e);
                        client.execute(() -> building = false);
                    }
                });
            }
            return cachedResults;
        }

        // Same-tick optimization
        Set<Item> changed = tracker.snapshot(client.player.getInventory(), client.world.getTime());
        if (changed == null) {
            return cachedResults;
        }

        boolean gridChanged = gridSize != lastGridSize;
        lastGridSize = gridSize;

        // Check if recipes changed (new recipes unlocked)
        List<RecipeResultCollection> allCrafting = new ArrayList<>(
                client.player.getRecipeBook().getResultsForGroup(RecipeBookGroup.CRAFTING_SEARCH));
        if (allCrafting.size() != lastRecipeCount) {
            clearCache();
            return resolveForTab(recipeBook);
        }

        if (changed.isEmpty() && !gridChanged
                && tracker.getGeneration() == lastGeneration && !cachedResults.isEmpty()) {
            return cachedResults;
        }

        // Recalculate counts
        long resolveStart = System.nanoTime();
        Map<Item, Integer> inventory = tracker.getInventory();
        Map<Item, Integer> containerInv = ClientCraftConfig.searchContainers
                ? tracker.getContainerInventory() : null;
        boolean hasContainers = containerInv != null && !containerInv.isEmpty();

        if (gridChanged || craftCountsByItem.isEmpty() || changed.size() > 50) {
            craftCountsByItem = CraftCalculator.calculateAllCounts(tree, inventory, containerInv, gridSize);
        } else if (!changed.isEmpty()) {
            Set<Item> affected = CraftCalculator.computeAffectedItems(tree, changed);
            CraftCalculator.updateCounts(tree, inventory, containerInv, gridSize, affected, craftCountsByItem);
        }

        lastGeneration = tracker.getGeneration();

        // Build container-only set
        Map<Identifier, Integer> counts = new HashMap<>();
        Set<Identifier> containerSet = new HashSet<>();

        Set<Item> containerOnlyItems = new HashSet<>();
        if (hasContainers) {
            for (Map.Entry<Item, Integer> e : craftCountsByItem.entrySet()) {
                int directCount = CraftCalculator.maxCraftable(
                        tree.getNode(e.getKey()) != null ? tree.getNode(e.getKey()) : new BaseResource(e.getKey()),
                        tree, inventory, null, gridSize);
                if (directCount <= 0 && e.getValue() > 0) {
                    containerOnlyItems.add(e.getKey());
                }
            }
        }

        // Build tab results
        List<RecipeResultCollection> result = new ArrayList<>();
        Map<RecipeResultCollection, Integer> ranks = new IdentityHashMap<>();
        Set<RecipeResultCollection> autoCraft = new HashSet<>();

        for (RecipeResultCollection coll : allCrafting) {
            List<RecipeEntry<?>> allEntries = new ArrayList<>();
            List<RecipeEntry<?>> craftable = new ArrayList<>();
            boolean hasDirect = false;
            boolean hasContainer = false;

            for (RecipeEntry<?> entry : coll.getAllRecipes()) {
                if (!RecipeTreeBuilder.fitsInGrid(entry, gridSize)) continue;
                Item out = RecipeTreeBuilder.getOutputItem(entry, registryManager);
                if (out == null) continue;
                if (RecipeTreeBuilder.isSelfConsuming(entry, out)) continue;

                allEntries.add(entry);

                int totalCount = craftCountsByItem.getOrDefault(out, 0);
                int alreadyHave = inventory.getOrDefault(out, 0);
                if (hasContainers) alreadyHave += containerInv.getOrDefault(out, 0);
                int craftableCount = totalCount - alreadyHave;
                if (craftableCount > 0) {
                    int displayCount = simulateCraftableCount(out, entry.id(), inventory, containerInv, gridSize);
                    if (displayCount <= 0) continue;
                    craftable.add(entry);
                    counts.put(entry.id(), Math.min(999, displayCount));

                    if (containerOnlyItems.contains(out)) {
                        containerSet.add(entry.id());
                        hasContainer = true;
                    } else {
                        hasDirect = true;
                    }
                }
            }

            if (!allEntries.isEmpty()) {
                RecipeResultCollection nc = new RecipeResultCollection(registryManager, allEntries);
                RecipeResultCollectionAccessor acc = (RecipeResultCollectionAccessor) nc;
                for (RecipeEntry<?> e : allEntries) acc.getFittingRecipes().add(e);
                for (RecipeEntry<?> e : craftable) acc.getCraftableRecipes().add(e);
                ranks.put(nc, hasDirect ? 0 : hasContainer ? 1 : 2);
                autoCraft.add(nc);
                result.add(nc);
            }
        }

        craftCounts = counts;
        containerCraftable = containerSet;
        collectionRanks = ranks;
        autoCraftCollections = autoCraft;
        cachedResults = result;

        LOG.info("[CC] resolveForTab: {}ms ({} craftable)", (System.nanoTime() - resolveStart) / 1_000_000, counts.size());
        return cachedResults;
    }

    public static AutoCrafter.CraftPlan buildCraftCyclesForMode(RecipeEntry<?> target, AutoCrafter.Mode mode) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.world == null || tree == null) return null;

        DynamicRegistryManager registryManager = getRegistryManager();
        if (registryManager == null) return null;

        int gridSize = getGridSize();
        tracker.snapshot(client.player.getInventory(), client.world.getTime());
        Map<Item, Integer> inventory = new HashMap<>(tracker.getInventory());
        Map<Item, Integer> containerInv = ClientCraftConfig.searchContainers
                ? tracker.getContainerInventory() : null;

        if (containerInv != null && !containerInv.isEmpty()) {
            containerInv.forEach((k, v) -> inventory.merge(k, v, Integer::sum));
        }

        Item outputItem = RecipeTreeBuilder.getOutputItem(target, registryManager);
        if (outputItem == null) return null;

        CraftedItem crafted = findMatchingCraftedItem(outputItem, target.id());
        if (crafted == null) return null;

        int outputCount = RecipeTreeBuilder.getOutputCount(target, registryManager);
        if (outputCount <= 0) outputCount = 1;

        int maxAvailable = CraftCalculator.maxCraftable(crafted, tree, tracker.getInventory(),
                ClientCraftConfig.searchContainers ? tracker.getContainerInventory() : null, gridSize);
        if (maxAvailable <= 0) return null;

        // Check if direct recipe
        Set<Item> uncraftable = new HashSet<>();
        List<RecipeEntry<?>> firstSteps = new ArrayList<>();
        Map<Item, Integer> sim = new HashMap<>(inventory);
        boolean canDirect = simulateCraft(crafted, sim, firstSteps, gridSize, new HashSet<>(), uncraftable);

        boolean directCraft = canDirect && mode == AutoCrafter.Mode.ALL && firstSteps.size() == 1;

        ItemStack output = target.value().getResult(registryManager);
        int maxRepeats;
        if (directCraft) {
            int craftsPerClick = output.getMaxCount() / outputCount;
            int total = maxAvailable / outputCount;
            maxRepeats = Math.max(1, (total + craftsPerClick - 1) / craftsPerClick);
        } else {
            maxRepeats = switch (mode) {
                case ONCE -> 1;
                case STACK -> (output.getMaxCount() + outputCount - 1) / outputCount;
                case ALL -> maxAvailable / outputCount;
            };
        }
        if (maxRepeats <= 0) return null;

        // Build step lists
        List<List<RecipeEntry<?>>> cycles = new ArrayList<>();
        sim = new HashMap<>(inventory);

        for (int r = 0; r < maxRepeats; r++) {
            List<RecipeEntry<?>> steps = new ArrayList<>();
            if (!simulateCraft(crafted, sim, steps, gridSize, new HashSet<>(), uncraftable)) break;
            cycles.add(steps);
        }

        if (cycles.isEmpty()) return null;
        return new AutoCrafter.CraftPlan(cycles, directCraft);
    }

    private static boolean simulateCraft(
            CraftedItem target, Map<Item, Integer> sim,
            List<RecipeEntry<?>> steps, int gridSize, Set<Item> inProgress,
            Set<Item> uncraftable) {
        if (target.gridSize() > gridSize) return false;
        if (!inProgress.add(target.item())) return false;
        if (uncraftable.contains(target.item())) {
            inProgress.remove(target.item());
            return false;
        }

        for (IngredientEdge edge : target.ingredients()) {
            for (int i = 0; i < edge.count(); i++) {
                IngredientOption chosen = null;
                for (IngredientOption opt : edge.options()) {
                    if (sim.getOrDefault(opt.item(), 0) >= 1) {
                        chosen = opt;
                        break;
                    }
                }

                if (chosen != null) {
                    sim.put(chosen.item(), sim.get(chosen.item()) - 1);
                } else {
                    boolean subCrafted = false;
                    for (IngredientOption opt : edge.options()) {
                        if (uncraftable.contains(opt.item())) continue;

                        List<CraftedItem> candidates = new ArrayList<>();
                        if (opt.node() instanceof CraftedItem c) candidates.add(c);
                        if (tree != null) {
                            List<CraftedItem> alts = tree.getAllRecipes(opt.item());
                            if (alts != null) {
                                for (CraftedItem alt : alts) {
                                    if (!candidates.contains(alt)) candidates.add(alt);
                                }
                            }
                        }

                        boolean optSucceeded = false;
                        for (CraftedItem subNode : candidates) {
                            Map<Item, Integer> simBackup = new HashMap<>(sim);
                            int stepsSize = steps.size();

                            if (simulateCraft(subNode, sim, steps, gridSize, inProgress, uncraftable)) {
                                int nowHave = sim.getOrDefault(opt.item(), 0);
                                if (nowHave >= 1) {
                                    sim.put(opt.item(), nowHave - 1);
                                    optSucceeded = true;
                                    subCrafted = true;
                                    break;
                                }
                            }

                            sim.clear();
                            sim.putAll(simBackup);
                            while (steps.size() > stepsSize) steps.removeLast();
                        }
                        if (!optSucceeded) uncraftable.add(opt.item());
                        if (subCrafted) break;
                    }
                    if (!subCrafted) {
                        inProgress.remove(target.item());
                        return false;
                    }
                }
            }
        }

        steps.add(target.recipeEntry());
        sim.merge(target.item(), target.outputCount(), Integer::sum);
        inProgress.remove(target.item());
        return true;
    }

    public static ItemStack resolveResult(RecipeEntry<?> entry) {
        DynamicRegistryManager registryManager = getRegistryManager();
        if (registryManager == null) return ItemStack.EMPTY;
        ItemStack out = entry.value().getResult(registryManager);
        return out.isEmpty() ? ItemStack.EMPTY : out;
    }

    public static int getCraftCount(Identifier id) {
        return craftCounts.getOrDefault(id, 0);
    }

    public static boolean isAutoCraftCollection(RecipeResultCollection collection) {
        return autoCraftCollections.contains(collection);
    }

    public static boolean isContainerCraftable(Identifier id) {
        return containerCraftable.contains(id);
    }

    public static int getCollectionRank(RecipeResultCollection coll) {
        return collectionRanks.getOrDefault(coll, 2);
    }

    public static String getLowerCaseName(Item item) {
        return lowerCaseNameCache.computeIfAbsent(item,
                i -> new ItemStack(i).getName().getString().toLowerCase(Locale.ROOT));
    }

    public static RecipeResultCollection buildIngredientCollection(RecipeEntry<?> originalEntry) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.world == null || tree == null) return null;

        DynamicRegistryManager registryManager = getRegistryManager();
        if (registryManager == null) return null;

        int gridSize = getGridSize();
        tracker.snapshot(client.player.getInventory(), client.world.getTime());
        Map<Item, Integer> inventory = tracker.getInventory();

        DefaultedList<Ingredient> ingredients = RecipeTreeBuilder.getIngredients(originalEntry);
        if (ingredients == null || ingredients.isEmpty()) return null;

        IngredientGrid grid = new IngredientGrid();
        Arrays.fill(grid.items, ItemStack.EMPTY);
        fillGrid(grid, originalEntry, ingredients, inventory);
        computeGridCraftability(grid, inventory, gridSize);

        activeIngredientGrid = grid;
        return buildFakeCollection(grid, originalEntry, registryManager);
    }

    public static void clearCache() {
        tree = null;
        building = false;
        onResultsPublished = null;
        cachedResults = List.of();
        craftCounts = Map.of();
        craftCountsByItem = new HashMap<>();
        containerCraftable = Set.of();
        collectionRanks = Map.of();
        autoCraftCollections = Set.of();
        lastGridSize = -1;
        lastGeneration = -1;
        lastRecipeCount = -1;
        activeIngredientGrid = null;
        lowerCaseNameCache.clear();
        tracker.clear();
    }

    // --- Helpers ---

    private static CraftedItem findMatchingCraftedItem(Item outputItem, Identifier recipeId) {
        if (tree == null) return null;
        List<CraftedItem> recipes = tree.getAllRecipes(outputItem);
        if (recipes == null || recipes.isEmpty()) {
            RecipeNode node = tree.getNode(outputItem);
            return node instanceof CraftedItem c ? c : null;
        }
        for (CraftedItem recipe : recipes) {
            if (recipe.recipeId().equals(recipeId)) return recipe;
        }
        RecipeNode node = tree.getNode(outputItem);
        if (node instanceof CraftedItem c) return c;
        return recipes.getFirst();
    }

    private static int simulateCraftableCount(Item outputItem, Identifier recipeId,
                                              Map<Item, Integer> inventory,
                                              Map<Item, Integer> containerInv, int gridSize) {
        if (tree == null) return 0;
        CraftedItem recipe = findMatchingCraftedItem(outputItem, recipeId);
        if (recipe == null || recipe.gridSize() > gridSize) return 0;

        Map<Item, Integer> sim = new HashMap<>(inventory);
        if (containerInv != null) containerInv.forEach((k, v) -> sim.merge(k, v, Integer::sum));

        int total = 0;
        for (int i = 0; i < 999; i++) {
            int before = sim.getOrDefault(outputItem, 0);
            if (!simulateCraft(recipe, sim, new ArrayList<>(), gridSize,
                    new HashSet<>(), new HashSet<>())) break;
            int gained = sim.getOrDefault(outputItem, 0) - before;
            if (gained <= 0) break;
            total += gained;
        }
        return total;
    }

    // --- Grid helpers ---

    private static void fillGrid(IngredientGrid grid, RecipeEntry<?> entry,
                                 DefaultedList<Ingredient> ingredients, Map<Item, Integer> inventory) {
        if (entry.value() instanceof ShapedRecipe shaped) {
            int w = shaped.getWidth(), h = shaped.getHeight();
            for (int row = 0; row < h; row++) for (int col = 0; col < w; col++) {
                int srcIdx = row * w + col;
                if (srcIdx >= ingredients.size()) continue;
                Ingredient ingredient = ingredients.get(srcIdx);
                if (ingredient.isEmpty()) continue;
                ItemStack resolved = resolveIngredientForDisplay(ingredient, inventory);
                if (!resolved.isEmpty()) {
                    int gridIdx = row * 3 + col;
                    grid.items[gridIdx] = resolved;
                    grid.slots[gridIdx] = ingredient;
                }
            }
        } else {
            int idx = 0;
            for (int i = 0, len = ingredients.size(); i < len && idx < 9; i++) {
                Ingredient ingredient = ingredients.get(i);
                if (ingredient.isEmpty()) continue;
                ItemStack resolved = resolveIngredientForDisplay(ingredient, inventory);
                if (!resolved.isEmpty()) {
                    grid.items[idx] = resolved;
                    grid.slots[idx] = ingredient;
                }
                idx++;
            }
        }
    }

    private static ItemStack resolveIngredientForDisplay(Ingredient ingredient, Map<Item, Integer> inventory) {
        ItemStack[] stacks = ingredient.getMatchingStacks();
        if (stacks.length == 0) return ItemStack.EMPTY;

        // Prefer item in inventory
        for (ItemStack stack : stacks) {
            if (inventory.getOrDefault(stack.getItem(), 0) > 0) return stack;
        }

        // Prefer craftable item from tree
        if (tree != null) {
            for (ItemStack stack : stacks) {
                if (tree.getNode(stack.getItem()) instanceof CraftedItem) return stack;
            }
        }

        return stacks[0];
    }

    private static void computeGridCraftability(IngredientGrid grid, Map<Item, Integer> inventory, int gridSize) {
        Map<Item, Integer> remaining = new HashMap<>(inventory);

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
            } else if (tree != null) {
                RecipeNode node = tree.getNode(item);
                if (node instanceof CraftedItem) {
                    int canCraft = CraftCalculator.maxCraftable(node, tree, remaining, null, gridSize);
                    grid.craftable[i] = canCraft > 0;
                } else {
                    Map<Item, Integer> containerInv = tracker.getContainerInventory();
                    if (containerInv.getOrDefault(item, 0) > 0) {
                        grid.inContainer[i] = true;
                    }
                }
            }
        }
    }

    private static RecipeResultCollection buildFakeCollection(IngredientGrid grid,
                                                              RecipeEntry<?> originalEntry,
                                                              DynamicRegistryManager registryManager) {
        List<RecipeEntry<?>> entries = new ArrayList<>(9);
        for (int i = 0; i < 9; i++) {
            ItemStack stack = grid.items[i];
            Identifier fakeId = Identifier.of("clientcraftmk4", "fake_" + i);
            Ingredient ingredient = stack.isEmpty()
                    ? Ingredient.empty()
                    : Ingredient.ofStacks(stack);
            // Create a simple shapeless recipe with single ingredient for display
            ShapelessRecipe fakeRecipe = new ShapelessRecipe("clientcraft",
                    net.minecraft.recipe.book.CraftingRecipeCategory.MISC,
                    stack.isEmpty() ? ItemStack.EMPTY : stack,
                    DefaultedList.ofSize(1, ingredient));
            entries.add(new RecipeEntry<>(fakeId, fakeRecipe));
        }
        RecipeResultCollection collection = new RecipeResultCollection(registryManager, entries);
        RecipeResultCollectionAccessor acc = (RecipeResultCollectionAccessor) collection;
        for (RecipeEntry<?> e : entries) {
            acc.getFittingRecipes().add(e);
            acc.getCraftableRecipes().add(e);
        }
        return collection;
    }
}
