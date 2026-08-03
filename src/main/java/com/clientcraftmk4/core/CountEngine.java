package com.clientcraftmk4.core;

import com.clientcraftmk4.config.ClientCraftConfig;
import com.clientcraftmk4.core.RecipeGraph.GraphFlatData;
import com.clientcraftmk4.core.algorithms.DpEstimator;
import com.clientcraftmk4.core.algorithms.ExactSimulator;
import com.clientcraftmk4.core.algorithms.Reachability;
import com.clientcraftmk4.core.resolver.ResolveContext;
import net.minecraft.client.ClientRecipeBook;
import net.minecraft.client.gui.screens.recipebook.RecipeCollection;
import net.minecraft.client.gui.screens.recipebook.SearchRecipeBookCategory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.display.RecipeDisplayEntry;
import net.minecraft.world.item.crafting.display.RecipeDisplayId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Computes per-recipe craft counts for the ClientCraft tab (plan §6.8).
 *
 * <p>Byte-equivalent to MK4's resolveForTab worker body, with two differences:
 * the DP-exactness gates ({@code recCycleSuspect}/{@code recSharingSuspect})
 * are read from the flat data instead of recomputed per resolve, and the
 * resolver runs on {@link WorkMap} instead of HashMap copies. The count contract
 * is unchanged: DP value when provably exact, exact simulation otherwise, and
 * container items only ever flip the {@code containerCraftable} flag.
 */
public final class CountEngine {
    private static final Logger LOG = LoggerFactory.getLogger("clientcraftmk4");

    private CountEngine() {}

    public static CraftCounts compute(ClientRecipeBook book, int gridSize, InventorySnapshot snapshot,
                                      long modelGeneration) {
        long t0 = System.nanoTime();

        CraftModel model = CraftModel.current();
        if (model == null) return CraftCounts.EMPTY;
        if (model.modelGeneration() != modelGeneration) {
            if (ClientCraftConfig.debugLogging) {
                LOG.info("[CC] Resolve: aborted (model generation {} != request {})",
                        model.modelGeneration(), modelGeneration);
            }
            return CraftCounts.EMPTY;
        }
        long modelNs = System.nanoTime() - t0;

        Map<Item, Integer> invSnapshot = snapshot.inventory();
        Map<Item, Integer> contSnapshot = snapshot.container();
        boolean checkContainers = ClientCraftConfig.searchContainers && !contSnapshot.isEmpty();

        List<RecipeCollection> allCrafting = book.getCollection(SearchRecipeBookCategory.CRAFTING);
        if (allCrafting.isEmpty()) return CraftCounts.EMPTY;

        Map<Item, Integer> combined = checkContainers ? snapshot.combined() : null;
        Set<Item> containerItemSet = checkContainers ? new HashSet<>(contSnapshot.keySet()) : Set.of();

        int totalRecipes = 0, treeCounted = 0, preCheckSkipped = 0, treeSkipped = 0, containerChecked = 0;
        int dpExactCount = 0, simulatedCount = 0;
        long treeComputeNs = 0, verifyNs = 0;

        RecipeGraph graph = model.graph();
        GraphFlatData flat = graph != null ? graph.flat() : null;

        long tReachable = System.nanoTime();
        Map<Item, Integer> reachableSnapshot = checkContainers && !contSnapshot.isEmpty()
                ? combined
                : invSnapshot;
        Set<Item> reachableItems = Reachability.compute(graph, reachableSnapshot, gridSize);
        long reachableNs = System.nanoTime() - tReachable;

        Map<RecipeDisplayId, Integer> treeCounts = Map.of();
        Map<RecipeDisplayId, Integer> treeCombinedCounts = Map.of();
        if (graph != null) {
            long tTreeCompute = System.nanoTime();
            treeCounts = DpEstimator.calculatePerRecipeCounts(
                    graph, invSnapshot, Map.of(), gridSize, Constants.MAX_REPEATS);
            if (checkContainers) {
                treeCombinedCounts = DpEstimator.calculatePerRecipeCounts(
                        graph, invSnapshot, contSnapshot, gridSize, Constants.MAX_REPEATS);
            }
            treeComputeNs = System.nanoTime() - tTreeCompute;
        }

        long tVerify = System.nanoTime();
        Map<RecipeDisplayId, Integer> resolvedCounts = new HashMap<>();
        Set<RecipeDisplayId> containerSet = new HashSet<>();
        List<List<RecipeDisplayEntry>> collAllEntries = new ArrayList<>();

        for (RecipeCollection coll : allCrafting) {
            List<RecipeDisplayEntry> allEntries = new ArrayList<>();

            for (RecipeDisplayEntry entry : coll.getRecipes()) {
                int recIdx = flat != null ? flat.dispIdToRecIdx().getOrDefault(entry.id(), -1) : -1;
                String entryName = null;
                if (ClientCraftConfig.debugLogging) {
                    if (recIdx >= 0) {
                        entryName = model.recipeIndex().getLowerCaseName(flat.idToItem()[flat.recOutId()[recIdx]]);
                    } else {
                        ItemStack rs = RecipeDisplays.resolveResult(entry.display(), model.tagIndex());
                        entryName = rs.isEmpty() ? "?" : model.recipeIndex().getLowerCaseName(rs.getItem());
                    }
                }

                if (!ClientCraftConfig.quickCountMode && recIdx >= 0) {
                    // === FLAT ARRAY PATH: zero SlotDisplay processing ===
                    if (flat.recGridSize()[recIdx] > gridSize) continue;
                    if (flat.recSelfConsuming()[recIdx]) continue;

                    int count = treeCounts.getOrDefault(entry.id(), 0);
                    boolean cycleSuspect = flat.recCycleSuspect()[recIdx];

                    if (count == 0 && !checkContainers && !cycleSuspect
                            && !Reachability.allEdgesReachableFlat(flat, recIdx, reachableItems)) {
                        allEntries.add(entry);
                        totalRecipes++;
                        treeSkipped++;
                        logEntry(entryName, count, "treeSkip");
                        continue;
                    }

                    allEntries.add(entry);
                    totalRecipes++;

                    Item out = flat.idToItem()[flat.recOutId()[recIdx]];
                    int outputCount = flat.recOutCount()[recIdx];

                    if (count > 0) {
                        // The tree DP can both over-count (intra-edge option sharing,
                        // cross-edge sharing) and under-count (cycle-avoidance hides
                        // nuggets→ingot→block chains). Perf gate: when the DP is provably
                        // exact — not cycle-flagged, no sharing-suspect edge, and the
                        // physical inventory alone already satisfies the DP count — trust
                        // it; otherwise the exact simulator is the ground truth.
                        boolean dpExact = !cycleSuspect
                                && !flat.recSharingSuspect()[recIdx]
                                && allDirectlyAvailableFlat(flat, recIdx, invSnapshot)
                                && directCountFlat(flat, recIdx, invSnapshot) >= count;
                        if (dpExact) {
                            resolvedCounts.put(entry.id(), count);
                            treeCounted++;
                            dpExactCount++;
                            logEntry(entryName, count, "dpExact", count);
                        } else {
                            int hi = simulateUpperBound(flat, recIdx, outputCount, count, cycleSuspect);
                            int exact = ExactSimulator.simulateCraftCount(
                                    model, gridSize, entry, invSnapshot, hi) * outputCount;
                            simulatedCount++;
                            if (exact > 0) {
                                resolvedCounts.put(entry.id(), Math.min(exact, Constants.MAX_REPEATS));
                                treeCounted++;
                                logEntry(entryName, count, "sim", exact);
                            } else if (checkContainers && ExactSimulator.tryResolveOnce(model, gridSize, entry, combined)) {
                                containerSet.add(entry.id());
                                containerChecked++;
                                containerItemSet.add(out);
                                logEntry(entryName, count, "container");
                            } else {
                                logEntry(entryName, count, "sim0");
                            }
                        }
                    } else {
                        // DP under-counted (cycle-avoidance hid a sub-craft path, e.g.
                        // 9 gold nuggets + 8 ingots can craft a gold block, or the
                        // straw -> tall dry grass -> short dry grass -> torch chain).
                        // Verify whenever every edge is reachable — the cycle flag
                        // alone misses recipes whose sub-craft chain passes through a
                        // cycle without the recipe's own output being part of it.
                        boolean edgesReachable = Reachability.allEdgesReachableFlat(flat, recIdx, reachableItems);
                        if (edgesReachable) {
                            int exact = ExactSimulator.simulateCraftCount(
                                    model, gridSize, entry, invSnapshot, Constants.MAX_REPEATS) * outputCount;
                            if (exact > 0) {
                                resolvedCounts.put(entry.id(), Math.min(exact, Constants.MAX_REPEATS));
                                treeCounted++;
                                logEntry(entryName, count, "sim-cycle", exact);
                            } else if (checkContainers && ExactSimulator.tryResolveOnce(model, gridSize, entry, combined)) {
                                containerSet.add(entry.id());
                                containerChecked++;
                                containerItemSet.add(out);
                                logEntry(entryName, count, "container");
                            } else {
                                logEntry(entryName, count, "cycle-skip");
                            }
                        } else if (checkContainers) {
                            int contCount = treeCombinedCounts.getOrDefault(entry.id(), 0);
                            if (contCount > 0) {
                                // The DP under-counted this recipe, so its craftability
                                // must be verified exactly. Check the inventory alone
                                // first: a recipe craftable from the inventory is counted
                                // as craftable, never mislabeled as container-craftable.
                                int exact = ExactSimulator.simulateCraftCount(
                                        model, gridSize, entry, invSnapshot, Constants.MAX_REPEATS) * outputCount;
                                if (exact > 0) {
                                    resolvedCounts.put(entry.id(), Math.min(exact, Constants.MAX_REPEATS));
                                    treeCounted++;
                                    logEntry(entryName, count, "sim-recover", exact);
                                } else if (ExactSimulator.tryResolveOnce(model, gridSize, entry, combined)) {
                                    containerSet.add(entry.id());
                                    containerChecked++;
                                    containerItemSet.add(out);
                                    logEntry(entryName, count, "container");
                                } else {
                                    logEntry(entryName, count, "cont-skip");
                                }
                            } else {
                                logEntry(entryName, count, "cont0");
                            }
                        } else {
                            logEntry(entryName, count, "cycle-skip");
                        }
                    }
                } else {
                    // === SLOTDISPLAY PATH: quickCountMode or recipe not in the tree ===
                    if (!RecipeDisplays.fitsInGrid(entry.display(), gridSize)) continue;

                    if (!ClientCraftConfig.quickCountMode) {
                        allEntries.add(entry);
                        totalRecipes++;
                        treeSkipped++;
                        logEntry(entryName, 0, "notInTree");
                        continue;
                    }

                    ItemStack outputStack = RecipeDisplays.resolveSlot(entry.display().result(), invSnapshot, model.tagIndex(), false);
                    Item out = outputStack.isEmpty() ? null : outputStack.getItem();
                    if (out != null && RecipeDisplays.recipeConsumesItem(entry, out)) continue;
                    allEntries.add(entry);
                    totalRecipes++;

                    int outputCount = Math.max(1, outputStack.getCount());

                    if (!Reachability.allSlotsReachable(entry, reachableItems, model.tagIndex())) {
                        preCheckSkipped++;
                        continue;
                    }
                    WorkMap temp = WorkMap.from(invSnapshot, graph);
                    Set<Item> sharedInProgress = new HashSet<>();
                    if (ResolveContext.of(model, gridSize).resolve(entry, temp, null, sharedInProgress, 0, null)) {
                        resolvedCounts.put(entry.id(), outputCount);
                        logEntry(entryName, 0, "resolve", outputCount);
                    } else {
                        logEntry(entryName, 0, "resolve0");
                    }
                }
            }

            collAllEntries.add(allEntries);
        }

        verifyNs = System.nanoTime() - tVerify;

        if (ClientCraftConfig.debugLogging) {
            long totalNs = System.nanoTime() - t0;
            LOG.info("[CC] Resolve: {}us | {} recipes | reachable: {}us ({} items) | model: {}us treeCompute: {}us verify: {}us | counted:{} preSkip:{} treeSkip:{} cont:{} | dpExact:{} simulated:{}",
                    totalNs / 1_000, totalRecipes,
                    reachableNs / 1_000, reachableItems.size(),
                    modelNs / 1_000, treeComputeNs / 1_000, verifyNs / 1_000,
                    treeCounted, preCheckSkipped, treeSkipped, containerChecked,
                    dpExactCount, simulatedCount);
        }

        return new CraftCounts(
                resolvedCounts, containerSet, containerItemSet, collAllEntries,
                new CraftCounts.Stats(System.nanoTime() - t0, treeCounted, preCheckSkipped, treeSkipped, containerChecked));
    }

    /** Debug-log one recipe's counting decision (only when {@code debugLogging} is on). */
    private static void logEntry(String name, int dpCount, String decision, int value) {
        if (ClientCraftConfig.debugLogging) {
            LOG.info("[CC]   {} | dp={} -> {}{}", name, dpCount, decision,
                    value > 0 ? " (" + value + ")" : "");
        }
    }

    private static void logEntry(String name, int dpCount, String decision) {
        logEntry(name, dpCount, decision, 0);
    }

    /** True if every edge of the flat recipe has at least one option in the physical inventory. */
    private static boolean allDirectlyAvailableFlat(GraphFlatData f, int recIdx, Map<Item, Integer> inventory) {        for (int ei = f.recEdgeStart()[recIdx]; ei < f.recEdgeEnd()[recIdx]; ei++) {
            boolean anyAvail = false;
            for (int oi = f.edgeOptStart()[ei]; oi < f.edgeOptEnd()[ei]; oi++) {
                Item item = f.optItemObj()[oi];
                if (inventory.getOrDefault(item, 0) > 0) {
                    anyAvail = true;
                    break;
                }
            }
            if (!anyAvail) return false;
        }
        return true;
    }

    /** Exact direct (no sub-crafting) craftable item count using physical inventory only. */
    private static int directCountFlat(GraphFlatData f, int recIdx, Map<Item, Integer> inventory) {
        long maxOps = Long.MAX_VALUE;
        for (int ei = f.recEdgeStart()[recIdx]; ei < f.recEdgeEnd()[recIdx]; ei++) {
            long avail = 0;
            for (int oi = f.edgeOptStart()[ei]; oi < f.edgeOptEnd()[ei]; oi++) {
                avail += inventory.getOrDefault(f.optItemObj()[oi], 0);
            }
            maxOps = Math.min(maxOps, avail / f.edgeCnt()[ei]);
        }
        if (maxOps == Long.MAX_VALUE || maxOps <= 0) return 0;
        long items = maxOps * f.recOutCount()[recIdx];
        return items > Constants.MAX_REPEATS ? Constants.MAX_REPEATS : (int) items;
    }

    /**
     * A safe upper bound (in crafts) for the exact simulator's binary search.
     *
     * <p>For non-cycle-suspect recipes the tree DP never under-counts (its only
     * under-count source is cycle-avoidance, which is exactly what
     * {@code recCycleSuspect} flags), so the DP item count is an upper bound on
     * the true count. Multi-recipe outputs share the DP estimate (a per-recipe
     * under-count), and base-with-recipes options fall back to physical-only
     * memoisation — both must keep the full 999 bound. The search result is
     * unchanged: feasibility is monotonic and the truth always lies ≤ hi.
     */
    private static int simulateUpperBound(GraphFlatData f, int recIdx, int outputCount,
                                          int dpCount, boolean cycleSuspect) {
        if (cycleSuspect || dpCount <= 0) return Constants.MAX_REPEATS;
        int outId = f.recOutId()[recIdx];
        if (outId < 0) return Constants.MAX_REPEATS;
        if (f.itemRecEnd()[outId] - f.itemRecStart()[outId] > 1) return Constants.MAX_REPEATS;
        for (int ei = f.recEdgeStart()[recIdx]; ei < f.recEdgeEnd()[recIdx]; ei++) {
            for (int oi = f.edgeOptStart()[ei]; oi < f.edgeOptEnd()[ei]; oi++) {
                int oid = f.optItemId()[oi];
                if (oid >= 0 && f.isBaseNode()[oid] && f.primaryRecIdx()[oid] >= 0) {
                    return Constants.MAX_REPEATS;
                }
            }
        }
        int hi = (dpCount + outputCount - 1) / outputCount;   // items → crafts
        if (hi >= Constants.MAX_REPEATS) return Constants.MAX_REPEATS;
        return Math.max(1, hi);
    }

    /** Counting result: per-recipe counts, container flags, and the per-collection entry lists. */
    public record CraftCounts(
            Map<RecipeDisplayId, Integer> counts,
            Set<RecipeDisplayId> containerCraftable,
            Set<Item> containerAvailableItems,
            List<List<RecipeDisplayEntry>> collAllEntries,
            Stats stats
    ) {
        public static final CraftCounts EMPTY = new CraftCounts(
                Map.of(), Set.of(), Set.of(), List.of(),
                new Stats(0, 0, 0, 0, 0));

        public boolean isEmpty() {
            return counts.isEmpty() && containerCraftable.isEmpty() && collAllEntries.isEmpty();
        }

        public record Stats(long totalNs, int treeCounted, int preCheckSkipped, int treeSkipped, int containerChecked) {}
    }
}
