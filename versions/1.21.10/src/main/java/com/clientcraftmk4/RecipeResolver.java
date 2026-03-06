package com.clientcraftmk4;

import com.clientcraftmk4.mixin.RecipeResultCollectionAccessor;
import com.clientcraftmk4.tree.*;
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
import net.minecraft.registry.tag.TagKey;

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
    private static Map<NetworkRecipeId, Integer> craftCounts = Map.of();
    private static Map<Item, Integer> craftCountsByItem = new HashMap<>();
    private static Set<NetworkRecipeId> containerCraftable = Set.of();
    private static Map<RecipeResultCollection, Integer> collectionRanks = Map.of();
    private static Set<RecipeResultCollection> autoCraftCollections = Set.of();
    private static int lastGridSize = -1;
    private static long lastGeneration = -1;

    private static volatile Runnable onResultsPublished = null;
    public static boolean lastTabWasClientCraft = false;

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

    public static void setOnResultsPublished(Runnable callback) { onResultsPublished = callback; }

    // --- Public API ---

    public static List<RecipeResultCollection> resolveForTab(ClientRecipeBook recipeBook) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.world == null) return List.of();

        int gridSize = getGridSize();

        // Build tree if needed (background thread for first build)
        if (tree == null) {
            if (!building) {
                building = true;
                List<RecipeResultCollection> allCrafting = new ArrayList<>(
                        recipeBook.getResultsForCategory(RecipeBookType.CRAFTING));

                // Build placeholder results
                List<RecipeResultCollection> placeholder = new ArrayList<>();
                for (RecipeResultCollection coll : allCrafting) {
                    List<RecipeDisplayEntry> entries = coll.getAllRecipes();
                    if (!entries.isEmpty()) {
                        RecipeResultCollection nc = new RecipeResultCollection(entries);
                        RecipeResultCollectionAccessor acc = (RecipeResultCollectionAccessor) nc;
                        for (RecipeDisplayEntry e : entries) acc.getDisplayableRecipes().add(e.id());
                        placeholder.add(nc);
                    }
                }
                cachedResults = placeholder;

                RESOLVER_EXECUTOR.submit(() -> {
                    try {
                        RecipeTree built = RecipeTreeBuilder.build(allCrafting);
                        client.execute(() -> {
                            tree = built;
                            building = false;
                            lastGeneration = -1; // Force full recalculation
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

        // Tree exists — snapshot inventory and check delta
        Set<Item> changed = tracker.snapshot(client.player.getInventory(), client.world.getTime());
        boolean gridChanged = gridSize != lastGridSize;
        lastGridSize = gridSize;

        if (changed != null && changed.isEmpty() && !gridChanged
                && tracker.getGeneration() == lastGeneration && !cachedResults.isEmpty()) {
            return cachedResults; // Nothing changed, instant return
        }

        // Recalculate counts
        Map<Item, Integer> inventory = tracker.getInventory();
        Map<Item, Integer> containerInv = ClientCraftConfig.searchContainers
                ? tracker.getContainerInventory() : null;
        boolean hasContainers = containerInv != null && !containerInv.isEmpty();

        if (gridChanged || craftCountsByItem.isEmpty() || changed == null
                || changed.size() > 50) {
            // Full recalculation
            craftCountsByItem = CraftCalculator.calculateAllCounts(tree, inventory, containerInv, gridSize);
        } else if (!changed.isEmpty()) {
            // Delta recalculation
            Set<Item> affected = CraftCalculator.computeAffectedItems(tree, changed);
            CraftCalculator.updateCounts(tree, inventory, containerInv, gridSize, affected, craftCountsByItem);
        }

        lastGeneration = tracker.getGeneration();

        // Build container-craftable set and recipe-id counts
        Map<NetworkRecipeId, Integer> counts = new HashMap<>();
        Set<NetworkRecipeId> containerSet = new HashSet<>();

        // Compute container-only for items
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
        List<RecipeResultCollection> allCrafting = new ArrayList<>(
                client.player.getRecipeBook().getResultsForCategory(RecipeBookType.CRAFTING));

        List<RecipeResultCollection> result = new ArrayList<>();
        Map<RecipeResultCollection, Integer> ranks = new IdentityHashMap<>();
        Set<RecipeResultCollection> autoCraft = new HashSet<>();

        for (RecipeResultCollection coll : allCrafting) {
            List<RecipeDisplayEntry> allEntries = new ArrayList<>();
            List<RecipeDisplayEntry> craftable = new ArrayList<>();
            boolean hasDirect = false;
            boolean hasContainer = false;

            for (RecipeDisplayEntry entry : coll.getAllRecipes()) {
                if (!RecipeTreeBuilder.fitsInGrid(entry.display(), gridSize)) continue;
                Item out = RecipeTreeBuilder.getOutputItem(entry.display());
                if (out == null) continue;
                if (RecipeTreeBuilder.isSelfConsuming(entry, out)) continue;

                allEntries.add(entry);

                int totalCount = craftCountsByItem.getOrDefault(out, 0);
                if (totalCount > 0 && isRecipeCraftable(out, entry.id(), craftCountsByItem, gridSize)) {
                    craftable.add(entry);
                    // Show how many more can be crafted, not total available
                    int alreadyHave = inventory.getOrDefault(out, 0);
                    if (hasContainers) alreadyHave += containerInv.getOrDefault(out, 0);
                    int displayCount = Math.min(999, Math.max(1, totalCount - alreadyHave));
                    counts.put(entry.id(), displayCount);

                    if (containerOnlyItems.contains(out)) {
                        containerSet.add(entry.id());
                        hasContainer = true;
                    } else {
                        hasDirect = true;
                    }
                }
            }

            if (!allEntries.isEmpty()) {
                RecipeResultCollection nc = new RecipeResultCollection(allEntries);
                RecipeResultCollectionAccessor acc = (RecipeResultCollectionAccessor) nc;
                for (RecipeDisplayEntry e : allEntries) acc.getDisplayableRecipes().add(e.id());
                for (RecipeDisplayEntry e : craftable) acc.getCraftableRecipes().add(e.id());
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

        return cachedResults;
    }

    public static AutoCrafter.CraftPlan buildCraftCyclesForMode(RecipeDisplayEntry target, AutoCrafter.Mode mode) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.world == null || tree == null) {
            LOG.warn("[CC] buildCraftCycles: null client/player/world/tree");
            return null;
        }

        int gridSize = getGridSize();
        tracker.snapshot(client.player.getInventory(), client.world.getTime());
        Map<Item, Integer> inventory = new HashMap<>(tracker.getInventory());
        Map<Item, Integer> containerInv = ClientCraftConfig.searchContainers
                ? tracker.getContainerInventory() : null;

        if (containerInv != null && !containerInv.isEmpty()) {
            containerInv.forEach((k, v) -> inventory.merge(k, v, Integer::sum));
        }

        Item outputItem = RecipeTreeBuilder.getOutputItem(target.display());
        if (outputItem == null) {
            LOG.warn("[CC] buildCraftCycles: outputItem is null for {}", target.id());
            return null;
        }

        CraftedItem crafted = findMatchingCraftedItem(outputItem, target.id());
        if (crafted == null) return null;

        int outputCount = RecipeTreeBuilder.getOutputCount(target.display());
        if (outputCount <= 0) outputCount = 1;

        int maxAvailable = CraftCalculator.maxCraftable(crafted, tree, tracker.getInventory(),
                ClientCraftConfig.searchContainers ? tracker.getContainerInventory() : null, gridSize);
        if (maxAvailable <= 0) return null;

        // Check if this is a direct recipe (no sub-crafting needed)
        long startTime = System.nanoTime();
        Set<Item> uncraftable = new HashSet<>();
        List<NetworkRecipeId> firstSteps = new ArrayList<>();
        Map<Item, Integer> sim = new HashMap<>(inventory);
        boolean canDirect = simulateCraft(crafted, sim, firstSteps, gridSize, new HashSet<>(), uncraftable);

        boolean directCraft = canDirect && mode == AutoCrafter.Mode.ALL && firstSteps.size() == 1;

        ItemStack output = RecipeTreeBuilder.resolveOutputSlot(target.display().result());
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
        List<List<NetworkRecipeId>> cycles = new ArrayList<>();
        sim = new HashMap<>(inventory);

        for (int r = 0; r < maxRepeats; r++) {
            List<NetworkRecipeId> steps = new ArrayList<>();
            if (!simulateCraft(crafted, sim, steps, gridSize, new HashSet<>(), uncraftable)) break;
            cycles.add(steps);
        }

        if (cycles.isEmpty()) return null;
        LOG.info("[CC] buildCraftCycles: {} in {}ms", outputItem, (System.nanoTime() - startTime) / 1_000_000);
        return new AutoCrafter.CraftPlan(cycles, directCraft);
    }

    private static boolean simulateCraft(
            CraftedItem target, Map<Item, Integer> sim,
            List<NetworkRecipeId> steps, int gridSize, Set<Item> inProgress,
            Set<Item> uncraftable) {
        if (target.gridSize() > gridSize) return false;
        if (!inProgress.add(target.item())) return false;
        if (uncraftable.contains(target.item())) {
            inProgress.remove(target.item());
            return false;
        }

        for (IngredientEdge edge : target.ingredients()) {
            for (int i = 0; i < edge.count(); i++) {
                // Pick best option: prefer available in sim, then craftable
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
                    // Try sub-crafting: collect all candidate recipes per option
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

                            // Rollback sim and steps on failure
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

        // All ingredients consumed, add output
        steps.add(target.recipeId());
        sim.merge(target.item(), target.outputCount(), Integer::sum);
        inProgress.remove(target.item());
        return true;
    }

    public static ItemStack resolveResult(RecipeDisplay display) {
        ItemStack out = RecipeTreeBuilder.resolveOutputSlot(display.result());
        return out.isEmpty() ? ItemStack.EMPTY : out;
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
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.world == null || tree == null) return null;

        int gridSize = getGridSize();
        tracker.snapshot(client.player.getInventory(), client.world.getTime());
        Map<Item, Integer> inventory = tracker.getInventory();

        RecipeDisplay display = originalEntry.display();
        List<SlotDisplay> slots = RecipeTreeBuilder.getSlots(display);
        if (slots == null || slots.isEmpty()) return null;

        IngredientGrid grid = new IngredientGrid();
        Arrays.fill(grid.items, ItemStack.EMPTY);
        fillGrid(grid, display, slots, inventory);
        computeGridCraftability(grid, inventory, gridSize);

        activeIngredientGrid = grid;
        return buildFakeCollection(grid, originalEntry);
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
        activeIngredientGrid = null;
        lowerCaseNameCache.clear();
        tracker.clear();
    }

    // --- Helpers ---

    private static CraftedItem findMatchingCraftedItem(Item outputItem, NetworkRecipeId recipeId) {
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

    private static boolean isRecipeCraftable(Item outputItem, NetworkRecipeId recipeId,
                                             Map<Item, Integer> craftCountsByItem, int gridSize) {
        if (tree == null) return true;
        List<CraftedItem> recipes = tree.getAllRecipes(outputItem);
        if (recipes == null || recipes.isEmpty()) return craftCountsByItem.getOrDefault(outputItem, 0) > 0;
        for (CraftedItem recipe : recipes) {
            if (!recipe.recipeId().equals(recipeId)) continue;
            if (recipe.gridSize() > gridSize) return false;
            for (IngredientEdge edge : recipe.ingredients()) {
                boolean edgeAvailable = false;
                for (IngredientOption option : edge.options()) {
                    if (craftCountsByItem.getOrDefault(option.item(), 0) > 0) {
                        edgeAvailable = true;
                        break;
                    }
                }
                if (!edgeAvailable) return false;
            }
            return true;
        }
        return craftCountsByItem.getOrDefault(outputItem, 0) > 0;
    }

    private static int getGridSize() {
        return (MinecraftClient.getInstance().currentScreen instanceof CraftingScreen) ? 3 : 2;
    }

    private static void fillGrid(IngredientGrid grid, RecipeDisplay display,
                                 List<SlotDisplay> slots, Map<Item, Integer> inventory) {
        if (display instanceof ShapedCraftingRecipeDisplay shaped) {
            int w = shaped.width(), h = shaped.height();
            for (int row = 0; row < h; row++) for (int col = 0; col < w; col++) {
                int srcIdx = row * w + col;
                if (srcIdx >= slots.size()) continue;
                SlotDisplay slot = slots.get(srcIdx);
                if (slot instanceof SlotDisplay.EmptySlotDisplay) continue;
                ItemStack resolved = resolveIngredientSlot(slot, inventory);
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
                ItemStack resolved = resolveIngredientSlot(slot, inventory);
                if (!resolved.isEmpty()) {
                    grid.items[idx] = resolved;
                    grid.slots[idx] = slot;
                }
                idx++;
            }
        }
    }

    private static ItemStack resolveIngredientSlot(SlotDisplay slot, Map<Item, Integer> inventory) {
        MinecraftClient client = MinecraftClient.getInstance();

        if (slot instanceof SlotDisplay.ItemSlotDisplay d) return new ItemStack(d.item().value());
        if (slot instanceof SlotDisplay.StackSlotDisplay d)
            return new ItemStack(d.stack().getItem(), d.stack().getCount());

        if (slot instanceof SlotDisplay.TagSlotDisplay d) {
            if (client.world != null) {
                var regOpt = client.world.getRegistryManager().getOptional(RegistryKeys.ITEM);
                if (regOpt.isPresent()) {
                    var entriesOpt = regOpt.get().getOptional(d.tag());
                    if (entriesOpt.isPresent()) {
                        ItemStack fallback = ItemStack.EMPTY;
                        for (var entry : entriesOpt.get()) {
                            Item item = entry.value();
                            if (fallback.isEmpty()) fallback = new ItemStack(item);
                            if (inventory.getOrDefault(item, 0) > 0) return new ItemStack(item);
                            if (tree != null && tree.getNode(item) instanceof CraftedItem) {
                                if (fallback.getItem() != item) fallback = new ItemStack(item);
                            }
                        }
                        return fallback;
                    }
                }
            }
            return ItemStack.EMPTY;
        }

        if (slot instanceof SlotDisplay.CompositeSlotDisplay d) {
            ItemStack fallback = ItemStack.EMPTY;
            for (SlotDisplay sub : d.contents()) {
                ItemStack r = resolveIngredientSlot(sub, inventory);
                if (!r.isEmpty()) {
                    if (inventory.getOrDefault(r.getItem(), 0) > 0) return r;
                    if (fallback.isEmpty()) fallback = r;
                }
            }
            return fallback;
        }

        if (slot instanceof SlotDisplay.WithRemainderSlotDisplay d) {
            return resolveIngredientSlot(d.input(), inventory);
        }

        return ItemStack.EMPTY;
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
        for (RecipeDisplayEntry e : entries) {
            acc.getDisplayableRecipes().add(e.id());
            acc.getCraftableRecipes().add(e.id());
        }
        return collection;
    }
}
