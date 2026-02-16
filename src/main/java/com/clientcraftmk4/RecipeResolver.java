package com.clientcraftmk4;

import com.clientcraftmk4.mixin.RecipeResultCollectionAccessor;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.CraftingScreen;
import net.minecraft.client.gui.screen.recipebook.RecipeResultCollection;
import net.minecraft.client.recipebook.ClientRecipeBook;
import net.minecraft.client.recipebook.RecipeBookType;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.BundleContentsComponent;
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
import net.minecraft.registry.tag.TagKey;

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
    private static long inventoryGeneration = 0;

    private static Map<Item, String> lowerCaseNameCache = new HashMap<>();
    private static IngredientGrid activeIngredientGrid = null;

    // --- Tag cache ---
    private static Set<TagKey<Item>> knownTags = new HashSet<>();
    private static Map<Item, Set<TagKey<Item>>> itemToTags = new HashMap<>();
    private static Set<Item> tagComputedItems = new HashSet<>();
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
        Map<Item, List<RecipeDisplayEntry>> index = new HashMap<>();
        knownTags.clear();
        for (RecipeResultCollection coll : client.player.getRecipeBook().getResultsForCategory(RecipeBookType.CRAFTING)) {
            for (RecipeDisplayEntry entry : coll.getAllRecipes()) {
                Item out = getOutputItem(entry.display());
                if (out != null) index.computeIfAbsent(out, k -> new ArrayList<>()).add(entry);
                List<SlotDisplay> slots = getSlots(entry.display());
                if (slots != null) for (SlotDisplay slot : slots) collectTags(slot);
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

    private static void collectTags(SlotDisplay slot) {
        if (slot instanceof SlotDisplay.TagSlotDisplay d) knownTags.add(d.tag());
        else if (slot instanceof SlotDisplay.CompositeSlotDisplay d) {
            for (SlotDisplay sub : d.contents()) collectTags(sub);
        } else if (slot instanceof SlotDisplay.WithRemainderSlotDisplay d) collectTags(d.input());
    }

    private static Set<TagKey<Item>> computeTagsForItem(Item item) {
        Set<TagKey<Item>> existing = itemToTags.get(item);
        if (existing != null) return existing;
        if (tagComputedItems.contains(item)) return Set.of();

        Set<TagKey<Item>> tags = new HashSet<>();
        ItemStack stack = new ItemStack(item);
        for (TagKey<Item> tag : knownTags) {
            if (stack.isIn(tag)) tags.add(tag);
        }
        tagComputedItems.add(item);
        if (!tags.isEmpty()) itemToTags.put(item, tags);
        return tags;
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

        LOG.info("[CC] inventoryTagIndex rebuilt: {} tags, inv items: {}", inventoryTagIndex.size(),
                currentInvKeys.stream().map(i -> net.minecraft.registry.Registries.ITEM.getId(i).getPath()).toList());

        // Build craftableTagIndex: tag → recipe output items matching that tag
        // Items whose direct recipe inputs are in inventory/inventoryTagIndex sort first
        // Flipped loop: iterate items once (calling hasDirectInputs once per item)
        // instead of iterating tags × items (calling hasDirectInputs per tag match)
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

        LOG.info("[CC] craftableTagIndex rebuilt: {} tags", craftableTagIndex.size());
        for (var e : craftableTagIndex.entrySet()) {
            String tagName = e.getKey().id().toString();
            if (tagName.contains("plank") || tagName.contains("chest") || tagName.contains("trap") || tagName.contains("ender")) {
                LOG.info("[CC]   {} -> {}", tagName,
                        e.getValue().stream().limit(5).map(i -> net.minecraft.registry.Registries.ITEM.getId(i).getPath()).toList());
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
        var entriesOpt = regOpt.get().getOptional(tag);
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
                if (slot instanceof SlotDisplay.EmptySlotDisplay) continue;
                if (slot instanceof SlotDisplay.TagSlotDisplay t) {
                    if (inventoryTagIndex.containsKey(t.tag())) continue;
                    continue outer;
                }
                if (slot instanceof SlotDisplay.CompositeSlotDisplay d) {
                    boolean found = false;
                    for (SlotDisplay sub : d.contents()) {
                        ItemStack r = resolveSlot(sub, cachedInventory);
                        if (!r.isEmpty() && cachedInventory.getOrDefault(r.getItem(), 0) > 0) { found = true; break; }
                    }
                    if (found) continue;
                    continue outer;
                }
                if (slot instanceof SlotDisplay.WithRemainderSlotDisplay d) {
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

        List<RecipeResultCollection> allCrafting = new ArrayList<>(
                recipeBook.getResultsForCategory(RecipeBookType.CRAFTING));
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
                List<RecipeDisplayEntry> entries = coll.getAllRecipes();
                if (!entries.isEmpty()) {
                    RecipeResultCollection nc = new RecipeResultCollection(entries);
                    RecipeResultCollectionAccessor acc = (RecipeResultCollectionAccessor) nc;
                    for (RecipeDisplayEntry e : entries) acc.getDisplayableRecipes().add(e.id());
                    placeholder.add(nc);
                }
            }
            cachedResults = placeholder;
        }

        resolving = true;

        RESOLVER_EXECUTOR.submit(() -> {
            try {
                List<RecipeResultCollection> result = new ArrayList<>();
                Map<NetworkRecipeId, Integer> counts = new HashMap<>();
                Set<NetworkRecipeId> containerSet = new HashSet<>();
                Map<RecipeResultCollection, Integer> ranks = new IdentityHashMap<>();

                Map<Item, Integer> combined = checkContainers ? mergeMaps(invSnapshot, contSnapshot) : null;
                Set<Item> containerItemSet = checkContainers ? new HashSet<>(contSnapshot.keySet()) : Set.of();

                for (RecipeResultCollection coll : allCrafting) {
                    List<RecipeDisplayEntry> allEntries = new ArrayList<>();
                    List<RecipeDisplayEntry> craftable = new ArrayList<>();
                    boolean hasDirect = false;
                    boolean hasContainer = false;

                    for (RecipeDisplayEntry entry : coll.getAllRecipes()) {
                        if (!fitsInGrid(entry.display(), snapGridSize)) continue;
                        ItemStack outputStack = resolveSlot(entry.display().result());
                        Item out = outputStack.isEmpty() ? null : outputStack.getItem();
                        if (out != null && recipeConsumesItem(entry, out)) continue;
                        allEntries.add(entry);

                        int outputCount = Math.max(1, outputStack.getCount());
                        int repeats = countRepeats(entry, invSnapshot, outputCount);
                        if (repeats > 0) {
                            craftable.add(entry);
                            hasDirect = true;
                            counts.put(entry.id(), Math.min(repeats * outputCount, MAX_REPEATS));
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
        tagComputedItems.clear();
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

        boolean debug = depth == 0 && outputItem != null
                && net.minecraft.registry.Registries.ITEM.getId(outputItem).getPath().contains("inventory_interface");

        boolean success = true;
        for (SlotDisplay slot : slots) {
            if (slot instanceof SlotDisplay.EmptySlotDisplay) continue;

            ItemStack resolved = resolveSlot(slot, available);
            if (resolved.isEmpty()) {
                if (debug) LOG.info("[CC] FAIL: resolveSlot returned EMPTY for slot {}", slot);
                success = false; break;
            }
            Item item = resolved.getItem();

            int have = available.getOrDefault(item, 0);
            if (have >= 1) {
                available.put(item, have - 1);
                if (debug) LOG.info("[CC] OK: {} (have {})", net.minecraft.registry.Registries.ITEM.getId(item), have);
                continue;
            }

            if (debug) LOG.info("[CC] NEED subcraft: {} (slot: {})", net.minecraft.registry.Registries.ITEM.getId(item), slot.getClass().getSimpleName());
            if (!trySubCraft(item, available, stepsOut, inProgress, depth, rootOutput)
                    && !(depth <= 1 && tryTagFallback(slot, item, available, stepsOut, inProgress, depth, rootOutput))) {
                if (debug) LOG.info("[CC] FAIL: subcraft failed for {}", net.minecraft.registry.Registries.ITEM.getId(item));
                success = false;
                break;
            }
            if (debug) LOG.info("[CC] OK: subcrafted {}", net.minecraft.registry.Registries.ITEM.getId(item));
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

    private static boolean tryTagFallback(
            SlotDisplay slot, Item alreadyTried, Map<Item, Integer> working,
            List<NetworkRecipeId> stepsOut, Set<Item> inProgress, int depth, Item rootOutput) {
        // Unwrap remainder displays
        if (slot instanceof SlotDisplay.WithRemainderSlotDisplay d)
            return tryTagFallback(d.input(), alreadyTried, working, stepsOut, inProgress, depth, rootOutput);

        if (slot instanceof SlotDisplay.TagSlotDisplay d) {
            TagKey<Item> tag = d.tag();
            // First: check working map for items already available that match this tag
            for (Map.Entry<Item, Integer> e : working.entrySet()) {
                if (e.getKey().equals(alreadyTried)) continue;
                if (e.getValue() >= 1 && computeTagsForItem(e.getKey()).contains(tag)) {
                    working.put(e.getKey(), e.getValue() - 1);
                    return true;
                }
            }
            // Second: try sub-crafting from pre-computed list
            List<Item> craftable = craftableTagIndex.get(tag);
            if (craftable != null) {
                for (Item alt : craftable) {
                    if (alt.equals(alreadyTried)) continue;
                    if (trySubCraft(alt, working, stepsOut, inProgress, depth, rootOutput)) return true;
                }
            }
            return false;
        }

        if (slot instanceof SlotDisplay.CompositeSlotDisplay d) {
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

    private static int countRepeats(RecipeDisplayEntry target, Map<Item, Integer> inventory, int outputCount) {
        int maxRepeats = Math.max(1, MAX_REPEATS / outputCount);
        int mathCount = tryMathCount(target, inventory, maxRepeats);
        if (mathCount >= 0) return mathCount;

        Map<Item, Integer> sim = new HashMap<>(inventory);
        int count = 0;
        while (count < maxRepeats) {
            if (!resolve(target, sim, null, new HashSet<>(), 0, null)) break;
            count++;
        }
        return count;
    }

    private static int tryMathCount(RecipeDisplayEntry target, Map<Item, Integer> inventory, int maxRepeats) {
        List<SlotDisplay> slots = getSlots(target.display());
        if (slots == null) return -1;

        Map<Item, Integer> needed = new HashMap<>();
        for (SlotDisplay slot : slots) {
            if (slot instanceof SlotDisplay.EmptySlotDisplay) continue;
            ItemStack resolved = resolveSlot(slot, inventory);
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
        return maxCrafts > 0 ? maxCrafts : -1;
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
            TagKey<Item> tag = d.tag();
            // 1. Check inventory items matching this tag
            Set<Item> matches = inventoryTagIndex.get(tag);
            if (matches != null) {
                for (Item item : matches) {
                    if (inventory.getOrDefault(item, 0) > 0) return new ItemStack(item);
                }
            }
            // 2. Check working map (resolve() decrements counts into working copies)
            if (inventory != cachedInventory) {
                for (Item item : inventory.keySet()) {
                    if (inventory.get(item) > 0 && computeTagsForItem(item).contains(tag)) return new ItemStack(item);
                }
            }
            // 3. Return a sub-craftable item so trySubCraft() can handle it
            List<Item> craftable = craftableTagIndex.get(tag);
            if (craftable != null) return new ItemStack(craftable.getFirst());
            // 4. Fallback for display purposes
            return getAnyTagMember(tag);
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
        } else if (slot instanceof SlotDisplay.CompositeSlotDisplay d) {
            ItemStack fallback = ItemStack.EMPTY;
            for (SlotDisplay sub : d.contents()) {
                ItemStack r = resolveSlot(sub, cachedInventory);
                if (r.isEmpty()) continue;
                if (fallback.isEmpty()) fallback = r;
                if (cachedInventory.getOrDefault(r.getItem(), 0) > 0) return r;
                if (canSubCraft(r.getItem(), cachedInventory)) return r;
            }
            if (!fallback.isEmpty()) return fallback;
        } else if (slot instanceof SlotDisplay.WithRemainderSlotDisplay d) {
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
