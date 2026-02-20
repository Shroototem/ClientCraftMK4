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

    public static void setOnResultsPublished(Runnable callback) { onResultsPublished = callback; }
    public static boolean lastTabWasClientCraft = false;

    private static Map<Item, List<RecipeEntry<?>>> recipesByOutput = Map.of();
    private static boolean recipeIndexDirty = true;
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
            inventorySnapshotTick = tick;
            if (!resolving && (!snapshot.equals(cachedInventory) || !containerSnapshot.equals(cachedContainerInventory))) {
                cachedInventory = snapshot;
                cachedContainerInventory = containerSnapshot;
                rebuildInventoryTagIndex();
                inventoryGeneration++;
            }
        }
        return cachedInventory;
    }

    private static void ensureIndex() {
        if (!recipeIndexDirty && !recipesByOutput.isEmpty()) return;
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return;
        DynamicRegistryManager registryManager = getRegistryManager();
        if (registryManager == null) return;
        Map<Item, List<RecipeEntry<?>>> index = new HashMap<>();
        knownTags.clear();
        for (RecipeResultCollection coll : client.player.getRecipeBook().getResultsForGroup(RecipeBookGroup.CRAFTING_SEARCH)) {
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
        recipeIndexDirty = false;
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

    private static ItemStack getAnyTagMember(TagKey<Item> tag) {
        Item cached = tagFallbackItem.get(tag);
        if (cached != null) return new ItemStack(cached);
        List<Item> members = getOrComputeTagMembers(tag);
        if (members == null || members.isEmpty()) return ItemStack.EMPTY;
        tagFallbackItem.put(tag, members.getFirst());
        return new ItemStack(members.getFirst());
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

        RESOLVER_EXECUTOR.submit(() -> {
            try {
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

                for (RecipeResultCollection coll : allCrafting) {
                    List<RecipeEntry<?>> allEntries = new ArrayList<>();
                    List<RecipeEntry<?>> craftable = new ArrayList<>();
                    boolean hasDirect = false;
                    boolean hasContainer = false;

                    for (RecipeEntry<?> entry : coll.getAllRecipes()) {
                        if (!fitsInGrid(entry, snapGridSize)) continue;
                        ItemStack outputStack = getOutputStack(entry, bgRegistryManager);
                        Item out = outputStack.isEmpty() ? null : outputStack.getItem();
                        if (out != null && recipeConsumesItem(entry, out)) continue;
                        allEntries.add(entry);

                        int outputCount = Math.max(1, outputStack.getCount());
                        int repeats = countRepeats(entry, invSnapshot, bgRegistryManager, outputCount);
                        if (repeats > 0) {
                            craftable.add(entry);
                            hasDirect = true;
                            counts.put(entry.id(), Math.min(repeats * outputCount, MAX_REPEATS));
                        } else if (combined != null) {
                            Map<Item, Integer> temp = new HashMap<>(combined);
                            if (resolve(entry, temp, null, new HashSet<>(), 0, null, bgRegistryManager)) {
                                containerSet.add(entry.id());
                                craftable.add(entry);
                                hasContainer = true;
                                if (out != null) containerItemSet.add(out);
                            }
                        }
                    }

                    if (!allEntries.isEmpty()) {
                        RecipeResultCollection nc = new RecipeResultCollection(bgRegistryManager, allEntries);
                        RecipeResultCollectionAccessor acc = (RecipeResultCollectionAccessor) nc;
                        for (RecipeEntry<?> e : allEntries) acc.getFittingRecipes().add(e);
                        for (RecipeEntry<?> e : craftable) acc.getCraftableRecipes().add(e);
                        ranks.put(nc, hasDirect ? 0 : hasContainer ? 1 : 2);
                        result.add(nc);
                    }
                }

                Set<Item> finalContainerItemSet = containerItemSet;
                MinecraftClient.getInstance().execute(() -> {
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

    public static List<List<RecipeEntry<?>>> buildCraftCyclesForMode(RecipeEntry<?> target, AutoCrafter.Mode mode) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return null;
        prepareContext();

        DynamicRegistryManager registryManager = getRegistryManager();
        if (registryManager == null) return null;

        Map<Item, Integer> available = new HashMap<>(cachedInventory);
        List<RecipeEntry<?>> firstSteps = new ArrayList<>();
        if (!resolve(target, available, firstSteps, new HashSet<>(), 0, null, registryManager)) return null;

        ItemStack output = resolveResult(target);
        int outputCount = output.getCount();

        int maxRepeats = switch (mode) {
            case ONCE -> 1;
            case STACK -> (output.getMaxCount() + outputCount - 1) / outputCount;
            case ALL -> MAX_REPEATS;
        };
        if (maxRepeats <= 0) return null;

        List<List<RecipeEntry<?>>> cycles = new ArrayList<>();
        cycles.add(firstSteps);

        for (int r = 1; r < maxRepeats; r++) {
            List<RecipeEntry<?>> steps = new ArrayList<>();
            if (!resolve(target, available, steps, new HashSet<>(), 0, null, registryManager)) break;
            cycles.add(steps);
        }
        return cycles;
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

        Map<Item, Integer> snapshot = new HashMap<>(available);
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
                continue;
            }

            if (!trySubCraft(item, available, stepsOut, inProgress, depth, rootOutput, registryManager)
                    && !(depth <= 1 && tryIngredientFallback(ingredient, item, available, stepsOut, inProgress, depth, rootOutput, registryManager))) {
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

        // First: check working map for alternative items that match this ingredient
        for (Map.Entry<Item, Integer> e : working.entrySet()) {
            if (e.getKey().equals(alreadyTried)) continue;
            if (e.getValue() >= 1 && ingredient.test(new ItemStack(e.getKey()))) {
                working.put(e.getKey(), e.getValue() - 1);
                return true;
            }
        }

        // Second: try sub-crafting alternatives from the ingredient's matching stacks
        // Check items that match this ingredient and are in craftableTagIndex
        ItemStack[] matchingStacks = ingredient.getMatchingStacks();
        // Try items we can find in craftableTagIndex via their tags
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

            Map<Item, Integer> temp = new HashMap<>(working);
            List<RecipeEntry<?>> tempSteps = stepsOut != null ? new ArrayList<>() : null;

            if (resolve(sub, temp, tempSteps, inProgress, depth + 1, rootOutput, registryManager)) {
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

    private static int countRepeats(RecipeEntry<?> target, Map<Item, Integer> inventory,
                                    DynamicRegistryManager registryManager, int outputCount) {
        int maxRepeats = Math.max(1, MAX_REPEATS / outputCount);
        int mathCount = tryMathCount(target, inventory, maxRepeats);
        if (mathCount >= 0) return mathCount;

        Map<Item, Integer> sim = new HashMap<>(inventory);
        int count = 0;
        while (count < maxRepeats) {
            if (!resolve(target, sim, null, new HashSet<>(), 0, null, registryManager)) break;
            count++;
        }
        return count;
    }

    private static int tryMathCount(RecipeEntry<?> target, Map<Item, Integer> inventory, int maxRepeats) {
        DefaultedList<Ingredient> ingredients = getIngredients(target);
        if (ingredients == null) return -1;

        Map<Item, Integer> needed = new HashMap<>();
        for (Ingredient ingredient : ingredients) {
            if (ingredient.isEmpty()) continue;
            ItemStack resolved = resolveIngredient(ingredient, inventory);
            if (resolved.isEmpty()) return -1;
            Item item = resolved.getItem();
            if (inventory.getOrDefault(item, 0) <= 0) return -1;
            needed.merge(item, 1, Integer::sum);
        }

        if (needed.isEmpty()) return 0;
        int maxCrafts = maxRepeats;
        for (var e : needed.entrySet()) {
            int crafts = inventory.getOrDefault(e.getKey(), 0) / e.getValue();
            if (crafts < maxCrafts) maxCrafts = crafts;
        }
        // If the bottleneck ingredient can be sub-crafted, bail to simulation
        for (var e : needed.entrySet()) {
            if (inventory.getOrDefault(e.getKey(), 0) / e.getValue() == maxCrafts
                    && recipesByOutput.containsKey(e.getKey())) {
                return -1;
            }
        }
        return maxCrafts;
    }

    // --- Recipe helpers ---

    private static boolean recipeConsumesItem(RecipeEntry<?> entry, Item target) {
        DefaultedList<Ingredient> ingredients = getIngredients(entry);
        if (ingredients == null) return false;
        for (Ingredient ingredient : ingredients) {
            ItemStack resolved = resolveIngredient(ingredient, cachedInventory);
            if (!resolved.isEmpty() && resolved.getItem().equals(target)) return true;
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
