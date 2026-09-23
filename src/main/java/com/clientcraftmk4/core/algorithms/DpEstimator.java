package com.clientcraftmk4.core.algorithms;

import com.clientcraftmk4.core.RecipeGraph.GraphFlatData;
import com.clientcraftmk4.core.RecipeGraph;
import net.minecraft.world.item.Item;

import java.util.*;

/**
 * Fast approximate per-recipe craft-count DP — a byte-equivalent port of MK4's
 * CraftCalculator over the flat arrays (plan §6). It can over-count
 * (intra-edge option sharing, cross-edge sharing) and under-count
 * (cycle-avoidance); {@link com.clientcraftmk4.core.CountEngine} decides per
 * recipe whether to trust it via the static {@code recCycleSuspect} /
 * {@code recSharingSuspect} flags and falls back to the exact simulator.
 */
public final class DpEstimator {

    /**
     * Per-recipe craft counts indexed by flat {@code recIdx} (not a boxed
     * {@code Map<RecipeDisplayId, Integer>}: the old map allocated an entry + Integer
     * per craftable recipe twice per resolve, only to be re-indexed by the caller).
     * Values are capped at {@code maxOutput}; unset recipes read 0.
     */
    public static int[] calculatePerRecipeCounts(
            RecipeGraph graph,
            Map<Item, Integer> inventory,
            Map<Item, Integer> containerInventory,
            int gridSize,
            int maxOutput) {

        GraphFlatData f = graph.flat();
        if (f == null) return new int[0];

        Ctx ctx = new Ctx(graph, f, inventory, containerInventory, gridSize);
        return ctx.compute(maxOutput);
    }

    private static class Ctx {
        final GraphFlatData f;
        final int gridSize;
        // Items outside the graph (oid<0) are rare; their counts live here instead of a
        // full combined-inventory HashMap copy per compute (old combinedInventory rehashed
        // the whole inventory + container on every call, 2x/resolve with searchContainers).
        final Map<Item, Integer> overflowComb;
        final Map<Item, Integer> overflowInv;
        // int[] throughout: no count here can approach 2^31 (downstream caps at
        // MAX_REPEATS), and halved array widths double how much fits in cache —
        // significant when modpacks push n into the tens of thousands.
        final int[] comb;
        final int[] inv;
        final int[] memoCount;
        final boolean[] memoSet;
        final boolean[] memoContOnly;
        final int[] dOpsCount;
        final boolean[] dOpsSet;
        int cycleVersion;
        final int[] cycleVersions;

        /** Clamp-on-store: keeps int[] storage exact for every reachable value. */
        private static int saturate(long v) {
            return v >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) v;
        }

        Ctx(RecipeGraph graph, GraphFlatData f,
            Map<Item, Integer> inventory, Map<Item, Integer> containerInventory,
            int gridSize) {
            this.f = f;
            this.gridSize = gridSize;

            int n = f.n();
            IdentityHashMap<Item, Integer> idMap = f.idMap();
            comb = new int[n];
            inv = new int[n];
            // Single pass over each map directly into the flat arrays — no intermediate
            // combined HashMap copy and no second hashing of the same keys.
            Map<Item, Integer> ovComb = null;
            Map<Item, Integer> ovInv = null;
            for (var e : inventory.entrySet()) {
                Integer id = idMap.get(e.getKey());
                if (id != null) {
                    comb[id] += e.getValue();
                    inv[id] += e.getValue();
                } else {
                    if (ovComb == null) { ovComb = new HashMap<>(); ovInv = new HashMap<>(); }
                    ovComb.merge(e.getKey(), e.getValue(), Integer::sum);
                    ovInv.merge(e.getKey(), e.getValue(), Integer::sum);
                }
            }
            if (containerInventory != null) {
                for (var e : containerInventory.entrySet()) {
                    Integer id = idMap.get(e.getKey());
                    if (id != null) {
                        comb[id] += e.getValue();
                    } else {
                        if (ovComb == null) { ovComb = new HashMap<>(); ovInv = new HashMap<>(); }
                        ovComb.merge(e.getKey(), e.getValue(), Integer::sum);
                    }
                }
            }
            this.overflowComb = ovComb != null ? ovComb : Map.of();
            this.overflowInv = ovInv != null ? ovInv : Map.of();

            memoCount = new int[n];
            memoSet = new boolean[n];
            memoContOnly = new boolean[n];
            dOpsCount = new int[n];
            dOpsSet = new boolean[n];
            cycleVersions = new int[n];
        }

        int[] compute(int maxOutput) {
            int n = f.n();
            for (int i = 0; i < n; i++) {
                if (!memoSet[i]) computeMemo(i);
            }

            int[] results = new int[f.totalRecipes()];
            for (int i = 0; i < n; i++) {
                int rs = f.itemRecStart()[i], re = f.itemRecEnd()[i];
                if (rs == re) continue;
                long alreadyHave = comb[i];

                // Each recipe is counted on its own merits: memoCount[i] is derived
                // from a single primary recipe, so dividing it evenly across all of
                // the item's recipes could hand 0 to a directly-craftable recipe
                // (e.g. yellow_dye_from_wildflowers with 3 wildflowers) and phantom
                // counts to recipes that aren't craftable at all. computeForRecipe
                // below is per-recipe and never under-counts a craftable recipe; any
                // residual over-count is rejected by CountEngine's dpExact gate and
                // corrected by the exact simulator.
                for (int k = rs; k < re; k++) {
                    int ri = f.itemRecFlat()[k];
                    // Skip the per-recipe reverse-target scan for acyclic recipes
                    // (the common case): no targets means version -1 = no physical-only opts.
                    int ver = f.recReverseTargets()[ri].isEmpty() ? -1 : setCycleFlags(ri);
                    long total = computeForRecipe(ri, ver);
                    long newItems = total - alreadyHave;
                    if (newItems > 0) {
                        results[ri] = (int) Math.min(newItems, maxOutput);
                    }
                }
            }
            return results;
        }

        /**
         * Allocates a fresh cycle version for the given recipe and marks the option
         * items that must be treated as physical-inventory-only for this recipe's
         * count (cycle avoidance). Every recipe gets its own version — a stale
         * version would leak physical-only treatment into unrelated recipes and
         * under-count them (version 0 also collides with the unmarked default).
         *
         * @return the fresh version to pass to {@link #computeForRecipe}.
         */
        private int setCycleFlags(int ri) {
            // int wraps to negative after ~2B recipes and would collide with the -1
            // (no-cycle) sentinel and 0 (unmarked) default — reset long before that.
            if (cycleVersion >= Integer.MAX_VALUE - 1024) {
                Arrays.fill(cycleVersions, 0);
                cycleVersion = 0;
            }
            cycleVersion++;
            Set<Item> targets = f.recReverseTargets()[ri];
            if (targets.isEmpty()) return cycleVersion;
            for (int ei = f.recEdgeStart()[ri]; ei < f.recEdgeEnd()[ri]; ei++) {
                for (int oi = f.edgeOptStart()[ei]; oi < f.edgeOptEnd()[ei]; oi++) {
                    int oid = f.optItemId()[oi];
                    if (oid >= 0 && targets.contains(f.idToItem()[oid])) {
                        cycleVersions[oid] = cycleVersion;
                    }
                }
            }
            return cycleVersion;
        }

        private void computeMemo(int id) {
            if (memoSet[id]) return;

            int baseValue = comb[id];
            memoSet[id] = true;
            memoCount[id] = baseValue;

            long result;
            boolean containerOnly = false;

            if (f.isBaseNode()[id]) {
                result = comb[id];
                containerOnly = inv[id] == 0 && result > 0;

                int rs = f.itemRecStart()[id], re = f.itemRecEnd()[id];
                for (int k = rs; k < re; k++) {
                    long altResult = computeForRecipeBaseOnly(f.itemRecFlat()[k]);
                    if (altResult > result) {
                        result = altResult;
                        containerOnly = false;
                    }
                }
            } else {
                int pri = f.primaryRecIdx()[id];
                result = computeForRecipe(pri, -1);

                int rs = f.itemRecStart()[id], re = f.itemRecEnd()[id];
                if (re - rs > 1) {
                    for (int k = rs; k < re; k++) {
                        int altRi = f.itemRecFlat()[k];
                        if (altRi == pri) continue;
                        long altResult = computeForRecipe(altRi, -1);
                        result = Math.max(result, altResult);
                    }
                }

                containerOnly = result > 0
                        && inv[id] == 0
                        && isRecipeContainerOnly(pri);

                if (containerOnly) {
                    long directOps = calculateDirectOps(id);
                    if (directOps > 0 || inv[id] > 0) {
                        containerOnly = false;
                    }
                }
            }

            memoCount[id] = saturate(result);
            memoContOnly[id] = containerOnly;
        }

        private long computeForRecipe(int ri, int cycleVersionToUse) {
            if (f.recGridSize()[ri] > gridSize) {
                return comb[f.recOutId()[ri]];
            }

            long maxOps = Long.MAX_VALUE;
            int[] optItemId = f.optItemId();
            Item[] optItemObj = f.optItemObj();
            int[] memoCount = this.memoCount;
            boolean[] memoSet = this.memoSet;
            int[] primaryRecIdx = f.primaryRecIdx();
            int[] comb = this.comb;

            for (int ei = f.recEdgeStart()[ri]; ei < f.recEdgeEnd()[ri]; ei++) {
                long avail = 0;

                for (int oi = f.edgeOptStart()[ei]; oi < f.edgeOptEnd()[ei]; oi++) {
                    int oid = optItemId[oi];
                    if (oid < 0) {
                        avail += overflowComb.getOrDefault(optItemObj[oi], 0);
                        continue;
                    }

                    if (cycleVersionToUse >= 0 && cycleVersions[oid] == cycleVersionToUse) {
                        avail += comb[oid];
                        continue;
                    }

                    if (memoSet[oid]) {
                        avail += memoCount[oid];
                    } else if (primaryRecIdx[oid] >= 0) {
                        computeMemo(oid);
                        avail += memoCount[oid];
                    } else {
                        avail += comb[oid];
                    }
                }

                maxOps = Math.min(maxOps, avail / f.edgeCnt()[ei]);
                if (maxOps == 0) break;
            }

            if (maxOps == Long.MAX_VALUE) maxOps = 0;
            return maxOps * f.recOutCount()[ri] + comb[f.recOutId()[ri]];
        }

        private long computeForRecipeBaseOnly(int ri) {
            if (f.recGridSize()[ri] > gridSize) {
                return comb[f.recOutId()[ri]];
            }

            long maxOps = Long.MAX_VALUE;
            int[] optItemId = f.optItemId();
            Item[] optItemObj = f.optItemObj();
            int[] comb = this.comb;

            for (int ei = f.recEdgeStart()[ri]; ei < f.recEdgeEnd()[ri]; ei++) {
                long avail = 0;
                for (int oi = f.edgeOptStart()[ei]; oi < f.edgeOptEnd()[ei]; oi++) {
                    int oid = optItemId[oi];
                    if (oid >= 0) {
                        avail += comb[oid];
                    } else {
                        avail += overflowComb.getOrDefault(optItemObj[oi], 0);
                    }
                }
                maxOps = Math.min(maxOps, avail / f.edgeCnt()[ei]);
                if (maxOps == 0) break;
            }
            if (maxOps == Long.MAX_VALUE) maxOps = 0;
            return maxOps * f.recOutCount()[ri] + comb[f.recOutId()[ri]];
        }

        private boolean isRecipeContainerOnly(int ri) {
            for (int ei = f.recEdgeStart()[ri]; ei < f.recEdgeEnd()[ri]; ei++) {
                boolean edgeContOnly = true;
                boolean edgeHasAvail = false;
                for (int oi = f.edgeOptStart()[ei]; oi < f.edgeOptEnd()[ei]; oi++) {
                    int oid = f.optItemId()[oi];
                    if (oid >= 0 && memoSet[oid] && memoCount[oid] > 0) {
                        edgeHasAvail = true;
                        if (!memoContOnly[oid]) {
                            edgeContOnly = false;
                            break;
                        }
                    }
                }
                if (edgeContOnly && edgeHasAvail) return true;
            }
            return false;
        }

        private long calculateDirectOps(int id) {
            if (dOpsSet[id]) return dOpsCount[id];
            dOpsSet[id] = true;
            dOpsCount[id] = 0;

            int ri = f.primaryRecIdx()[id];
            if (ri < 0) return inv[id];

            long maxOps = Long.MAX_VALUE;
            for (int ei = f.recEdgeStart()[ri]; ei < f.recEdgeEnd()[ri]; ei++) {
                long available = 0;
                for (int oi = f.edgeOptStart()[ei]; oi < f.edgeOptEnd()[ei]; oi++) {
                    int oid = f.optItemId()[oi];
                    if (oid < 0) {
                        available += overflowInv.getOrDefault(f.optItemObj()[oi], 0);
                        continue;
                    }

                    int priOid = f.primaryRecIdx()[oid];
                    if (priOid < 0) {
                        available += inv[oid];
                    } else {
                        if (f.recGridSize()[priOid] <= gridSize) {
                            long subDirect = calculateDirectOps(oid);
                            available += subDirect * f.recOutCount()[priOid] + inv[oid];
                        } else {
                            available += inv[oid];
                        }
                    }
                }
                maxOps = Math.min(maxOps, available / f.edgeCnt()[ei]);
            }
            long result = maxOps == Long.MAX_VALUE ? 0 : maxOps;
            dOpsCount[id] = saturate(result);
            return result;
        }
    }
}
