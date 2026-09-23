package com.clientcraftmk4.core;

import com.clientcraftmk4.config.ClientCraftConfig;
import com.clientcraftmk4.core.RecipeGraph.GraphFlatData;
import com.clientcraftmk4.core.algorithms.DpEstimator;
import com.clientcraftmk4.core.algorithms.ExactSimulator;
import com.clientcraftmk4.core.algorithms.Reachability;
import com.clientcraftmk4.core.resolver.ResolveContext;
import net.minecraft.client.gui.screens.recipebook.RecipeCollection;
import net.minecraft.tags.TagKey;
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
    private static final Logger LOG = LoggerFactory.getLogger(Constants.MOD_ID);

    private CountEngine() {}

    /**
     * Base for an incremental resolve: previously published counts plus the snapshot and
     * grid they were computed from. Built by the pipeline from the last published result;
     * null (or stale) means a full recompute.
     */
    public record Previous(Map<RecipeDisplayId, Integer> counts,
                           Set<RecipeDisplayId> containerCraftable,
                           InventorySnapshot snapshot,
                           long gridSize) {}

    public static CraftCounts compute(List<RecipeCollection> allCrafting, int gridSize,
                                      InventorySnapshot snapshot, long modelGeneration,
                                      Previous previous, Set<TagKey<Item>> changedTags) {
        // All timing is gated: nanoTime calls are not free (~20-100ns each) and block JIT
        // elision of the surrounding code when left unconditional on this hot worker path.
        boolean debug = ClientCraftConfig.debugLogging;
        long t0 = debug ? System.nanoTime() : 0;

        CraftModel model = CraftModel.current();
        if (model == null) return CraftCounts.EMPTY;
        if (model.modelGeneration() != modelGeneration) {
            if (debug) {
                LOG.info("[CC] Resolve: aborted (model generation {} != request {})",
                        model.modelGeneration(), modelGeneration);
            }
            return CraftCounts.EMPTY;
        }
        long modelNs = debug ? System.nanoTime() - t0 : 0;

        Map<Item, Integer> invSnapshot = snapshot.inventory();
        Map<Item, Integer> contSnapshot = snapshot.container();
        boolean checkContainers = ClientCraftConfig.searchContainers && !contSnapshot.isEmpty();

        if (allCrafting.isEmpty()) return CraftCounts.EMPTY;

        Map<Item, Integer> combined = checkContainers ? snapshot.combined() : null;

        int totalRecipes = 0, treeCounted = 0, preCheckSkipped = 0, treeSkipped = 0, containerChecked = 0;
        int dpExactCount = 0, simulatedCount = 0, reused = 0;
        // dpExact gate rejection breakdown (debug only): tells which exactness condition
        // forces most sim fallbacks, so future gate tuning is data-driven, not guessed.
        int gateCycle = 0, gateSharing = 0, gateCross = 0, gateDirect = 0;
        long treeComputeNs = 0, verifyNs = 0;

        RecipeGraph graph = model.graph();
        GraphFlatData flat = graph != null ? graph.flat() : null;
        int totalFlatRecipes = flat != null ? flat.totalRecipes() : 0;

        // --- Incremental resolve: reuse published counts for recipes whose inputs cannot
        // have changed. Valid only with a non-empty previous base from the same model and
        // grid; a >50% dirty set falls back to the full path below (today's code, untouched).
        // Soundness: a recipe's outcome is a pure function of its transitive closure counts
        // (concrete options via the dependents fixpoint, tag picks via tag propagation +
        // craftable-order diffs), all recomputed inputs (full DP + reachability + snapshot),
        // and static per-model data. Absent-from-previous means zero, same as a fresh map.
        BitSet dirty = null;
        boolean incremental = false;
        if (previous != null && flat != null && totalFlatRecipes > 0
                && previous.snapshot() != null && previous.gridSize() == gridSize
                && model.modelGeneration() == modelGeneration
                && (!previous.counts().isEmpty() || !previous.containerCraftable().isEmpty())) {
            dirty = computeDirty(flat, model.tagIndex(), previous.snapshot(), snapshot,
                    checkContainers, changedTags);
            incremental = dirty.cardinality() <= totalFlatRecipes / 2;
        }
        int dirtyCount = incremental ? dirty.cardinality() : totalFlatRecipes;

        long tReachable = debug ? System.nanoTime() : 0;
        Map<Item, Integer> reachableSnapshot = checkContainers && !contSnapshot.isEmpty()
                ? combined
                : invSnapshot;
        Set<Item> reachableItems = Reachability.compute(graph, reachableSnapshot, gridSize);
        long reachableNs = debug ? System.nanoTime() - tReachable : 0;

        // Flat physical-inventory counts: one conversion per compute replaces per-option
        // map hashing in the fused direct-count scan (millions of hashes in large packs).
        // Non-graph items (practically none — every ingredient is a graph node) ride along
        // in a tiny overflow map so outcomes stay bit-identical to the map version.
        int[] invCounts = new int[flat != null ? flat.n() : 0];
        Map<Item, Integer> invOverflow = null;
        if (flat != null) {
            for (Map.Entry<Item, Integer> e : invSnapshot.entrySet()) {
                Integer id = flat.idMap().get(e.getKey());
                if (id != null) {
                    invCounts[id] += e.getValue();
                } else {
                    if (invOverflow == null) invOverflow = new HashMap<>();
                    invOverflow.merge(e.getKey(), e.getValue(), Integer::sum);
                }
            }
        }

        // Flat reachability bits: one conversion per compute replaces per-option
        // HashSet hashing in the edge-reachability scans. The set itself is kept for
        // the slot path and as the overflow source for non-graph options (see above).
        boolean[] reachableFlat = new boolean[flat != null ? flat.n() : 0];
        if (flat != null) {
            for (Item item : reachableItems) {
                Integer id = flat.idMap().get(item);
                if (id != null) reachableFlat[id] = true;
            }
        }

        // DP counts arrive indexed by recIdx straight from the estimator — no boxed map,
        // no conversion pass (see DpEstimator).
        int[] treeCountByRec = new int[totalFlatRecipes];
        int[] treeCombinedCountByRec = new int[totalFlatRecipes];
        if (graph != null) {
            long tTreeCompute = debug ? System.nanoTime() : 0;
            treeCountByRec = DpEstimator.calculatePerRecipeCounts(
                    graph, invSnapshot, Map.of(), gridSize, Constants.MAX_REPEATS);
            if (checkContainers) {
                treeCombinedCountByRec = DpEstimator.calculatePerRecipeCounts(
                        graph, invSnapshot, contSnapshot, gridSize, Constants.MAX_REPEATS);
            }
            if (debug) treeComputeNs = System.nanoTime() - tTreeCompute;
        }

        long tVerify = debug ? System.nanoTime() : 0;
        // Incremental merge: pre-populate from the previous result, then evict dirty keys
        // (absent means zero, identical to a fresh map). The loop below only (re)computes
        // dirty entries; collAllEntries still assembles fully (cheap, needed by assembly).
        Map<RecipeDisplayId, Integer> resolvedCounts;
        Set<RecipeDisplayId> containerSet;
        if (incremental) {
            resolvedCounts = new HashMap<>((int) (previous.counts().size() / 0.75f) + 16);
            resolvedCounts.putAll(previous.counts());
            containerSet = new HashSet<>(previous.containerCraftable());
            for (int ri = dirty.nextSetBit(0); ri >= 0; ri = dirty.nextSetBit(ri + 1)) {
                RecipeDisplayId did = flat.recDispId()[ri];
                resolvedCounts.remove(did);
                containerSet.remove(did);
            }
        } else {
            resolvedCounts = new HashMap<>((int) (totalFlatRecipes / 0.75f) + 16);
            containerSet = new HashSet<>(64);
        }
        List<List<RecipeDisplayEntry>> collAllEntries = new ArrayList<>(allCrafting.size());

        // Scratch resolver state for the quickCountMode slot-display path, reused
        // across entries instead of a fresh WorkMap (full inventory re-sort) and
        // ResolveContext per recipe.
        ResolveContext quickCtx = ClientCraftConfig.quickCountMode ? ResolveContext.of(model, gridSize) : null;
        WorkMap quickWork = quickCtx != null ? WorkMap.from(invSnapshot, graph) : null;
        Set<Item> quickInProgress = quickCtx != null ? new HashSet<>() : null;

        for (RecipeCollection coll : allCrafting) {
            List<RecipeDisplayEntry> allEntries = new ArrayList<>(coll.getRecipes().size());

            for (RecipeDisplayEntry entry : coll.getRecipes()) {
                int recIdx = flat != null ? flat.dispIdToRecIdx().getOrDefault(entry.id(), -1) : -1;

                if (!ClientCraftConfig.quickCountMode && recIdx >= 0) {
                    // === FLAT ARRAY PATH: zero SlotDisplay processing ===
                    if (flat.recGridSize()[recIdx] > gridSize) continue;
                    if (flat.recSelfConsuming()[recIdx]) continue;

                    // Incremental clean-skip: inputs provably unchanged, published value stands.
                    if (incremental && !dirty.get(recIdx)) {
                        allEntries.add(entry);
                        totalRecipes++;
                        reused++;
                        continue;
                    }

                    int count = treeCountByRec[recIdx];
                    boolean cycleSuspect = flat.recCycleSuspect()[recIdx];

                    if (count == 0 && !checkContainers && !cycleSuspect
                            && !Reachability.allEdgesReachableFlat(flat, recIdx, reachableFlat, reachableItems)) {
                        allEntries.add(entry);
                        totalRecipes++;
                        treeSkipped++;
                        continue;
                    }

                    allEntries.add(entry);
                    totalRecipes++;

                    int outputCount = flat.recOutCount()[recIdx];

                    if (count > 0) {
                        // The tree DP can both over-count (intra-edge option sharing,
                        // cross-edge sharing) and under-count (cycle-avoidance hides
                        // nuggets→ingot→block chains). Perf gate: when the DP is provably
                        // exact — not cycle-flagged, no sharing-suspect edge, no item
                        // shared across distinct edges (both DP and directCountFlat would
                        // double-count it), and the physical inventory alone already
                        // satisfies the DP count — trust it; otherwise the exact
                        // simulator is the ground truth.
                        // NOTE: no separate allDirectlyAvailableFlat pass — directCountFlat
                        // returns 0 unless every edge has stock, so a single fused scan
                        // covers both checks (directCount > 0 implies all-direct, and
                        // directCount == 0 fails the >= count gate for count > 0 anyway).
                        // The direct value is reused below as the simulator's lo seed.
                        int direct = directCountFlat(flat, recIdx, invCounts, invOverflow);
                        boolean dpExact = !cycleSuspect
                                && !flat.recSharingSuspect()[recIdx]
                                && !flat.recCrossEdgeShared()[recIdx]
                                && direct >= count;
                        // First-false attribution matches the && short-circuit order above.
                        // Debug-only: a predictable-false branch + increments, ~1ns/recipe off.
                        if (debug) {
                            if (!dpExact) {
                                if (cycleSuspect) gateCycle++;
                                else if (flat.recSharingSuspect()[recIdx]) gateSharing++;
                                else if (flat.recCrossEdgeShared()[recIdx]) gateCross++;
                                else gateDirect++;
                            }
                        }
                        if (dpExact) {
                            resolvedCounts.put(entry.id(), count);
                            treeCounted++;
                            dpExactCount++;
                        } else {
                            // Reachability prescreen: unreachable edges prove exact == 0
                            // (reachability is complete over fitting recipes), so the
                            // ~10-attempt binary search is skipped, not just shortened.
                            int exact = 0;
                            if (Reachability.allEdgesReachableFlat(flat, recIdx, reachableFlat, reachableItems)) {
                                int hi = simulateUpperBound(flat, recIdx, outputCount, count, cycleSuspect);
                                exact = ExactSimulator.simulateCraftCount(
                                                model, gridSize, entry, invSnapshot, hi,
                                                direct / Math.max(1, outputCount))
                                        * outputCount;
                                simulatedCount++;
                            }
                            if (exact > 0) {
                                resolvedCounts.put(entry.id(), Math.min(exact, Constants.MAX_REPEATS));
                                treeCounted++;
                            } else if (checkContainers && treeCombinedCountByRec[recIdx] > 0
                                    && ExactSimulator.tryResolveOnce(model, gridSize, entry, combined)) {
                                containerSet.add(entry.id());
                                containerChecked++;
                            }
                        }
                    } else {
                        // DP under-counted (cycle-avoidance hid a sub-craft path, e.g.
                        // 9 gold nuggets + 8 ingots can craft a gold block, or the
                        // straw -> tall dry grass -> short dry grass -> torch chain).
                        // Verify whenever every edge is reachable — the cycle flag
                        // alone misses recipes whose sub-craft chain passes through a
                        // cycle without the recipe's own output being part of it.
                        boolean edgesReachable = Reachability.allEdgesReachableFlat(flat, recIdx, reachableFlat, reachableItems);
                        if (edgesReachable) {
                            int exact = ExactSimulator.simulateCraftCount(
                                    model, gridSize, entry, invSnapshot, Constants.MAX_REPEATS) * outputCount;
                            if (exact > 0) {
                                resolvedCounts.put(entry.id(), Math.min(exact, Constants.MAX_REPEATS));
                                treeCounted++;
                            } else if (checkContainers && treeCombinedCountByRec[recIdx] > 0
                                    && ExactSimulator.tryResolveOnce(model, gridSize, entry, combined)) {
                                containerSet.add(entry.id());
                                containerChecked++;
                            }
                        } else if (checkContainers) {
                            int contCount = treeCombinedCountByRec[recIdx];
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
                                } else if (ExactSimulator.tryResolveOnce(model, gridSize, entry, combined)) {
                                    containerSet.add(entry.id());
                                    containerChecked++;
                                }
                            }
                        }
                    }
                } else {
                    // === SLOTDISPLAY PATH: quickCountMode or recipe not in the tree ===
                    if (!RecipeDisplays.fitsInGrid(entry.display(), gridSize)) continue;

                    if (!ClientCraftConfig.quickCountMode) {
                        allEntries.add(entry);
                        totalRecipes++;
                        treeSkipped++;
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
                    quickWork.resetTo(invSnapshot, graph);
                    quickInProgress.clear();
                    if (quickCtx.resolve(entry, quickWork, null, quickInProgress, 0, null)) {
                        resolvedCounts.put(entry.id(), outputCount);
                    }
                }
            }

            collAllEntries.add(allEntries);
        }

        if (debug) verifyNs = System.nanoTime() - tVerify;

        // Container-available items derived from the final container set (identical to the
        // old per-hit accumulation: exactly the outputs of container-craftable recipes).
        Set<Item> containerItemSet = new HashSet<>();
        if (checkContainers && flat != null) {
            for (RecipeDisplayId id : containerSet) {
                Integer ri = flat.dispIdToRecIdx().get(id);
                if (ri == null) continue;
                int outId = flat.recOutId()[ri];
                if (outId >= 0) containerItemSet.add(flat.idToItem()[outId]);
            }
        }

        long totalNs = 0;
        if (debug) {
            totalNs = System.nanoTime() - t0;
            LOG.info("[CC] Resolve: {}us | {} recipes | reachable: {}us ({} items) | model: {}us tree: {}us verify: {}us | counted:{} preSkip:{} treeSkip:{} cont:{} | dpExact:{} sim:{} | gate:cyc{} shr{} crs{} dir{} | {} dirty:{} reused:{}",
                    totalNs / 1_000, totalRecipes,
                    reachableNs / 1_000, reachableItems.size(),
                    modelNs / 1_000, treeComputeNs / 1_000, verifyNs / 1_000,
                    treeCounted, preCheckSkipped, treeSkipped, containerChecked,
                    dpExactCount, simulatedCount, gateCycle, gateSharing, gateCross, gateDirect,
                    incremental ? "incr" : "full", dirtyCount, reused);
        }

        return new CraftCounts(
                resolvedCounts, containerSet, containerItemSet, collAllEntries,
                new CraftCounts.Stats(totalNs, treeCounted, preCheckSkipped, treeSkipped, containerChecked));
    }

    /**
     * Value-diffed dirty recipe set between two snapshots (see the incremental block in
     * {@link #compute}): changed items (both directions, values compared) seed the item
     * dependents fixpoint; their tags plus the craftable-order diff tags union in.
     * Container diffs apply only when containers participate in counting.
     */
    private static BitSet computeDirty(GraphFlatData f, TagIndex tags,
                                       InventorySnapshot prevSnap, InventorySnapshot snap,
                                       boolean checkContainers, Set<TagKey<Item>> changedTags) {
        Map<Item, Integer> prevInv = prevSnap.inventory();
        Map<Item, Integer> inv = snap.inventory();
        List<Item> changedItems = new ArrayList<>();
        for (Map.Entry<Item, Integer> e : inv.entrySet()) {
            if (!Objects.equals(e.getValue(), prevInv.get(e.getKey()))) changedItems.add(e.getKey());
        }
        for (Item k : prevInv.keySet()) {
            if (!inv.containsKey(k)) changedItems.add(k);
        }
        if (checkContainers) {
            Map<Item, Integer> prevCont = prevSnap.container();
            Map<Item, Integer> cont = snap.container();
            for (Map.Entry<Item, Integer> e : cont.entrySet()) {
                if (!Objects.equals(e.getValue(), prevCont.get(e.getKey()))) changedItems.add(e.getKey());
            }
            for (Item k : prevCont.keySet()) {
                if (!cont.containsKey(k)) changedItems.add(k);
            }
        }
        int[] seeds = new int[changedItems.size()];
        List<int[]> extras = new ArrayList<>();
        Set<TagKey<Item>> seenTags = new HashSet<>();
        int s = 0;
        for (Item item : changedItems) {
            Integer id = f.idMap().get(item);
            if (id != null) seeds[s++] = id;
            // Non-graph items skip seeding (in no edge) but still propagate their tags.
            for (TagKey<Item> tag : tags.tagsOf(item)) {
                if (seenTags.add(tag)) {
                    int[] arr = f.tagRecIdx().get(tag);
                    if (arr != null) extras.add(arr);
                }
            }
        }
        if (changedTags != null) {
            for (TagKey<Item> tag : changedTags) {
                if (seenTags.add(tag)) {
                    int[] arr = f.tagRecIdx().get(tag);
                    if (arr != null) extras.add(arr);
                }
            }
        }
        if (s < seeds.length) seeds = Arrays.copyOf(seeds, s);
        return DirtySet.dirtyRecipes(f.totalRecipes(), f.n(), seeds, f.dependentItems(),
                f.itemRecStart(), f.itemRecEnd(), f.itemRecFlat(), extras);
    }

    /** Exact direct (no sub-crafting) craftable item count using physical inventory only. */
    private static int directCountFlat(GraphFlatData f, int recIdx, int[] invCounts,
                                       Map<Item, Integer> invOverflow) {
        long maxOps = Long.MAX_VALUE;
        for (int ei = f.recEdgeStart()[recIdx]; ei < f.recEdgeEnd()[recIdx]; ei++) {
            long avail = 0;
            for (int oi = f.edgeOptStart()[ei]; oi < f.edgeOptEnd()[ei]; oi++) {
                int oid = f.optItemId()[oi];
                if (oid >= 0) {
                    avail += invCounts[oid];
                } else if (invOverflow != null) {
                    // Non-graph option: map fallback (practically unreachable — every
                    // ingredient is a graph node — but bit-identical when it happens).
                    avail += invOverflow.getOrDefault(f.optItemObj()[oi], 0);
                }
            }
            maxOps = Math.min(maxOps, avail / f.edgeCnt()[ei]);
            if (maxOps == 0) break;
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
