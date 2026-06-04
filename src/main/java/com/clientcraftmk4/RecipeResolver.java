package com.clientcraftmk4;

import com.clientcraftmk4.mixin.RecipeResultCollectionAccessor;
import com.clientcraftmk4.tree.CraftCalculator;
import com.clientcraftmk4.tree.CraftedItem;
import com.clientcraftmk4.tree.IngredientEdge;
import com.clientcraftmk4.tree.IngredientOption;
import com.clientcraftmk4.tree.RecipeTree;
import com.clientcraftmk4.tree.RecipeTreeBuilder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.CraftingScreen;
import net.minecraft.client.gui.screens.recipebook.RecipeCollection;
import net.minecraft.client.ClientRecipeBook;
import net.minecraft.client.gui.screens.recipebook.SearchRecipeBookCategory;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.component.BundleContents;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.display.RecipeDisplayId;
import net.minecraft.world.item.crafting.display.RecipeDisplayEntry;
import net.minecraft.world.item.crafting.display.RecipeDisplay;
import net.minecraft.world.item.crafting.display.ShapedCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.ShapelessCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.SlotDisplay;
import net.minecraft.core.registries.Registries;
import net.minecraft.tags.TagKey;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.LinkedBlockingQueue;

public class RecipeResolver {
    private static final Logger LOG = LoggerFactory.getLogger("ClientCraftMK4");
    private static final int MAX_DEPTH = 10;
    private static final int MAX_REPEATS = 999;
    private static final ThreadPoolExecutor RESOLVER_EXECUTOR = new ThreadPoolExecutor(
            1, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(1),
            r -> {
                Thread t = new Thread(r, "ClientCraft-Resolver");
                t.setDaemon(true);
                return t;
            });
    static { RESOLVER_EXECUTOR.setRejectedExecutionHandler(new ThreadPoolExecutor.DiscardOldestPolicy()); }

    private static volatile List<RecipeCollection> cachedResults = List.of();
    private static volatile long lastCacheKey = 0;
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

    private static volatile Map<Item, List<RecipeDisplayEntry>> recipesByOutput = Map.of();
    private static volatile boolean recipeIndexDirty = true;
    private static volatile int lastRecipeCount = 0;
    private static RecipeTree cachedRecipeTree = null;
    private static int currentGridSize = 3;
    private static volatile Map<RecipeDisplayId, Integer> craftCounts = Map.of();
    private static volatile Set<RecipeDisplayId> containerCraftable = Set.of();
    private static volatile Set<Item> containerAvailableItems = Set.of();
    private static volatile Map<RecipeCollection, Integer> collectionRanks = Map.of();
    private static volatile Set<RecipeCollection> autoCraftCollections = Set.of();

    private static volatile Map<Item, Integer> cachedInventory = Map.of();
    private static volatile Map<Item, Integer> cachedContainerInventory = Map.of();
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
        return (Minecraft.getInstance().screen instanceof CraftingScreen) ? 3 : 2;
    }

    private static Map<Item, Integer> getOrSnapshotInventory() {
        Minecraft client = Minecraft.getInstance();
        if (client.level == null || client.player == null) return Map.of();
        Map<Item, Integer> snapshot = new HashMap<>();
        Map<Item, Integer> containerSnapshot = new HashMap<>();
        var inv = client.player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.isEmpty()) continue;

            if (ClientCraftConfig.searchContainers) {
                ItemContainerContents container = stack.get(DataComponents.CONTAINER);
                if (container != null) {
                    container.nonEmptyItemCopyStream().forEach(contained -> {
                        containerSnapshot.merge(contained.getItem(), contained.getCount(), Integer::sum);
                    });
                }
                BundleContents bundle = stack.get(DataComponents.BUNDLE_CONTENTS);
                if (bundle != null) {
                    bundle.itemCopyStream().forEach(contained -> {
                        containerSnapshot.merge(contained.getItem(), contained.getCount(), Integer::sum);
                    });
                }
            }

            if (stack.has(DataComponents.CUSTOM_NAME)) continue;
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
        Minecraft client = Minecraft.getInstance();
        if (client.player == null) return;
        List<RecipeCollection> allCrafting = client.player.getRecipeBook().getCollection(SearchRecipeBookCategory.CRAFTING);
        int currentCount = 0;
        for (RecipeCollection c : allCrafting) currentCount += c.getRecipes().size();
        if (!recipeIndexDirty && !recipesByOutput.isEmpty() && currentCount == lastRecipeCount) return;
        lastRecipeCount = currentCount;
        Map<Item, List<RecipeDisplayEntry>> index = new HashMap<>();
        knownTags.clear();
        for (RecipeCollection coll : allCrafting) {
            for (RecipeDisplayEntry entry : coll.getRecipes()) {
                Item out = getOutputItem(entry.display());
                if (out != null) index.computeIfAbsent(out, k -> new ArrayList<>()).add(entry);
                List<SlotDisplay> slots = getSlots(entry.display());
                if (slots != null) for (SlotDisplay slot : slots) collectTags(slot);
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

    private static void collectTags(SlotDisplay slot) {
        if (slot instanceof SlotDisplay.TagSlotDisplay d) knownTags.add(d.tag());
        else if (slot instanceof SlotDisplay.Composite d) {
            for (SlotDisplay sub : d.contents()) collectTags(sub);
        } else if (slot instanceof SlotDisplay.WithRemainder d) collectTags(d.input());
    }

    private static Set<TagKey<Item>> computeTagsForItem(Item item) {
        Set<TagKey<Item>> existing = itemToTags.get(item);
        if (existing != null) return existing;

        Set<TagKey<Item>> tags = new HashSet<>();
        var holder = item.builtInRegistryHolder();
        for (TagKey<Item> tag : knownTags) {
            if (holder.is(tag)) tags.add(tag);
        }
        Set<TagKey<Item>> result = tags.isEmpty() ? Set.of() : tags;
        itemToTags.put(item, result);
        return result;
    }

    private static void rebuildInventoryTagIndex() {
        Set<Item> currentInvKeys = cachedInventory.keySet();
        Set<Item> currentContKeys = cachedContainerInventory.keySet();
        if (currentInvKeys.equals(lastInventoryKeySet) && currentContKeys.equals(lastContainerKeySet)) return;

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
        Minecraft client = Minecraft.getInstance();
        if (client.level == null) return null;
        var regOpt = client.level.registryAccess().lookupOrThrow(Registries.ITEM);
        var entriesOpt = regOpt.get(tag);
        if (entriesOpt.isEmpty()) return null;
        List<Item> items = new ArrayList<>();
        for (var entry : entriesOpt.get()) items.add(entry.value());
        tagMembersCache.put(tag, items);
        return items;
    }

    private static boolean hasDirectInputs(Item item) {
        List<RecipeDisplayEntry> recipes = recipesByOutput.get(item);
        if (recipes == null) return false;
        outer: for (RecipeDisplayEntry entry : recipes) {
            List<SlotDisplay> slots = getSlots(entry.display());
            if (slots == null) continue;
            for (SlotDisplay slot : slots) {
                if (slot instanceof SlotDisplay.Empty) continue;
                if (slot instanceof SlotDisplay.TagSlotDisplay t) {
                    if (inventoryTagIndex.containsKey(t.tag())) continue;
                    continue outer;
                }
                if (slot instanceof SlotDisplay.Composite d) {
                    boolean found = false;
                    for (SlotDisplay sub : d.contents()) {
                        ItemStack r = resolveSlot(sub, cachedInventory);
                        if (!r.isEmpty() && cachedInventory.getOrDefault(r.getItem(), 0) > 0) { found = true; break; }
                    }
                    if (found) continue;
                    continue outer;
                }
                if (slot instanceof SlotDisplay.WithRemainder d) {
                    SlotDisplay inner = d.input();
                    if (inner instanceof SlotDisplay.TagSlotDisplay t) {
                        if (inventoryTagIndex.containsKey(t.tag())) continue;
                        continue outer;
                    }
                }
                ItemStack r = resolveSlot(slot, cachedInventory);
                if (!r.isEmpty() && cachedInventory.getOrDefault(r.getItem(), 0) > 0) continue;
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

    public static List<RecipeCollection> resolveForTab(ClientRecipeBook recipeBook) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.level == null) return List.of();

        int gridSize = getGridSize();
        currentGridSize = gridSize;
        ensureIndex();
        Map<Item, Integer> inventory = getOrSnapshotInventory();

        long cacheKey = inventoryGeneration * 7L + gridSize;
        if (cacheKey == lastCacheKey && !cachedResults.isEmpty()) return cachedResults;
        if (batchMode) return cachedResults;
        if (resolving) return cachedResults;
        if (recipesByOutput.isEmpty()) return List.of();

        List<RecipeCollection> allCrafting = new ArrayList<>(
                recipeBook.getCollection(SearchRecipeBookCategory.CRAFTING));
        if (allCrafting.isEmpty()) return List.of();

        Map<Item, Integer> invSnapshot = new HashMap<>(inventory);
        Map<Item, Integer> contSnapshot = new HashMap<>(cachedContainerInventory);
        boolean checkContainers = ClientCraftConfig.searchContainers && !contSnapshot.isEmpty();
        int snapGridSize = gridSize;
        long snapCacheKey = cacheKey;

        // Build placeholder results so tab is visible while background computes
        if (cachedResults.isEmpty()) {
            List<RecipeCollection> placeholder = new ArrayList<>();
            for (RecipeCollection coll : allCrafting) {
                List<RecipeDisplayEntry> entries = coll.getRecipes();
                if (!entries.isEmpty()) {
                    RecipeCollection nc = new RecipeCollection(entries);
                    RecipeResultCollectionAccessor acc = (RecipeResultCollectionAccessor) nc;
                    for (RecipeDisplayEntry e : entries) acc.getDisplayableRecipes().add(e.id());
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

                Map<RecipeDisplayId, Integer> counts = new HashMap<>();
                Set<RecipeDisplayId> containerSet = new HashSet<>();
                Map<RecipeCollection, Integer> ranks = new IdentityHashMap<>();
                List<RecipeCollection> result = new ArrayList<>();

                Map<Item, Integer> combined = checkContainers ? mergeMaps(invSnapshot, contSnapshot) : null;
                Set<Item> containerItemSet = checkContainers ? new HashSet<>(contSnapshot.keySet()) : Set.of();
                Set<Item> sharedInProgress = new HashSet<>();
                Map<Item, Integer> tempInv = new HashMap<>();

                int totalRecipes = 0, treeCounted = 0, preCheckSkipped = 0, containerChecked = 0;
                long treeBuildNs = 0, treeComputeNs = 0;

                // Build or reuse recipe tree (structure is independent of inventory counts)
                long tTreeBuild = System.nanoTime();
                RecipeTree tree;
                if (recipeIndexDirty || cachedRecipeTree == null) {
                    tree = RecipeTreeBuilder.build(allCrafting);
                    cachedRecipeTree = tree;
                } else {
                    tree = cachedRecipeTree;
                }
                treeBuildNs = System.nanoTime() - tTreeBuild;

                // Pre-compute the full set of items reachable from inventory (+ containers if enabled) via crafting chains.
                long tReachable = System.nanoTime();
                Map<Item, Integer> reachableSnapshot = checkContainers && !contSnapshot.isEmpty()
                        ? mergeMaps(invSnapshot, contSnapshot)
                        : invSnapshot;
                Set<Item> reachableItems = computeReachableItems(reachableSnapshot, snapGridSize);
                long reachableMs = (System.nanoTime() - tReachable) / 1_000_000;

                Map<RecipeDisplayId, Integer> treeCounts = Map.of();
                Map<RecipeDisplayId, Integer> treeCombinedCounts = Map.of();
                if (tree != null) {
                    long tTreeCompute = System.nanoTime();
                    treeCounts = CraftCalculator.calculatePerRecipeCounts(
                            tree, invSnapshot, Map.of(), snapGridSize, MAX_REPEATS);
                    if (checkContainers) {
                        treeCombinedCounts = CraftCalculator.calculatePerRecipeCounts(
                                tree, invSnapshot, contSnapshot, snapGridSize, MAX_REPEATS);
                    }
                    treeComputeNs = System.nanoTime() - tTreeCompute;
                }

                // Pre-filter entries per collection and compute counts in one pass
                List<List<RecipeDisplayEntry>> collAllEntries = new ArrayList<>();
                Map<RecipeDisplayId, Integer> resolvedCounts = new HashMap<>();
                Map<RecipeDisplayId, Item> entryOutputItems = new HashMap<>();

                for (RecipeCollection coll : allCrafting) {
                    List<RecipeDisplayEntry> allEntries = new ArrayList<>();

                    for (RecipeDisplayEntry entry : coll.getRecipes()) {
                        if (!fitsInGrid(entry.display(), snapGridSize)) continue;
                        ItemStack outputStack = resolveSlot(entry.display().result());
                        Item out = outputStack.isEmpty() ? null : outputStack.getItem();
                        if (out != null && recipeConsumesItem(entry, out)) continue;
                        allEntries.add(entry);
                        totalRecipes++;

                        int outputCount = Math.max(1, outputStack.getCount());
                        if (out != null) entryOutputItems.put(entry.id(), out);

                        if (!allSlotsReachable(entry, reachableItems)) {
                            preCheckSkipped++;
                            continue;
                        }

                        if (ClientCraftConfig.quickCountMode) {
                            tempInv.clear(); tempInv.putAll(invSnapshot);
                            sharedInProgress.clear();
                            if (resolve(entry, tempInv, null, sharedInProgress, 0, null)) {
                                resolvedCounts.put(entry.id(), outputCount);
                            }
                        } else {
                            int count = treeCounts.getOrDefault(entry.id(), 0);
                            if (count > 0) {
                                // Tree-based DP can over-count when multiple
                                // edges share the same base resources through
                                // sub-crafting (e.g. 2 planks → "craftable"
                                // shovel because both the head AND sticks
                                // count the same 2 planks independently).
                                // resolve() simulates actual consumption and
                                // correctly rejects impossible crafts.
                                tempInv.clear(); tempInv.putAll(invSnapshot);
                                sharedInProgress.clear();
                                if (resolve(entry, tempInv, null, sharedInProgress, 0, null)) {
                                    resolvedCounts.put(entry.id(), count);
                                    treeCounted++;
                                }
                            } else if (checkContainers) {
                                int contCount = treeCombinedCounts.getOrDefault(entry.id(), 0);
                                if (contCount > 0) {
                                    containerSet.add(entry.id());
                                    containerChecked++;
                                    Item oi = entryOutputItems.get(entry.id());
                                    if (oi != null) containerItemSet.add(oi);
                                }
                            }
                        }
                    }

                    collAllEntries.add(allEntries);
                }

                // --- Build result collections ---
                for (int i = 0; i < allCrafting.size(); i++) {
                    List<RecipeDisplayEntry> allEntries = collAllEntries.get(i);
                    if (allEntries.isEmpty()) continue;

                    List<RecipeDisplayEntry> craftable = new ArrayList<>();
                    boolean hasDirect = false;
                    boolean hasContainer = false;

                    for (RecipeDisplayEntry entry : allEntries) {
                        int finalCount = resolvedCounts.getOrDefault(entry.id(), 0);
                        if (finalCount > 0) {
                            craftable.add(entry);
                            hasDirect = true;
                            counts.put(entry.id(), finalCount);
                        } else if (containerSet.contains(entry.id())) {
                            craftable.add(entry);
                            hasContainer = true;
                        }
                    }

                    RecipeCollection nc = new RecipeCollection(allEntries);
                    RecipeResultCollectionAccessor acc = (RecipeResultCollectionAccessor) nc;
                    for (RecipeDisplayEntry e : allEntries) acc.getDisplayableRecipes().add(e.id());
                    for (RecipeDisplayEntry e : craftable) acc.getCraftableRecipes().add(e.id());
                    ranks.put(nc, hasDirect ? 0 : hasContainer ? 1 : 2);
                    result.add(nc);
                }

                if (ClientCraftConfig.debugLogging) {
                    long totalMs = (System.nanoTime() - t0) / 1_000_000;
                    LOG.info("[CC] Resolve: {}ms | {} recipes | reachable: {}ms ({} items) | treeBuild: {}ms treeCompute: {}ms | counted:{} preSkip:{} cont:{}",
                            totalMs, totalRecipes,
                            reachableMs, reachableItems.size(),
                            treeBuildNs / 1_000_000, treeComputeNs / 1_000_000,
                            treeCounted, preCheckSkipped, containerChecked);
                }

                Set<Item> finalContainerItemSet = containerItemSet;
                Minecraft.getInstance().execute(() -> {
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
                Minecraft.getInstance().execute(() -> resolving = false);
            }
        });

        return cachedResults;
    }

    public static AutoCrafter.CraftPlan buildCraftCyclesForMode(RecipeDisplayEntry target, AutoCrafter.Mode mode) {
        long t0 = System.nanoTime();
        if (Minecraft.getInstance().player == null) return null;
        prepareContext();
        long tPrep = System.nanoTime();

        Map<Item, Integer> available = new HashMap<>(cachedInventory);
        List<RecipeDisplayId> firstSteps = new ArrayList<>();
        if (!resolve(target, available, firstSteps, new HashSet<>(), 0, null)) return null;
        long tFirst = System.nanoTime();

        // A "direct" recipe has exactly 1 step (no sub-crafting required).
        // For ALL mode with direct recipes, use craftAll to fill the grid with
        // full stacks — like Shift+Click in the vanilla recipe book — so each
        // click produces up to a full stack of output instead of one craft.
        boolean directCraft = mode == AutoCrafter.Mode.ALL && firstSteps.size() == 1;

        ItemStack output = resolveResult(target.display());
        int outputCount = Math.max(1, output.getCount());

        int maxRepeats = switch (mode) {
            case ONCE -> 1;
            case STACK -> (output.getMaxStackSize() + outputCount - 1) / outputCount;
            case ALL -> MAX_REPEATS;
        };
        if (maxRepeats <= 0) return null;

        // Fast path: ONCE mode needs only the first resolve, no cycle building
        if (maxRepeats == 1) {
            return new AutoCrafter.CraftPlan(List.of(firstSteps), false);
        }

        List<List<RecipeDisplayId>> cycles = new ArrayList<>();
        cycles.add(firstSteps);

        if (directCraft) {
            // Count how many crafts can be done with direct items only
            int directCount = skipDirectCrafts(target, new HashMap<>(cachedInventory), maxRepeats);
            int craftsPerClick = output.getMaxStackSize() / outputCount;
            int directClicks = Math.max(1, (directCount + craftsPerClick - 1) / craftsPerClick);

            for (int r = 1; r < directClicks; r++) {
                cycles.add(List.of(firstSteps.getFirst()));
            }

            // Deduct direct items from available, then continue with sub-crafting for the rest
            skipDirectCrafts(target, available, directCount);
            for (int r = directCount; r < maxRepeats; r++) {
                List<RecipeDisplayId> steps = new ArrayList<>();
                if (!resolve(target, available, steps, new HashSet<>(), 0, null)) break;
                cycles.add(steps);
            }
        } else {
            for (int r = 1; r < maxRepeats; r++) {
                List<RecipeDisplayId> steps = new ArrayList<>();
                if (!resolve(target, available, steps, new HashSet<>(), 0, null)) break;
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

    public static ItemStack resolveResult(RecipeDisplay display) {
        ItemStack out = resolveSlot(display.result());
        return out.isEmpty() ? ItemStack.EMPTY : out;
    }

    public static int getCraftCount(RecipeDisplayId id) {
        return craftCounts.getOrDefault(id, 0);
    }

    public static boolean isAutoCraftCollection(RecipeCollection collection) {
        return autoCraftCollections.contains(collection);
    }

    public static boolean isContainerCraftable(RecipeDisplayId id) {
        return containerCraftable.contains(id);
    }

    public static int getCollectionRank(RecipeCollection coll) {
        return collectionRanks.getOrDefault(coll, 2);
    }

    public static String getLowerCaseName(Item item) {
        return lowerCaseNameCache.computeIfAbsent(item,
                i -> new ItemStack(i).getHoverName().getString().toLowerCase(Locale.ROOT));
    }

    public static RecipeCollection buildIngredientCollection(RecipeDisplayEntry originalEntry) {
        prepareContext();
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

    public static void markRecipesDirty() {
        recipeIndexDirty = true;
        cachedResults = List.of();
        lastCacheKey = 0;
        lastRecipeCount = 0;
        cachedRecipeTree = null;
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
        cachedRecipeTree = null;
    }

    // --- Resolution engine ---

    private static boolean resolve(
            RecipeDisplayEntry entry, Map<Item, Integer> available,
            List<RecipeDisplayId> stepsOut, Set<Item> inProgress, int depth,
            Item rootOutput) {
        if (depth > MAX_DEPTH) return false;

        List<SlotDisplay> slots = getSlots(entry.display());
        if (slots == null || slots.isEmpty()) return false;

        Item outputItem = getOutputItem(entry.display());
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
        for (SlotDisplay slot : slots) {
            if (slot instanceof SlotDisplay.Empty) continue;

            ItemStack resolved = resolveSlot(slot, available);
            if (resolved.isEmpty()) { success = false; break; }
            Item item = resolved.getItem();

            int have = available.getOrDefault(item, 0);
            if (have >= 1) {
                available.put(item, have - 1);
                if (consumed == null) consumed = new Item[slots.size()];
                consumed[consumedCount++] = item;
                continue;
            }

            // Sub-crafting needed: take full snapshot, restoring already-consumed items
            if (snapshot == null) {
                snapshot = new HashMap<>(available);
                for (int i = 0; i < consumedCount; i++) snapshot.merge(consumed[i], 1, Integer::sum);
            }

            if (!trySubCraft(item, available, stepsOut, inProgress, depth, rootOutput)
                    && !(depth <= 1 && tryTagFallback(slot, item, available, stepsOut, inProgress, depth, rootOutput))) {
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

        if (stepsOut != null) stepsOut.add(entry.id());
        if (outputItem != null) inProgress.remove(outputItem);
        return true;
    }

    private static boolean tryTagFallback(
            SlotDisplay slot, Item alreadyTried, Map<Item, Integer> working,
            List<RecipeDisplayId> stepsOut, Set<Item> inProgress, int depth, Item rootOutput) {
        // Unwrap remainder displays
        if (slot instanceof SlotDisplay.WithRemainder d)
            return tryTagFallback(d.input(), alreadyTried, working, stepsOut, inProgress, depth, rootOutput);

        if (slot instanceof SlotDisplay.TagSlotDisplay d) {
            TagKey<Item> tag = d.tag();
            // Scan working map directly for items matching this tag.
            // The global tag indices are built from a stale inventory snapshot and
            // miss items produced by sub-crafting during simulation.
            for (Map.Entry<Item, Integer> e : working.entrySet()) {
                if (e.getValue() >= 1 && !e.getKey().equals(alreadyTried)
                        && new ItemStack(e.getKey()).is(tag)) {
                    working.put(e.getKey(), e.getValue() - 1);
                    return true;
                }
            }
            // Try sub-crafting alternatives
            List<Item> craftable = craftableTagIndex.get(tag);
            if (craftable != null) {
                for (Item alt : craftable) {
                    if (alt.equals(alreadyTried)) continue;
                    if (trySubCraft(alt, working, stepsOut, inProgress, depth, rootOutput)) return true;
                }
            }
            return false;
        }

        if (slot instanceof SlotDisplay.Composite d) {
            for (SlotDisplay sub : d.contents()) {
                ItemStack r = resolveSlot(sub, working);
                if (r.isEmpty() || r.getItem().equals(alreadyTried)) continue;
                int have = working.getOrDefault(r.getItem(), 0);
                if (have >= 1) { working.put(r.getItem(), have - 1); return true; }
                if (trySubCraft(r.getItem(), working, stepsOut, inProgress, depth, rootOutput)) return true;
            }
            return false;
        }

        return false;
    }

    private static boolean trySubCraft(
            Item item, Map<Item, Integer> working, List<RecipeDisplayId> stepsOut,
            Set<Item> inProgress, int depth, Item rootOutput) {
        List<RecipeDisplayEntry> subs = recipesByOutput.get(item);
        if (subs == null) return false;
        for (int i = 0, len = subs.size(); i < len; i++) {
            RecipeDisplayEntry sub = subs.get(i);
            if (!fitsInGrid(sub.display(), currentGridSize)) continue;
            int subOutput = getOutputCount(sub.display());
            if (subOutput <= 0) continue;
            if (rootOutput != null && recipeConsumesItem(sub, rootOutput)) continue;

            // resolve() snapshots and rolls back working/stepsOut on failure,
            // so we can pass them directly instead of copying.
            if (resolve(sub, working, stepsOut, inProgress, depth + 1, rootOutput)) {
                working.merge(item, subOutput - 1, Integer::sum);
                return true;
            }
        }
        return false;
    }

    // --- Repeat counting ---

    private static final Map<Object, Integer> sdcNeeded = new HashMap<>();
    private static final Map<Object, Integer> sdcAvail = new HashMap<>();

    private static int skipDirectCrafts(RecipeDisplayEntry target, Map<Item, Integer> sim, int maxRepeats) {
        return computeDirectCrafts(target, sim, maxRepeats, sdcNeeded, sdcAvail);
    }

    /**
     * Core implementation: counts and deducts direct-only crafts (no sub-crafting).
     * @param needed (output) map of Item/TagKey → count needed per craft, cleared & filled
     * @param avail  (output) map of Item/TagKey → total available, cleared & filled
     */
    private static int computeDirectCrafts(RecipeDisplayEntry target, Map<Item, Integer> sim, int maxRepeats,
            Map<Object, Integer> needed, Map<Object, Integer> avail) {
        List<SlotDisplay> slots = getSlots(target.display());
        if (slots == null || maxRepeats <= 0) return 0;

        needed.clear();
        avail.clear();

        for (SlotDisplay slot : slots) {
            if (slot instanceof SlotDisplay.Empty) continue;
            TagKey<Item> tag = getSlotTag(slot);
            if (tag != null) {
                needed.merge(tag, 1, Integer::sum);
                if (!avail.containsKey(tag)) avail.put(tag, sumTagInventory(tag, sim));
            } else if (slot instanceof SlotDisplay.Composite) {
                return 0;
            } else {
                ItemStack resolved = resolveSlot(slot, sim);
                if (resolved.isEmpty()) return 0;
                Item item = resolved.getItem();
                if (sim.getOrDefault(item, 0) <= 0) return 0;
                needed.merge(item, 1, Integer::sum);
                avail.putIfAbsent(item, sim.getOrDefault(item, 0));
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
            } else if (e.getKey() instanceof TagKey<?>) {
                @SuppressWarnings("unchecked")
                TagKey<Item> tagKey = (TagKey<Item>) e.getKey();
                Set<Item> members = inventoryTagIndex.get(tagKey);
                if (members != null) {
                    for (Item m : members) {
                        int have = sim.getOrDefault(m, 0);
                        if (have <= 0) continue;
                        int take = Math.min(have, toConsume);
                        sim.merge(m, -take, Integer::sum);
                        toConsume -= take;
                        if (toConsume <= 0) break;
                    }
                }
            }
        }
        return maxCrafts;
    }

    // --- Recipe helpers ---

    private static boolean recipeConsumesItem(RecipeDisplayEntry entry, Item target) {
        List<SlotDisplay> slots = getSlots(entry.display());
        if (slots == null) return false;
        for (SlotDisplay slot : slots) {
            if (slotRequiresItem(slot, target)) return true;
        }
        return false;
    }

    /** Returns true only if every option for this slot resolves to the target item. */
    private static boolean slotRequiresItem(SlotDisplay slot, Item target) {
        if (slot instanceof SlotDisplay.Empty) return false;
        if (slot instanceof SlotDisplay.ItemSlotDisplay d) return d.item().value().equals(target);
        if (slot instanceof SlotDisplay.ItemStackSlotDisplay d) return d.stack().item().value().equals(target);
        // Tags and composites have alternatives, so they don't strictly require a specific item.
        // The inProgress set in resolve() already prevents true circular dependencies.
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

    /**
     * Fixed-point closure: scans all recipesByOutput iteratively until no new items
     * become reachable. Uses allSlotsReachable which handles tag slots correctly.
     * This is robust regardless of RecipeTree state or topological order correctness.
     */
    private static Set<Item> computeReachableItems(Map<Item, Integer> inventory, int gridSize) {
        Set<Item> reachable = new HashSet<>(inventory.keySet());
        boolean changed = true;
        while (changed) {
            changed = false;
            for (var entry : recipesByOutput.entrySet()) {
                Item output = entry.getKey();
                if (reachable.contains(output)) continue;
                for (RecipeDisplayEntry recipe : entry.getValue()) {
                    if (!fitsInGrid(recipe.display(), gridSize)) continue;
                    if (allSlotsReachable(recipe, reachable)) {
                        reachable.add(output);
                        changed = true;
                        break;
                    }
                }
            }
        }
        return reachable;
    }

    /** Returns true if every non-empty slot has at least one option in the reachable set. */
    private static boolean allSlotsReachable(RecipeDisplayEntry entry, Set<Item> reachable) {
        List<SlotDisplay> slots = getSlots(entry.display());
        if (slots == null) return false;
        for (SlotDisplay slot : slots) {
            if (slot instanceof SlotDisplay.Empty) continue;
            if (!slotReachable(slot, reachable)) return false;
        }
        return true;
    }

    private static boolean slotReachable(SlotDisplay slot, Set<Item> reachable) {
        if (slot instanceof SlotDisplay.ItemSlotDisplay d) return reachable.contains(d.item().value());
        if (slot instanceof SlotDisplay.ItemStackSlotDisplay d) return reachable.contains(d.stack().item().value());
        if (slot instanceof SlotDisplay.TagSlotDisplay d) {
            List<Item> members = getOrComputeTagMembers(d.tag());
            if (members != null) {
                for (Item m : members) {
                    if (reachable.contains(m)) return true;
                }
            }
            return false;
        }
        if (slot instanceof SlotDisplay.Composite d) {
            for (SlotDisplay sub : d.contents()) {
                if (slotReachable(sub, reachable)) return true;
            }
            return false;
        }
        if (slot instanceof SlotDisplay.WithRemainder d) return slotReachable(d.input(), reachable);
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
                if (!(ingredients.get(i) instanceof SlotDisplay.Empty)) count++;
            }
            return count <= gridSize * gridSize;
        }
        return false;
    }

    private static TagKey<Item> getSlotTag(SlotDisplay slot) {
        if (slot instanceof SlotDisplay.TagSlotDisplay d) return d.tag();
        if (slot instanceof SlotDisplay.WithRemainder d) return getSlotTag(d.input());
        return null;
    }

    private static int sumTagInventory(TagKey<Item> tag, Map<Item, Integer> inventory) {
        int total = 0;
        Set<Item> matches = inventoryTagIndex.get(tag);
        if (matches != null) {
            for (Item item : matches) {
                total += inventory.getOrDefault(item, 0);
            }
        }
        return total;
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
        if (display instanceof SlotDisplay.Empty) return ItemStack.EMPTY;
        if (display instanceof SlotDisplay.ItemSlotDisplay d) return new ItemStack(d.item());
        if (display instanceof SlotDisplay.ItemStackSlotDisplay d) {
            return new ItemStack(d.stack().item().value(), d.stack().count());
        }
        if (display instanceof SlotDisplay.TagSlotDisplay d) {
            TagKey<Item> tag = d.tag();
            // 1. Check inventory items matching this tag
            Set<Item> matches = inventoryTagIndex.get(tag);
            if (matches != null) {
                for (Item item : matches) {
                    if (inventory.getOrDefault(item, 0) > 0) return new ItemStack(item);
                }
            }
            // 2. Check craftable items in working copies (sub-crafted items not yet in inventoryTagIndex)
            if (inventory != cachedInventory) {
                List<Item> craft = craftableTagIndex.get(tag);
                if (craft != null) {
                    for (Item item : craft) {
                        if (inventory.getOrDefault(item, 0) > 0) return new ItemStack(item);
                    }
                }
            }
            // 3. Return a sub-craftable item so trySubCraft() can handle it
            List<Item> craftable = craftableTagIndex.get(tag);
            if (craftable != null) return new ItemStack(craftable.getFirst());
            // 4. Fallback for display purposes
            return getAnyTagMember(tag);
        }
        if (display instanceof SlotDisplay.WithRemainder d) return resolveSlot(d.input(), inventory);
        if (display instanceof SlotDisplay.Composite d) {
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
                if (slot instanceof SlotDisplay.Empty) continue;
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
                if (slot instanceof SlotDisplay.Empty) continue;
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

        // Second try: find an item we can sub-craft that satisfies this slot
        ItemStack craftable = findCraftableForSlot(slot);
        if (craftable != null) return craftable;

        // Fallback: return whatever resolveSlot gives
        return direct.isEmpty() ? resolveSlot(slot) : direct;
    }

    private static ItemStack findCraftableForSlot(SlotDisplay slot) {
        if (slot instanceof SlotDisplay.TagSlotDisplay d) {
            TagKey<Item> tag = d.tag();
            Set<Item> invMatches = inventoryTagIndex.get(tag);
            if (invMatches != null && !invMatches.isEmpty()) {
                return new ItemStack(invMatches.iterator().next());
            }
            List<Item> craftable = craftableTagIndex.get(tag);
            if (craftable != null) {
                for (Item item : craftable) {
                    if (canSubCraft(item, cachedInventory)) return new ItemStack(item);
                }
            }
        } else if (slot instanceof SlotDisplay.Composite d) {
            ItemStack fallback = ItemStack.EMPTY;
            for (SlotDisplay sub : d.contents()) {
                ItemStack r = resolveSlot(sub, cachedInventory);
                if (r.isEmpty()) continue;
                if (fallback.isEmpty()) fallback = r;
                if (cachedInventory.getOrDefault(r.getItem(), 0) > 0) return r;
                if (canSubCraft(r.getItem(), cachedInventory)) return r;
            }
            if (!fallback.isEmpty()) return fallback;
        } else if (slot instanceof SlotDisplay.WithRemainder d) {
            return findCraftableForSlot(d.input());
        }
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
        if (slot instanceof SlotDisplay.TagSlotDisplay d) {
            Set<Item> contMatches = containerTagIndex.get(d.tag());
            if (contMatches != null) {
                for (Item item : contMatches) if (items.contains(item)) return item;
            }
            List<Item> members = getOrComputeTagMembers(d.tag());
            if (members != null) {
                for (Item member : members) if (items.contains(member)) return member;
            }
        } else if (slot instanceof SlotDisplay.Composite comp) {
            for (SlotDisplay sub : comp.contents()) {
                Item item = findInSet(sub, resolved, items);
                if (item != null) return item;
            }
        } else if (slot instanceof SlotDisplay.WithRemainder rem) {
            return findInSet(rem.input(), resolved, items);
        }
        return null;
    }

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
        RecipeResultCollectionAccessor acc = (RecipeResultCollectionAccessor) collection;
        for (RecipeDisplayEntry e : entries) { acc.getDisplayableRecipes().add(e.id()); acc.getCraftableRecipes().add(e.id()); }
        return collection;
    }
}
