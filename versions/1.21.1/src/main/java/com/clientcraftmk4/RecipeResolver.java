package com.clientcraftmk4;

import com.clientcraftmk4.mixin.RecipeResultCollectionAccessor;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.CraftingScreen;
import net.minecraft.client.gui.screen.recipebook.RecipeResultCollection;
import net.minecraft.client.recipebook.ClientRecipeBook;
import net.minecraft.client.recipebook.RecipeBookGroup;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.BundleContentsComponent;
import net.minecraft.component.type.ContainerComponent;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.recipe.Ingredient;
import net.minecraft.recipe.Recipe;
import net.minecraft.recipe.RecipeEntry;
import net.minecraft.recipe.ShapedRecipe;
import net.minecraft.recipe.ShapelessRecipe;
import net.minecraft.recipe.book.CraftingRecipeCategory;
import net.minecraft.registry.DynamicRegistryManager;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.tag.TagKey;
import net.minecraft.util.Identifier;
import net.minecraft.util.collection.DefaultedList;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class RecipeResolver {
    private static final Logger LOG = LoggerFactory.getLogger("ClientCraftMK4");
    private static final int MAX_DEPTH = 10;
    private static final int MAX_REPEATS = 999;
    private static final ExecutorService RESOLVER_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "ClientCraft-Resolver");
        t.setDaemon(true);
        return t;
    });

    private static List<RecipeResultCollection> cachedResults = List.of();
    private static long lastCacheKey = 0;
    private static volatile boolean resolving = false;
    private static volatile Runnable onResultsPublished = null;

    // Batch mode: during mass/stack craft, skip all intermediate resolves.
    // AutoCrafter sets true on start, clears when inventory stabilizes.
    static volatile boolean batchMode = false;

    static long getInventoryGeneration() { return inventoryGeneration; }
    static void pollInventory() { getOrSnapshotInventory(); }
    static void triggerRefresh() {
        inventoryGeneration++;
        Runnable callback = onResultsPublished;
        if (callback != null) callback.run();
    }

    public static void setOnResultsPublished(Runnable callback) { onResultsPublished = callback; }
    public static boolean lastTabWasClientCraft = false;

    private static Map<Item, List<RecipeEntry<?>>> recipesByOutput = Map.of();
    private static boolean recipeIndexDirty = true;
    private static int lastRecipeCount = 0;
    private static int currentGridSize = 3;
    private static Map<Identifier, Integer> craftCounts = Map.of();
    private static Set<Identifier> containerCraftable = Set.of();
    private static Set<Item> containerAvailableItems = Set.of();
    private static Map<RecipeResultCollection, Integer> collectionRanks = Map.of();
    private static Set<RecipeResultCollection> autoCraftCollections = Set.of();

    private static Map<Item, Integer> cachedInventory = Map.of();
    private static Map<Item, Integer> cachedContainerInventory = Map.of();
    private static long inventorySnapshotTick = -1;
    private static long inventoryGeneration = 0;

    private static Map<Item, String> lowerCaseNameCache = new HashMap<>();
    private static IngredientGrid activeIngredientGrid = null;

    // --- Tag cache ---
    private static Set<TagKey<Item>> knownTags = new HashSet<>();
    private static Map<Item, Set<TagKey<Item>>> itemToTags = new HashMap<>();
    private static Map<TagKey<Item>, Set<Item>> inventoryTagIndex = new HashMap<>();
    private static Map<TagKey<Item>, Set<Item>> containerTagIndex = new HashMap<>();
    private static Map<TagKey<Item>, List<Item>> tagMembersCache = new ConcurrentHashMap<>();
    private static Map<TagKey<Item>, Item> tagFallbackItem = new ConcurrentHashMap<>();
    private static Map<TagKey<Item>, List<Item>> craftableTagIndex = new HashMap<>();
    private static Set<Item> lastInventoryKeySet = Set.of();
    private static Set<Item> lastContainerKeySet = Set.of();

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

    // --- Shared helpers ---

    private static int getGridSize() {
        return (MinecraftClient.getInstance().currentScreen instanceof CraftingScreen) ? 3 : 2;
    }

    private static DynamicRegistryManager getRegistryManager() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null) return null;
        return client.world.getRegistryManager();
    }

    private static Map<Item, Integer> getOrSnapshotInventory() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null || client.player == null) return Map.of();
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
                BundleContentsComponent bundle = stack.get(DataComponentTypes.BUNDLE_CONTENTS);
                if (bundle != null) {
                    for (ItemStack contained : bundle.iterate()) {
                        containerSnapshot.merge(contained.getItem(), contained.getCount(), Integer::sum);
                    }
                }
            }

            if (stack.contains(DataComponentTypes.CUSTOM_NAME)) continue;
            snapshot.merge(stack.getItem(), stack.getCount(), Integer::sum);
        }
        if (!snapshot.equals(cachedInventory) || !containerSnapshot.equals(cachedContainerInventory)) {
            cachedInventory = snapshot;
            cachedContainerInventory = containerSnapshot;
            rebuildInventoryTagIndex();
            inventoryGeneration++;
        }
        return cachedInventory;
    }

    private static void ensureIndex() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return;
        List<RecipeResultCollection> allCrafting = client.player.getRecipeBook().getResultsForGroup(RecipeBookGroup.CRAFTING_SEARCH);
        int currentCount = 0;
        for (RecipeResultCollection c : allCrafting) currentCount += c.getAllRecipes().size();
        if (!recipeIndexDirty && !recipesByOutput.isEmpty() && currentCount == lastRecipeCount) return;
        lastRecipeCount = currentCount;
        DynamicRegistryManager registryManager = getRegistryManager();
        if (registryManager == null) return;
        Map<Item, List<RecipeEntry<?>>> index = new HashMap<>();
        knownTags.clear();
        for (RecipeResultCollection coll : allCrafting) {
            for (RecipeEntry<?> entry : coll.getAllRecipes()) {
                Item out = getOutputItem(entry, registryManager);
                if (out != null) index.computeIfAbsent(out, k -> new ArrayList<>()).add(entry);
                DefaultedList<Ingredient> ingredients = getIngredients(entry);
                if (ingredients != null) {
                    for (Ingredient ingredient : ingredients) collectTags(ingredient);
                }
            }
        }
        recipesByOutput = index;
        if (!resolving) recipeIndexDirty = false;
        for (Item item : index.keySet()) computeTagsForItem(item);
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

    // --- Tag cache ---

    private static void collectTags(Ingredient ingredient) {
        if (ingredient == null || ingredient.isEmpty()) return;
        // In 1.21.1, Ingredient internally resolves tags.
        // We scan matching stacks to discover which tags are relevant.
        // We build knownTags by checking each item in getMatchingStacks against known tags.
        // However, we can also detect tags by iterating the item registry's tags.
        // For efficiency, we collect tags from matching stacks after the index is built.
        // The tag collection in 1.21.1 is done lazily via computeTagsForItem.
    }

    private static Set<TagKey<Item>> computeTagsForItem(Item item) {
        Set<TagKey<Item>> existing = itemToTags.get(item);
        if (existing != null) return existing;

        Set<TagKey<Item>> tags = new HashSet<>();
        ItemStack stack = new ItemStack(item);
        for (TagKey<Item> tag : knownTags) {
            if (stack.isIn(tag)) tags.add(tag);
        }
        Set<TagKey<Item>> result = tags.isEmpty() ? Set.of() : tags;
        itemToTags.put(item, result);
        return result;
    }

    /**
     * In 1.21.1, Ingredient handles tags internally. We discover tags by scanning
     * all ingredients across all recipes: for each ingredient with multiple matching
     * stacks, we look up what tags those items share.
     */
    private static void discoverTags() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null) return;
        var regOpt = client.world.getRegistryManager().getOptional(RegistryKeys.ITEM);
        if (regOpt.isEmpty()) return;
        var registry = regOpt.get();
        // Collect all tags from the item registry
        registry.streamTags().forEach(knownTags::add);
    }

    private static void rebuildInventoryTagIndex() {
        Set<Item> currentInvKeys = cachedInventory.keySet();
        Set<Item> currentContKeys = cachedContainerInventory.keySet();
        if (currentInvKeys.equals(lastInventoryKeySet) && currentContKeys.equals(lastContainerKeySet)) return;

        // Ensure we have discovered tags from the registry
        if (knownTags.isEmpty()) discoverTags();

        inventoryTagIndex.clear();
        for (Item item : currentInvKeys) {
            for (TagKey<Item> tag : computeTagsForItem(item)) {
                inventoryTagIndex.computeIfAbsent(tag, k -> new HashSet<>()).add(item);
            }
        }

        containerTagIndex.clear();
        for (Item item : currentContKeys) {
            for (TagKey<Item> tag : computeTagsForItem(item)) {
                containerTagIndex.computeIfAbsent(tag, k -> new HashSet<>()).add(item);
            }
        }

        lastInventoryKeySet = new HashSet<>(currentInvKeys);
        lastContainerKeySet = new HashSet<>(currentContKeys);

        craftableTagIndex.clear();
        Map<TagKey<Item>, List<Item>> hasInputsMap = new HashMap<>();
        Map<TagKey<Item>, List<Item>> noInputsMap = new HashMap<>();
        for (Item item : recipesByOutput.keySet()) {
            Set<TagKey<Item>> tags = computeTagsForItem(item);
            if (tags.isEmpty()) continue;
            boolean directInputs = hasDirectInputs(item);
            Map<TagKey<Item>, List<Item>> target = directInputs ? hasInputsMap : noInputsMap;
            for (TagKey<Item> tag : tags) {
                target.computeIfAbsent(tag, k -> new ArrayList<>()).add(item);
            }
        }
        for (TagKey<Item> tag : knownTags) {
            List<Item> has = hasInputsMap.get(tag);
            List<Item> no = noInputsMap.get(tag);
            if (has != null || no != null) {
                List<Item> combined = new ArrayList<>();
                if (has != null) combined.addAll(has);
                if (no != null) combined.addAll(no);
                craftableTagIndex.put(tag, combined);
            }
        }
    }

    private static List<Item> getOrComputeTagMembers(TagKey<Item> tag) {
        List<Item> cached = tagMembersCache.get(tag);
        if (cached != null) return cached;
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null) return null;
        var regOpt = client.world.getRegistryManager().getOptional(RegistryKeys.ITEM);
        if (regOpt.isEmpty()) return null;
        var entriesOpt = regOpt.get().getEntryList(tag);
        if (entriesOpt.isEmpty()) return null;
        List<Item> items = new ArrayList<>();
        for (var entry : entriesOpt.get()) items.add(entry.value());
        tagMembersCache.put(tag, items);
        return items;
    }

    private static boolean hasDirectInputs(Item item) {
        List<RecipeEntry<?>> recipes = recipesByOutput.get(item);
        if (recipes == null) return false;
        outer: for (RecipeEntry<?> entry : recipes) {
            DefaultedList<Ingredient> ingredients = getIngredients(entry);
            if (ingredients == null) continue;
            for (Ingredient ingredient : ingredients) {
                if (ingredient.isEmpty()) continue;
                ItemStack resolved = resolveIngredient(ingredient, cachedInventory);
                if (resolved.isEmpty()) { continue outer; }
                if (cachedInventory.getOrDefault(resolved.getItem(), 0) > 0) continue;
                // Check if any matching stack is in inventory via tag index
                boolean found = false;
                for (ItemStack match : ingredient.getMatchingStacks()) {
                    if (cachedInventory.getOrDefault(match.getItem(), 0) > 0) { found = true; break; }
                }
                if (found) continue;
                continue outer;
            }
            return true; // all slots satisfied
        }
        return false;
    }

    // --- Public API ---

    public static List<RecipeResultCollection> resolveForTab(ClientRecipeBook recipeBook) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.world == null) return List.of();

        int gridSize = getGridSize();
        currentGridSize = gridSize;
        ensureIndex();
        Map<Item, Integer> inventory = getOrSnapshotInventory();

        long cacheKey = inventoryGeneration * 7L + gridSize;
        if (cacheKey == lastCacheKey && !cachedResults.isEmpty()) return cachedResults;
        if (batchMode) return cachedResults;
        if (resolving) return cachedResults;
        if (recipesByOutput.isEmpty()) return List.of();

        DynamicRegistryManager registryManager = getRegistryManager();
        if (registryManager == null) return List.of();

        List<RecipeResultCollection> allCrafting = new ArrayList<>(
                recipeBook.getResultsForGroup(RecipeBookGroup.CRAFTING_SEARCH));
        if (allCrafting.isEmpty()) return List.of();

        Map<Item, Integer> invSnapshot = new HashMap<>(inventory);
        Map<Item, Integer> contSnapshot = new HashMap<>(cachedContainerInventory);
        boolean checkContainers = ClientCraftConfig.searchContainers && !contSnapshot.isEmpty();
        int snapGridSize = gridSize;
        long snapCacheKey = cacheKey;

        // Build placeholder results so tab is visible while background computes
        if (cachedResults.isEmpty()) {
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
        }

        resolving = true;
        int startRecipeCount = lastRecipeCount;

        RESOLVER_EXECUTOR.submit(() -> {
            try {
                long t0 = System.nanoTime();

                DynamicRegistryManager bgRegistryManager = getRegistryManager();
                if (bgRegistryManager == null) {
                    MinecraftClient.getInstance().execute(() -> resolving = false);
                    return;
                }

                List<RecipeResultCollection> result = new ArrayList<>();
                Map<Identifier, Integer> counts = new HashMap<>();
                Set<Identifier> containerSet = new HashSet<>();
                Map<RecipeResultCollection, Integer> ranks = new IdentityHashMap<>();

                Map<Item, Integer> combined = checkContainers ? mergeMaps(invSnapshot, contSnapshot) : null;
                Set<Item> containerItemSet = checkContainers ? new HashSet<>(contSnapshot.keySet()) : Set.of();
                Set<Item> sharedInProgress = new HashSet<>();
                Map<Item, Integer> tempInv = new HashMap<>();

                int totalRecipes = 0, mathPathCount = 0, simPathCount = 0;
                int preCheckSkipped = 0, containerChecked = 0;
                long mathTimeNs = 0, simTimeNs = 0, containerTimeNs = 0, preCheckTimeNs = 0;

                // Pre-compute the full set of items reachable from inventory via crafting chains.
                long tReachable = System.nanoTime();
                Set<Item> reachableItems = computeReachableItems(invSnapshot, snapGridSize, bgRegistryManager);
                long reachableMs = (System.nanoTime() - tReachable) / 1_000_000;

                // --- Pass 1: categorize recipes, resolve math/quick, collect sim tasks ---
                int NEEDS_SIM = -1;
                List<RecipeEntry<?>> simEntries = new ArrayList<>();
                List<Integer> simOutputCounts = new ArrayList<>();
                List<List<RecipeEntry<?>>> collAllEntries = new ArrayList<>();
                Map<Identifier, Integer> resolvedCounts = new HashMap<>();
                Map<Identifier, Item> entryOutputItems = new HashMap<>();

                for (RecipeResultCollection coll : allCrafting) {
                    List<RecipeEntry<?>> allEntries = new ArrayList<>();

                    for (RecipeEntry<?> entry : coll.getAllRecipes()) {
                        if (!fitsInGrid(entry, snapGridSize)) continue;
                        ItemStack outputStack = getOutputStack(entry, bgRegistryManager);
                        Item out = outputStack.isEmpty() ? null : outputStack.getItem();
                        if (out != null && recipeConsumesItem(entry, out)) continue;
                        allEntries.add(entry);
                        totalRecipes++;

                        int outputCount = Math.max(1, outputStack.getCount());
                        if (out != null) entryOutputItems.put(entry.id(), out);

                        long tPre = System.nanoTime();
                        boolean canCraft = allIngredientsReachable(entry, reachableItems);
                        preCheckTimeNs += System.nanoTime() - tPre;

                        if (canCraft) {
                            if (ClientCraftConfig.quickCountMode) {
                                tempInv.clear(); tempInv.putAll(invSnapshot);
                                sharedInProgress.clear();
                                int repeats = resolve(entry, tempInv, null, sharedInProgress, 0, null, bgRegistryManager) ? 1 : 0;
                                resolvedCounts.put(entry.id(), repeats > 0 ? outputCount : 0);
                            } else {
                                int maxRepeats = Math.max(1, MAX_REPEATS / outputCount);
                                long tMath = System.nanoTime();
                                int mathResult = tryMathCount(entry, invSnapshot, maxRepeats);
                                mathTimeNs += System.nanoTime() - tMath;

                                if (mathResult >= 0) {
                                    resolvedCounts.put(entry.id(), (int) Math.min((long) mathResult * outputCount, MAX_REPEATS));
                                    mathPathCount++;
                                } else {
                                    // Defer to parallel sim
                                    simEntries.add(entry);
                                    simOutputCounts.add(outputCount);
                                    simPathCount++;
                                }
                            }
                        } else {
                            preCheckSkipped++;
                        }
                    }

                    collAllEntries.add(allEntries);
                }

                // --- Parallel sim phase ---
                long tSim = System.nanoTime();
                Map<Identifier, Integer> simResults = new ConcurrentHashMap<>();
                final Map<Item, Integer> finalInvSnapshot = invSnapshot;
                final DynamicRegistryManager finalRegMgr = bgRegistryManager;
                int simSize = simEntries.size();
                if (simSize > 0) {
                    if (simSize < 16) {
                        // Small batch: sequential is faster (avoids thread overhead)
                        for (int i = 0; i < simSize; i++) {
                            RecipeEntry<?> entry = simEntries.get(i);
                            int oc = simOutputCounts.get(i);
                            int repeats = countRepeats(entry, finalInvSnapshot, finalRegMgr, oc);
                            if (repeats > 0) {
                                simResults.put(entry.id(), (int) Math.min((long) repeats * oc, MAX_REPEATS));
                            }
                        }
                    } else {
                        java.util.stream.IntStream.range(0, simSize).parallel().forEach(i -> {
                            RecipeEntry<?> entry = simEntries.get(i);
                            int oc = simOutputCounts.get(i);
                            int repeats = countRepeatsParallel(entry, finalInvSnapshot, finalRegMgr, oc);
                            if (repeats > 0) {
                                simResults.put(entry.id(), (int) Math.min((long) repeats * oc, MAX_REPEATS));
                            }
                        });
                    }
                }
                simTimeNs = System.nanoTime() - tSim;

                // --- Pass 2: build collections with all results merged ---
                int collIdx = 0;
                for (RecipeResultCollection coll : allCrafting) {
                    List<RecipeEntry<?>> allEntries = collAllEntries.get(collIdx++);
                    if (allEntries.isEmpty()) continue;

                    List<RecipeEntry<?>> craftable = new ArrayList<>();
                    boolean hasDirect = false;
                    boolean hasContainer = false;

                    for (RecipeEntry<?> entry : allEntries) {
                        int finalCount = resolvedCounts.getOrDefault(entry.id(), 0);
                        if (finalCount == 0) finalCount = simResults.getOrDefault(entry.id(), 0);

                        if (finalCount > 0) {
                            craftable.add(entry);
                            hasDirect = true;
                            counts.put(entry.id(), finalCount);
                        } else if (combined != null) {
                            containerChecked++;
                            long tCont = System.nanoTime();
                            tempInv.clear(); tempInv.putAll(combined);
                            sharedInProgress.clear();
                            if (resolve(entry, tempInv, null, sharedInProgress, 0, null, bgRegistryManager)) {
                                containerSet.add(entry.id());
                                craftable.add(entry);
                                hasContainer = true;
                                Item out = entryOutputItems.get(entry.id());
                                if (out != null) containerItemSet.add(out);
                            }
                            containerTimeNs += System.nanoTime() - tCont;
                        }
                    }

                    RecipeResultCollection nc = new RecipeResultCollection(bgRegistryManager, allEntries);
                    RecipeResultCollectionAccessor acc = (RecipeResultCollectionAccessor) nc;
                    for (RecipeEntry<?> e : allEntries) acc.getFittingRecipes().add(e);
                    for (RecipeEntry<?> e : craftable) acc.getCraftableRecipes().add(e);
                    ranks.put(nc, hasDirect ? 0 : hasContainer ? 1 : 2);
                    result.add(nc);
                }

                if (ClientCraftConfig.debugLogging) {
                    long totalMs = (System.nanoTime() - t0) / 1_000_000;
                    LOG.info("[CC] Resolve: {}ms | {} recipes | reachable: {}ms ({} items) | skip:{} math:{} sim:{} cont:{}",
                            totalMs, totalRecipes,
                            reachableMs, reachableItems.size(),
                            preCheckSkipped, mathPathCount, simPathCount, containerChecked);
                    LOG.info("[CC] Timing: preCheck={}ms math={}ms sim={}ms container={}ms",
                            preCheckTimeNs / 1_000_000, mathTimeNs / 1_000_000,
                            simTimeNs / 1_000_000, containerTimeNs / 1_000_000);
                }

                Set<Item> finalContainerItemSet = containerItemSet;
                MinecraftClient.getInstance().execute(() -> {
                    if (recipeIndexDirty || lastRecipeCount != startRecipeCount) {
                        resolving = false;
                        Runnable retry = onResultsPublished;
                        if (retry != null) retry.run();
                        return;
                    }
                    craftCounts = counts;
                    containerCraftable = containerSet;
                    collectionRanks = ranks;
                    autoCraftCollections = new HashSet<>(result);
                    containerAvailableItems = finalContainerItemSet;
                    cachedResults = result;
                    lastCacheKey = snapCacheKey;
                    resolving = false;

                    Runnable callback = onResultsPublished;
                    if (callback != null) callback.run();
                });
            } catch (Exception e) {
                LOG.error("[CC] Background resolve failed", e);
                MinecraftClient.getInstance().execute(() -> resolving = false);
            }
        });

        return cachedResults;
    }

    public static AutoCrafter.CraftPlan buildCraftCyclesForMode(RecipeEntry<?> target, AutoCrafter.Mode mode) {
        long t0 = System.nanoTime();
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return null;
        prepareContext();
        long tPrep = System.nanoTime();

        DynamicRegistryManager registryManager = getRegistryManager();
        if (registryManager == null) return null;

        Map<Item, Integer> available = new HashMap<>(cachedInventory);
        List<RecipeEntry<?>> firstSteps = new ArrayList<>();
        if (!resolve(target, available, firstSteps, new HashSet<>(), 0, null, registryManager)) return null;
        long tFirst = System.nanoTime();

        // A "direct" recipe has exactly 1 step (no sub-crafting required).
        // For ALL mode with direct recipes, use craftAll to fill the grid with
        // full stacks -- like Shift+Click in the vanilla recipe book -- so each
        // click produces up to a full stack of output instead of one craft.
        boolean directCraft = mode == AutoCrafter.Mode.ALL && firstSteps.size() == 1;

        ItemStack output = resolveResult(target);
        int outputCount = Math.max(1, output.getCount());

        int maxRepeats = switch (mode) {
            case ONCE -> 1;
            case STACK -> (output.getMaxCount() + outputCount - 1) / outputCount;
            case ALL -> MAX_REPEATS;
        };
        if (maxRepeats <= 0) return null;

        List<List<RecipeEntry<?>>> cycles = new ArrayList<>();
        cycles.add(firstSteps);

        if (directCraft) {
            // Count how many crafts can be done with direct items only
            int directCount = skipDirectCrafts(target, new HashMap<>(cachedInventory), maxRepeats, registryManager);
            int craftsPerClick = output.getMaxCount() / outputCount;
            int directClicks = Math.max(1, (directCount + craftsPerClick - 1) / craftsPerClick);

            for (int r = 1; r < directClicks; r++) {
                cycles.add(List.of(firstSteps.getFirst()));
            }

            // Deduct direct items from available, then continue with sub-crafting for the rest
            skipDirectCrafts(target, available, directCount, registryManager);
            for (int r = directCount; r < maxRepeats; r++) {
                List<RecipeEntry<?>> steps = new ArrayList<>();
                if (!resolve(target, available, steps, new HashSet<>(), 0, null, registryManager)) break;
                cycles.add(steps);
            }
        } else {
            for (int r = 1; r < maxRepeats; r++) {
                List<RecipeEntry<?>> steps = new ArrayList<>();
                if (!resolve(target, available, steps, new HashSet<>(), 0, null, registryManager)) break;
                cycles.add(steps);
            }
        }
        long tCycles = System.nanoTime();

        // Direct craft flag only applies if ALL cycles are single-step direct
        boolean allDirect = directCraft && cycles.stream().allMatch(c -> c.size() == 1);

        if (ClientCraftConfig.debugLogging) {
            int totalSteps = cycles.stream().mapToInt(List::size).sum();
            LOG.info("[CC] BuildCraft({}): prep={}ms first={}ms cycles={}ms | {} cycles, {} steps, direct={}",
                    mode,
                    (tPrep - t0) / 1_000_000, (tFirst - tPrep) / 1_000_000, (tCycles - tFirst) / 1_000_000,
                    cycles.size(), totalSteps, allDirect);
        }
        return new AutoCrafter.CraftPlan(cycles, allDirect);
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
        prepareContext();
        DefaultedList<Ingredient> ingredients = getIngredients(originalEntry);
        if (ingredients == null || ingredients.isEmpty()) return null;

        DynamicRegistryManager registryManager = getRegistryManager();
        if (registryManager == null) return null;

        IngredientGrid grid = new IngredientGrid();
        Arrays.fill(grid.items, ItemStack.EMPTY);
        fillGrid(grid, originalEntry, ingredients);
        computeGridCraftability(grid);

        activeIngredientGrid = grid;
        return buildFakeCollection(grid, originalEntry, registryManager);
    }

    public static void markRecipesDirty() {
        recipeIndexDirty = true;
        cachedResults = List.of();
        lastCacheKey = 0;
        lastRecipeCount = 0;
    }

    public static void clearCache() {
        resolving = false;
        onResultsPublished = null;
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
        inventoryGeneration = 0;
        lastCacheKey = 0;
        activeIngredientGrid = null;
        lowerCaseNameCache.clear();
        knownTags.clear();
        itemToTags.clear();
        inventoryTagIndex.clear();
        containerTagIndex.clear();
        tagMembersCache.clear();
        tagFallbackItem.clear();
        craftableTagIndex.clear();
        lastInventoryKeySet = Set.of();
        lastContainerKeySet = Set.of();
    }

    // --- Resolution engine ---

    private static boolean resolve(
            RecipeEntry<?> entry, Map<Item, Integer> available,
            List<RecipeEntry<?>> stepsOut, Set<Item> inProgress, int depth,
            Item rootOutput, DynamicRegistryManager registryManager) {
        if (depth > MAX_DEPTH) return false;

        DefaultedList<Ingredient> ingredients = getIngredients(entry);
        if (ingredients == null || ingredients.isEmpty()) return false;

        Item outputItem = getOutputItem(entry, registryManager);
        if (outputItem != null && !inProgress.add(outputItem)) return false;
        if (rootOutput == null) rootOutput = outputItem;

        // Defer full snapshot until sub-crafting is needed. Track direct
        // consumptions cheaply so we can roll back without a HashMap copy
        // when only direct items are used (the common case in simulation).
        Map<Item, Integer> snapshot = null;
        Item[] consumed = null;
        int consumedCount = 0;
        int stepsStart = stepsOut != null ? stepsOut.size() : 0;

        boolean success = true;
        for (Ingredient ingredient : ingredients) {
            if (ingredient.isEmpty()) continue;

            ItemStack resolved = resolveIngredient(ingredient, available);
            if (resolved.isEmpty()) { success = false; break; }
            Item item = resolved.getItem();

            int have = available.getOrDefault(item, 0);
            if (have >= 1) {
                available.put(item, have - 1);
                if (consumed == null) consumed = new Item[ingredients.size()];
                consumed[consumedCount++] = item;
                continue;
            }

            // Sub-crafting needed: take full snapshot, restoring already-consumed items
            if (snapshot == null) {
                snapshot = new HashMap<>(available);
                for (int i = 0; i < consumedCount; i++) snapshot.merge(consumed[i], 1, Integer::sum);
            }

            if (!trySubCraft(item, available, stepsOut, inProgress, depth, rootOutput, registryManager)
                    && !(depth <= 1 && tryIngredientFallback(ingredient, item, available, stepsOut, inProgress, depth, rootOutput, registryManager))) {
                success = false;
                break;
            }
        }

        if (!success) {
            if (snapshot != null) {
                available.clear();
                available.putAll(snapshot);
            } else {
                for (int i = 0; i < consumedCount; i++) available.merge(consumed[i], 1, Integer::sum);
            }
            if (stepsOut != null) while (stepsOut.size() > stepsStart) stepsOut.removeLast();
            if (outputItem != null) inProgress.remove(outputItem);
            return false;
        }

        if (stepsOut != null) stepsOut.add(entry);
        if (outputItem != null) inProgress.remove(outputItem);
        return true;
    }

    /**
     * In 1.21.1, Ingredient already resolves tags internally, so instead of
     * checking SlotDisplay subtypes, we iterate getMatchingStacks() to find
     * alternative items that can satisfy the ingredient.
     */
    private static boolean tryIngredientFallback(
            Ingredient ingredient, Item alreadyTried, Map<Item, Integer> working,
            List<RecipeEntry<?>> stepsOut, Set<Item> inProgress, int depth,
            Item rootOutput, DynamicRegistryManager registryManager) {

        // First: check indexed items already available that match this ingredient
        // Use inventoryTagIndex + craftableTagIndex for O(1) lookups instead of
        // iterating working.entrySet()
        ItemStack[] matchingStacks = ingredient.getMatchingStacks();
        for (ItemStack match : matchingStacks) {
            Item item = match.getItem();
            if (item.equals(alreadyTried)) continue;
            int have = working.getOrDefault(item, 0);
            if (have >= 1) { working.put(item, have - 1); return true; }
        }

        // Also check via tag index for items in the working map
        for (ItemStack match : matchingStacks) {
            Set<TagKey<Item>> tags = computeTagsForItem(match.getItem());
            for (TagKey<Item> tag : tags) {
                Set<Item> invMatches = inventoryTagIndex.get(tag);
                if (invMatches != null) {
                    for (Item item : invMatches) {
                        if (item.equals(alreadyTried)) continue;
                        int have = working.getOrDefault(item, 0);
                        if (have >= 1) { working.put(item, have - 1); return true; }
                    }
                }
                List<Item> craftItems = craftableTagIndex.get(tag);
                if (craftItems != null) {
                    for (Item item : craftItems) {
                        if (item.equals(alreadyTried)) continue;
                        int have = working.getOrDefault(item, 0);
                        if (have >= 1) { working.put(item, have - 1); return true; }
                    }
                }
            }
        }

        // Second: try sub-crafting alternatives from the ingredient's matching stacks
        Set<Item> triedCraft = new HashSet<>();
        triedCraft.add(alreadyTried);
        for (ItemStack match : matchingStacks) {
            Item matchItem = match.getItem();
            if (triedCraft.contains(matchItem)) continue;
            triedCraft.add(matchItem);
            if (trySubCraft(matchItem, working, stepsOut, inProgress, depth, rootOutput, registryManager)) return true;
        }

        // Third: try items from craftableTagIndex for tags this ingredient's items belong to
        for (ItemStack match : matchingStacks) {
            Set<TagKey<Item>> tags = computeTagsForItem(match.getItem());
            for (TagKey<Item> tag : tags) {
                List<Item> craftable = craftableTagIndex.get(tag);
                if (craftable == null) continue;
                for (Item alt : craftable) {
                    if (triedCraft.contains(alt)) continue;
                    triedCraft.add(alt);
                    if (trySubCraft(alt, working, stepsOut, inProgress, depth, rootOutput, registryManager)) return true;
                }
            }
        }

        return false;
    }

    private static boolean trySubCraft(
            Item item, Map<Item, Integer> working, List<RecipeEntry<?>> stepsOut,
            Set<Item> inProgress, int depth, Item rootOutput,
            DynamicRegistryManager registryManager) {
        List<RecipeEntry<?>> subs = recipesByOutput.get(item);
        if (subs == null) return false;
        for (int i = 0, len = subs.size(); i < len; i++) {
            RecipeEntry<?> sub = subs.get(i);
            if (!fitsInGrid(sub, currentGridSize)) continue;
            int subOutput = getOutputCount(sub, registryManager);
            if (subOutput <= 0) continue;
            if (rootOutput != null && recipeConsumesItem(sub, rootOutput)) continue;

            // resolve() snapshots and rolls back working/stepsOut on failure,
            // so we can pass them directly instead of copying.
            if (resolve(sub, working, stepsOut, inProgress, depth + 1, rootOutput, registryManager)) {
                working.merge(item, subOutput - 1, Integer::sum);
                return true;
            }
        }
        return false;
    }

    // --- Repeat counting ---

    // Pre-allocated working maps for countRepeats (resolver is single-threaded)
    private static final Map<Item, Integer> simMap = new HashMap<>();
    private static final Map<Item, Integer> beforeMap = new HashMap<>();
    private static final Map<Item, Integer> deltaMap = new HashMap<>();
    private static final Set<Item> simInProgress = new HashSet<>();
    private static final Map<Object, Integer> sdcNeeded = new HashMap<>();
    private static final Map<Object, Integer> sdcAvail = new HashMap<>();

    private static int countRepeats(RecipeEntry<?> target, Map<Item, Integer> inventory,
                                    DynamicRegistryManager registryManager, int outputCount) {
        int maxRepeats = Math.max(1, MAX_REPEATS / outputCount);

        simMap.clear();
        simMap.putAll(inventory);
        int count = 0;
        while (count < maxRepeats) {
            // Batch direct-only iterations (items already in sim, no sub-crafting)
            int direct = skipDirectCraftsLocal(target, simMap, maxRepeats - count, sdcNeeded, sdcAvail, registryManager);
            if (direct > 0) {
                count += direct;
                continue;
            }

            // Resolve once with sub-crafting, then extrapolate the cost
            beforeMap.clear();
            beforeMap.putAll(simMap);
            simInProgress.clear();
            if (!resolve(target, simMap, null, simInProgress, 0, null, registryManager)) break;
            count++;

            // Compute full delta: both consumed items (negative) and surplus produced (positive).
            // Extrapolate by applying this delta repeatedly until any item would go negative.
            deltaMap.clear();
            for (var e : beforeMap.entrySet()) {
                int d = simMap.getOrDefault(e.getKey(), 0) - e.getValue();
                if (d != 0) deltaMap.put(e.getKey(), d);
            }
            for (var e : simMap.entrySet()) {
                if (!beforeMap.containsKey(e.getKey()) && e.getValue() != 0) {
                    deltaMap.put(e.getKey(), e.getValue());
                }
            }

            int maxMore = maxRepeats - count;
            for (var e : deltaMap.entrySet()) {
                if (e.getValue() < 0) {
                    int remaining = simMap.getOrDefault(e.getKey(), 0);
                    maxMore = Math.min(maxMore, remaining / (-e.getValue()));
                }
            }
            if (maxMore > 0) {
                for (var e : deltaMap.entrySet()) {
                    simMap.merge(e.getKey(), maxMore * e.getValue(), Integer::sum);
                }
                count += maxMore;
            }
        }
        return count;
    }

    /** Thread-safe variant of countRepeats that allocates all working maps locally. */
    private static int countRepeatsParallel(RecipeEntry<?> target, Map<Item, Integer> inventory,
                                            DynamicRegistryManager registryManager, int outputCount) {
        int maxRepeats = Math.max(1, MAX_REPEATS / outputCount);

        Map<Item, Integer> sim = new HashMap<>(inventory);
        Map<Item, Integer> before = new HashMap<>();
        Map<Item, Integer> delta = new HashMap<>();
        Set<Item> inProgress = new HashSet<>();
        Map<Object, Integer> needed = new HashMap<>();
        Map<Object, Integer> avail = new HashMap<>();
        int count = 0;
        while (count < maxRepeats) {
            int direct = skipDirectCraftsLocal(target, sim, maxRepeats - count, needed, avail, registryManager);
            if (direct > 0) {
                count += direct;
                continue;
            }

            before.clear();
            before.putAll(sim);
            inProgress.clear();
            if (!resolve(target, sim, null, inProgress, 0, null, registryManager)) break;
            count++;

            delta.clear();
            for (var e : before.entrySet()) {
                int d = sim.getOrDefault(e.getKey(), 0) - e.getValue();
                if (d != 0) delta.put(e.getKey(), d);
            }
            for (var e : sim.entrySet()) {
                if (!before.containsKey(e.getKey()) && e.getValue() != 0) {
                    delta.put(e.getKey(), e.getValue());
                }
            }

            int maxMore = maxRepeats - count;
            for (var e : delta.entrySet()) {
                if (e.getValue() < 0) {
                    int remaining = sim.getOrDefault(e.getKey(), 0);
                    maxMore = Math.min(maxMore, remaining / (-e.getValue()));
                }
            }
            if (maxMore > 0) {
                for (var e : delta.entrySet()) {
                    sim.merge(e.getKey(), maxMore * e.getValue(), Integer::sum);
                }
                count += maxMore;
            }
        }
        return count;
    }

    /**
     * Computes how many crafts can be done with items currently in sim (no sub-crafting),
     * deducts the consumed items, and returns the count.
     */
    private static int skipDirectCrafts(RecipeEntry<?> target, Map<Item, Integer> sim, int maxRepeats,
                                        DynamicRegistryManager registryManager) {
        DefaultedList<Ingredient> ingredients = getIngredients(target);
        if (ingredients == null || maxRepeats <= 0) return 0;

        Map<Object, Integer> needed = new HashMap<>();
        Map<Object, Integer> avail = new HashMap<>();

        for (Ingredient ingredient : ingredients) {
            if (ingredient.isEmpty()) continue;
            ItemStack[] matching = ingredient.getMatchingStacks();
            if (matching.length == 0) return 0;

            if (matching.length == 1) {
                Item item = matching[0].getItem();
                if (sim.getOrDefault(item, 0) <= 0) return 0;
                needed.merge(item, 1, Integer::sum);
                avail.putIfAbsent(item, sim.getOrDefault(item, 0));
            } else {
                // Tag-like: use sumTagInventory for known tags, or sum matching items
                String key = ingredientKey(matching);
                needed.merge(key, 1, Integer::sum);
                if (!avail.containsKey(key)) {
                    int total = 0;
                    for (ItemStack stack : matching) {
                        total += sim.getOrDefault(stack.getItem(), 0);
                    }
                    avail.put(key, total);
                }
            }
        }

        int maxCrafts = maxRepeats;
        for (var e : needed.entrySet()) {
            int crafts = avail.getOrDefault(e.getKey(), 0) / e.getValue();
            if (crafts < maxCrafts) maxCrafts = crafts;
        }
        if (maxCrafts <= 0) return 0;

        // Deduct consumed items from sim
        for (var e : needed.entrySet()) {
            int toConsume = maxCrafts * e.getValue();
            if (e.getKey() instanceof Item item) {
                sim.merge(item, -toConsume, Integer::sum);
            } else if (e.getKey() instanceof String) {
                // Find matching ingredient and consume from its matching stacks
                for (Ingredient ingredient : ingredients) {
                    if (ingredient.isEmpty()) continue;
                    ItemStack[] matching = ingredient.getMatchingStacks();
                    if (matching.length > 1 && ingredientKey(matching).equals(e.getKey())) {
                        for (ItemStack stack : matching) {
                            Item m = stack.getItem();
                            int have = sim.getOrDefault(m, 0);
                            if (have <= 0) continue;
                            int take = Math.min(have, toConsume);
                            sim.merge(m, -take, Integer::sum);
                            toConsume -= take;
                            if (toConsume <= 0) break;
                        }
                        break;
                    }
                }
            }
        }
        return maxCrafts;
    }

    /** Thread-safe variant of skipDirectCrafts using pre-supplied working maps. */
    private static int skipDirectCraftsLocal(RecipeEntry<?> target, Map<Item, Integer> sim, int maxRepeats,
                                             Map<Object, Integer> needed, Map<Object, Integer> avail,
                                             DynamicRegistryManager registryManager) {
        DefaultedList<Ingredient> ingredients = getIngredients(target);
        if (ingredients == null || maxRepeats <= 0) return 0;

        needed.clear();
        avail.clear();

        for (Ingredient ingredient : ingredients) {
            if (ingredient.isEmpty()) continue;
            ItemStack[] matching = ingredient.getMatchingStacks();
            if (matching.length == 0) return 0;

            if (matching.length == 1) {
                Item item = matching[0].getItem();
                if (sim.getOrDefault(item, 0) <= 0) return 0;
                needed.merge(item, 1, Integer::sum);
                avail.putIfAbsent(item, sim.getOrDefault(item, 0));
            } else {
                String key = ingredientKey(matching);
                needed.merge(key, 1, Integer::sum);
                if (!avail.containsKey(key)) {
                    int total = 0;
                    for (ItemStack stack : matching) {
                        total += sim.getOrDefault(stack.getItem(), 0);
                    }
                    avail.put(key, total);
                }
            }
        }

        int maxCrafts = maxRepeats;
        for (var e : needed.entrySet()) {
            int crafts = avail.getOrDefault(e.getKey(), 0) / e.getValue();
            if (crafts < maxCrafts) maxCrafts = crafts;
        }
        if (maxCrafts <= 0) return 0;

        for (var e : needed.entrySet()) {
            int toConsume = maxCrafts * e.getValue();
            if (e.getKey() instanceof Item item) {
                sim.merge(item, -toConsume, Integer::sum);
            } else if (e.getKey() instanceof String) {
                for (Ingredient ingredient : ingredients) {
                    if (ingredient.isEmpty()) continue;
                    ItemStack[] matching = ingredient.getMatchingStacks();
                    if (matching.length > 1 && ingredientKey(matching).equals(e.getKey())) {
                        for (ItemStack stack : matching) {
                            Item m = stack.getItem();
                            int have = sim.getOrDefault(m, 0);
                            if (have <= 0) continue;
                            int take = Math.min(have, toConsume);
                            sim.merge(m, -take, Integer::sum);
                            toConsume -= take;
                            if (toConsume <= 0) break;
                        }
                        break;
                    }
                }
            }
        }
        return maxCrafts;
    }

    private static int tryMathCount(RecipeEntry<?> target, Map<Item, Integer> inventory, int maxRepeats) {
        DefaultedList<Ingredient> ingredients = getIngredients(target);
        if (ingredients == null) return -1;

        // Track needed counts keyed by Item (single-match) or a stable String key (multi-match/tag)
        Map<Object, Integer> needed = new HashMap<>();
        Map<Object, Integer> available = new HashMap<>();
        Set<Object> multiItemKeys = null;

        for (Ingredient ingredient : ingredients) {
            if (ingredient.isEmpty()) continue;
            ItemStack[] matching = ingredient.getMatchingStacks();
            if (matching.length == 0) return -1;

            if (matching.length == 1) {
                Item item = matching[0].getItem();
                if (inventory.getOrDefault(item, 0) <= 0) return -1;
                needed.merge(item, 1, Integer::sum);
                available.putIfAbsent(item, inventory.getOrDefault(item, 0));
            } else {
                // Multi-match (tag-like): group by sorted matching items, sum inventory
                String key = ingredientKey(matching);
                needed.merge(key, 1, Integer::sum);
                if (!available.containsKey(key)) {
                    int total = 0;
                    for (ItemStack stack : matching) {
                        total += inventory.getOrDefault(stack.getItem(), 0);
                    }
                    available.put(key, total);
                }
                if (multiItemKeys == null) multiItemKeys = new HashSet<>();
                multiItemKeys.add(key);
            }
        }

        if (needed.isEmpty()) return 0;

        // Check overlap: a specific item is also a member of a multi-match group
        if (multiItemKeys != null) {
            for (var e : needed.entrySet()) {
                if (e.getKey() instanceof Item item) {
                    String itemStr = item.toString();
                    for (Object key : multiItemKeys) {
                        if (((String) key).contains(itemStr)) return -1;
                    }
                }
            }
        }

        int maxCrafts = maxRepeats;
        for (var e : needed.entrySet()) {
            int avail = available.getOrDefault(e.getKey(), 0);
            int crafts = avail / e.getValue();
            if (crafts < maxCrafts) maxCrafts = crafts;
        }

        // Only bail if the bottleneck can actually be sub-crafted with current inventory
        for (var e : needed.entrySet()) {
            int avail = available.getOrDefault(e.getKey(), 0);
            if (avail / e.getValue() != maxCrafts) continue;
            if (e.getKey() instanceof Item item && canSubCraftMore(item, inventory)) return -1;
            if (e.getKey() instanceof String) {
                // Check if any item in this tag group is sub-craftable
                if (multiItemKeys != null && multiItemKeys.contains(e.getKey())) {
                    for (Ingredient ingredient : ingredients) {
                        if (ingredient.isEmpty()) continue;
                        ItemStack[] matching = ingredient.getMatchingStacks();
                        if (matching.length > 1 && ingredientKey(matching).equals(e.getKey())) {
                            boolean anyCanCraft = false;
                            for (ItemStack stack : matching) {
                                if (canSubCraftMore(stack.getItem(), inventory)) { anyCanCraft = true; break; }
                            }
                            if (anyCanCraft) return -1;
                            break;
                        }
                    }
                }
            }
        }
        return maxCrafts;
    }

    private static String ingredientKey(ItemStack[] matching) {
        if (matching.length == 1) return matching[0].getItem().toString();
        List<String> names = new ArrayList<>(matching.length);
        for (ItemStack stack : matching) names.add(stack.getItem().toString());
        Collections.sort(names);
        return String.join(",", names);
    }

    /** Quick O(ingredients) check: can this recipe possibly be crafted? */
    private static boolean canCraftAtAll(RecipeEntry<?> entry, Map<Item, Integer> inventory,
                                         DynamicRegistryManager registryManager) {
        DefaultedList<Ingredient> ingredients = getIngredients(entry);
        if (ingredients == null) return false;
        for (Ingredient ingredient : ingredients) {
            if (ingredient.isEmpty()) continue;
            ItemStack resolved = resolveIngredient(ingredient, inventory);
            if (resolved.isEmpty()) return false;
            Item item = resolved.getItem();
            if (inventory.getOrDefault(item, 0) > 0) continue;
            if (recipesByOutput.containsKey(item)) continue;
            // Check if any matching alternative is available or craftable
            ItemStack[] matching = ingredient.getMatchingStacks();
            boolean found = false;
            for (ItemStack stack : matching) {
                Item alt = stack.getItem();
                if (inventory.getOrDefault(alt, 0) > 0 || recipesByOutput.containsKey(alt)) {
                    found = true;
                    break;
                }
            }
            if (!found) return false;
        }
        return true;
    }

    /**
     * Fixed-point closure: starting from items in inventory, repeatedly add items
     * whose recipes can be fully satisfied from the set, until no new items appear.
     * Returns the complete set of items reachable via crafting chains.
     */
    private static Set<Item> computeReachableItems(Map<Item, Integer> inventory, int gridSize,
                                                    DynamicRegistryManager registryManager) {
        Set<Item> reachable = new HashSet<>(inventory.keySet());
        // Also include items reachable via tag membership (if oak_planks is reachable, birch_planks
        // isn't automatically, but any tag containing oak_planks is "satisfiable")
        boolean changed = true;
        while (changed) {
            changed = false;
            for (var entry : recipesByOutput.entrySet()) {
                Item output = entry.getKey();
                if (reachable.contains(output)) continue;
                for (RecipeEntry<?> recipe : entry.getValue()) {
                    if (!fitsInGrid(recipe, gridSize)) continue;
                    if (allIngredientsReachable(recipe, reachable)) {
                        reachable.add(output);
                        changed = true;
                        break;
                    }
                }
            }
        }
        return reachable;
    }

    /** Returns true if every non-empty ingredient has at least one option in the reachable set. */
    private static boolean allIngredientsReachable(RecipeEntry<?> entry, Set<Item> reachable) {
        DefaultedList<Ingredient> ingredients = getIngredients(entry);
        if (ingredients == null) return false;
        for (Ingredient ingredient : ingredients) {
            if (ingredient.isEmpty()) continue;
            if (!ingredientReachable(ingredient, reachable)) return false;
        }
        return true;
    }

    private static boolean ingredientReachable(Ingredient ingredient, Set<Item> reachable) {
        for (ItemStack match : ingredient.getMatchingStacks()) {
            if (reachable.contains(match.getItem())) return true;
        }
        return false;
    }

    /** Can the player sub-craft more of this item using only items directly in inventory? */
    private static boolean canSubCraftMore(Item item, Map<Item, Integer> inventory) {
        List<RecipeEntry<?>> subs = recipesByOutput.get(item);
        if (subs == null) return false;
        for (RecipeEntry<?> sub : subs) {
            if (hasDirectIngredients(sub, inventory)) return true;
        }
        return false;
    }

    /** Returns true if every ingredient of this recipe is directly in inventory (no sub-crafting). */
    private static boolean hasDirectIngredients(RecipeEntry<?> entry, Map<Item, Integer> inventory) {
        DefaultedList<Ingredient> ingredients = getIngredients(entry);
        if (ingredients == null) return false;
        for (Ingredient ingredient : ingredients) {
            if (ingredient.isEmpty()) continue;
            boolean found = false;
            for (ItemStack match : ingredient.getMatchingStacks()) {
                if (inventory.getOrDefault(match.getItem(), 0) > 0) { found = true; break; }
            }
            if (!found) return false;
        }
        return true;
    }

    // --- Recipe helpers ---

    private static boolean recipeConsumesItem(RecipeEntry<?> entry, Item target) {
        DefaultedList<Ingredient> ingredients = getIngredients(entry);
        if (ingredients == null) return false;
        for (Ingredient ingredient : ingredients) {
            if (ingredient.isEmpty()) continue;
            // Only flag as consuming when the target is the sole matching item.
            // Tag-based ingredients have alternatives; the inProgress set in
            // resolve() already prevents true circular dependencies.
            ItemStack[] matching = ingredient.getMatchingStacks();
            if (matching.length == 1 && matching[0].getItem().equals(target)) return true;
        }
        return false;
    }

    private static DefaultedList<Ingredient> getIngredients(RecipeEntry<?> entry) {
        Recipe<?> recipe = entry.value();
        if (recipe instanceof ShapedRecipe || recipe instanceof ShapelessRecipe) {
            return recipe.getIngredients();
        }
        return null;
    }

    private static boolean fitsInGrid(RecipeEntry<?> entry, int gridSize) {
        Recipe<?> recipe = entry.value();
        if (recipe instanceof ShapedRecipe shaped) {
            return shaped.getWidth() <= gridSize && shaped.getHeight() <= gridSize;
        } else if (recipe instanceof ShapelessRecipe) {
            DefaultedList<Ingredient> ingredients = recipe.getIngredients();
            int count = 0;
            for (int i = 0, len = ingredients.size(); i < len; i++) {
                if (!ingredients.get(i).isEmpty()) count++;
            }
            return count <= gridSize * gridSize;
        }
        return false;
    }

    private static Item getOutputItem(RecipeEntry<?> entry, DynamicRegistryManager registryManager) {
        ItemStack out = entry.value().getResult(registryManager);
        return out.isEmpty() ? null : out.getItem();
    }

    private static ItemStack getOutputStack(RecipeEntry<?> entry, DynamicRegistryManager registryManager) {
        return entry.value().getResult(registryManager);
    }

    private static int getOutputCount(RecipeEntry<?> entry, DynamicRegistryManager registryManager) {
        ItemStack out = entry.value().getResult(registryManager);
        return out.isEmpty() ? 0 : out.getCount();
    }

    // --- Ingredient resolution ---

    /**
     * Resolves an Ingredient to a single ItemStack, preferring items the player
     * already has in inventory, then craftable items, then any matching item.
     */
    private static ItemStack resolveIngredient(Ingredient ingredient, Map<Item, Integer> inventory) {
        if (ingredient.isEmpty()) return ItemStack.EMPTY;

        ItemStack[] matchingStacks = ingredient.getMatchingStacks();
        if (matchingStacks.length == 0) return ItemStack.EMPTY;

        // 1. Check inventory items matching this ingredient
        for (ItemStack match : matchingStacks) {
            if (inventory.getOrDefault(match.getItem(), 0) > 0) return match;
        }

        // 2. Check working map (resolve() decrements counts into working copies)
        if (inventory != cachedInventory) {
            for (ItemStack match : matchingStacks) {
                if (inventory.getOrDefault(match.getItem(), 0) > 0) return match;
            }
        }

        // 3. Return a sub-craftable item so trySubCraft() can handle it
        for (ItemStack match : matchingStacks) {
            if (recipesByOutput.containsKey(match.getItem())) return match;
        }

        // 4. Fallback: return first matching stack
        return matchingStacks[0];
    }

    // --- Ingredient grid building ---

    private static void fillGrid(IngredientGrid grid, RecipeEntry<?> entry, DefaultedList<Ingredient> ingredients) {
        Recipe<?> recipe = entry.value();
        if (recipe instanceof ShapedRecipe shaped) {
            int w = shaped.getWidth(), h = shaped.getHeight();
            for (int row = 0; row < h; row++) for (int col = 0; col < w; col++) {
                int srcIdx = row * w + col;
                if (srcIdx >= ingredients.size()) continue;
                Ingredient ingredient = ingredients.get(srcIdx);
                if (ingredient.isEmpty()) continue;
                ItemStack resolved = resolveGridIngredient(ingredient);
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
                ItemStack resolved = resolveGridIngredient(ingredient);
                if (!resolved.isEmpty()) {
                    grid.items[idx] = resolved;
                    grid.slots[idx] = ingredient;
                }
                idx++;
            }
        }
    }

    private static ItemStack resolveGridIngredient(Ingredient ingredient) {
        // First try: item already in inventory
        ItemStack direct = resolveIngredient(ingredient, cachedInventory);
        if (!direct.isEmpty() && cachedInventory.getOrDefault(direct.getItem(), 0) > 0) return direct;

        // Second try: find an item we can sub-craft that satisfies this ingredient
        ItemStack craftable = findCraftableForIngredient(ingredient);
        if (craftable != null) return craftable;

        // Fallback: return whatever resolveIngredient gives
        return direct.isEmpty() ? resolveIngredient(ingredient, cachedInventory) : direct;
    }

    private static ItemStack findCraftableForIngredient(Ingredient ingredient) {
        ItemStack[] matchingStacks = ingredient.getMatchingStacks();

        // Check if any matching item is in inventory
        ItemStack fallback = ItemStack.EMPTY;
        for (ItemStack match : matchingStacks) {
            if (fallback.isEmpty()) fallback = match;
            if (cachedInventory.getOrDefault(match.getItem(), 0) > 0) return match;
        }

        // Check if any matching item can be sub-crafted
        for (ItemStack match : matchingStacks) {
            if (canSubCraft(match.getItem(), cachedInventory)) return match;
        }

        // Also check craftableTagIndex for tag-based alternatives
        for (ItemStack match : matchingStacks) {
            Set<TagKey<Item>> tags = computeTagsForItem(match.getItem());
            for (TagKey<Item> tag : tags) {
                List<Item> craftable = craftableTagIndex.get(tag);
                if (craftable != null) {
                    for (Item item : craftable) {
                        if (ingredient.test(new ItemStack(item)) && canSubCraft(item, cachedInventory)) {
                            return new ItemStack(item);
                        }
                    }
                }
            }
        }

        if (!fallback.isEmpty()) return fallback;
        return null;
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
        DynamicRegistryManager registryManager = getRegistryManager();
        if (registryManager == null) return false;
        List<RecipeEntry<?>> subs = recipesByOutput.get(item);
        if (subs == null) return false;
        for (RecipeEntry<?> sub : subs) {
            if (!fitsInGrid(sub, currentGridSize)) continue;
            Map<Item, Integer> temp = new HashMap<>(available);
            if (resolve(sub, temp, null, new HashSet<>(), 0, null, registryManager)) return true;
        }
        return false;
    }

    /**
     * Finds an item from the given set that can satisfy the given ingredient.
     * In 1.21.1, we use Ingredient.test() and getMatchingStacks() instead of
     * SlotDisplay subtype checks.
     */
    private static Item findInSet(Ingredient ingredient, Item resolved, Set<Item> items) {
        if (items.contains(resolved)) return resolved;
        if (ingredient == null) return null;

        // Check matching stacks against the set
        for (ItemStack match : ingredient.getMatchingStacks()) {
            if (items.contains(match.getItem())) return match.getItem();
        }

        // Check via tag index
        for (ItemStack match : ingredient.getMatchingStacks()) {
            Set<TagKey<Item>> tags = computeTagsForItem(match.getItem());
            for (TagKey<Item> tag : tags) {
                Set<Item> contMatches = containerTagIndex.get(tag);
                if (contMatches != null) {
                    for (Item item : contMatches) if (items.contains(item)) return item;
                }
                List<Item> members = getOrComputeTagMembers(tag);
                if (members != null) {
                    for (Item member : members) if (items.contains(member)) return member;
                }
            }
        }

        return null;
    }

    private static RecipeResultCollection buildFakeCollection(IngredientGrid grid, RecipeEntry<?> originalEntry,
                                                              DynamicRegistryManager registryManager) {
        List<RecipeEntry<?>> entries = new ArrayList<>(9);
        for (int i = 0; i < 9; i++) {
            ItemStack ingredientStack = grid.items[i];
            Identifier fakeId = Identifier.of("clientcraftmk4", "fake_" + i);

            // Build a minimal ShapelessRecipe whose output is the ingredient item
            DefaultedList<Ingredient> fakeIngredients = DefaultedList.ofSize(1, Ingredient.EMPTY);
            if (!ingredientStack.isEmpty()) {
                fakeIngredients = DefaultedList.copyOf(Ingredient.EMPTY, Ingredient.ofStacks(ingredientStack));
            }
            ShapelessRecipe fakeRecipe = new ShapelessRecipe(
                    "",
                    CraftingRecipeCategory.MISC,
                    ingredientStack.isEmpty() ? ItemStack.EMPTY : ingredientStack.copy(),
                    fakeIngredients
            );
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
