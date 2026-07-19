package com.clientcraftmk4.tree;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.crafting.display.RecipeDisplayId;

import java.util.*;

public class CraftCalculator {

    public static Map<RecipeDisplayId, Integer> calculatePerRecipeCounts(
            RecipeTree tree,
            Map<Item, Integer> inventory,
            Map<Item, Integer> containerInventory,
            int gridSize,
            int maxOutput) {

        RecipeTree.FlatData f = tree.flat();
        if (f == null) return Map.of();

        Ctx ctx = new Ctx(tree, f, inventory, containerInventory, gridSize);
        return ctx.compute(maxOutput);
    }

    private static Map<Item, Integer> combinedInventory(
            Map<Item, Integer> inventory, Map<Item, Integer> containerInventory) {
        Map<Item, Integer> combined = new HashMap<>(inventory);
        if (containerInventory != null)
            containerInventory.forEach((k, v) -> combined.merge(k, v, Integer::sum));
        return combined;
    }

    private static class Ctx {
        final RecipeTree tree;
        final RecipeTree.FlatData f;
        final int gridSize;
        final Map<Item, Integer> combMap;
        final Map<Item, Integer> invMap;
        final long[] comb;
        final long[] inv;
        final long[] memoCount;
        final boolean[] memoSet;
        final boolean[] memoContOnly;
        final long[] dOpsCount;
        final boolean[] dOpsSet;
        int cycleVersion;
        final int[] cycleVersions;

        Ctx(RecipeTree tree, RecipeTree.FlatData f,
            Map<Item, Integer> inventory, Map<Item, Integer> containerInventory,
            int gridSize) {
            this.tree = tree;
            this.f = f;
            this.gridSize = gridSize;
            this.combMap = combinedInventory(inventory, containerInventory);
            this.invMap = inventory;

            int n = f.n();
            IdentityHashMap<Item, Integer> idMap = f.idMap();
            comb = new long[n];
            for (var e : combMap.entrySet()) {
                Integer id = idMap.get(e.getKey());
                if (id != null) comb[id] = e.getValue();
            }
            inv = new long[n];
            for (var e : invMap.entrySet()) {
                Integer id = idMap.get(e.getKey());
                if (id != null) inv[id] = e.getValue();
            }

            memoCount = new long[n];
            memoSet = new boolean[n];
            memoContOnly = new boolean[n];
            dOpsCount = new long[n];
            dOpsSet = new boolean[n];
            cycleVersions = new int[n];
        }

        Map<RecipeDisplayId, Integer> compute(int maxOutput) {
            int n = f.n();
            for (int i = 0; i < n; i++) {
                if (!memoSet[i]) computeMemo(i);
            }

            Map<RecipeDisplayId, Integer> results = new HashMap<>();
            for (int i = 0; i < n; i++) {
                int rs = f.itemRecStart()[i], re = f.itemRecEnd()[i];
                if (rs == re) continue;
                long alreadyHave = comb[i];
                int recipeCount = re - rs;

                if (recipeCount > 1) {
                    long maxNewItems = Math.max(0, memoCount[i] - alreadyHave);
                    if (maxNewItems > 0) {
                        long base = maxNewItems / recipeCount;
                        int rem = (int) (maxNewItems % recipeCount);
                        for (int j = 0; j < recipeCount; j++) {
                            long share = base + (j < rem ? 1 : 0);
                            results.put(f.recDispId()[f.itemRecFlat()[rs + j]],
                                    (int) Math.min(share, maxOutput));
                        }
                        continue;
                    }
                }

                for (int k = rs; k < re; k++) {
                    int ri = f.itemRecFlat()[k];
                    setCycleFlags(ri);
                    long total = computeForRecipe(ri, cycleVersion);
                    long newItems = total - alreadyHave;
                    if (newItems > 0) {
                        results.put(f.recDispId()[ri], (int) Math.min(newItems, maxOutput));
                    }
                }
                cycleVersion++;
            }
            return results;
        }

        private void setCycleFlags(int ri) {
            Set<Item> targets = tree.getReverseDependencyTargets(f.idToItem()[f.recOutId()[ri]]);
            if (targets.isEmpty()) return;
            cycleVersion++;
            for (int ei = f.recEdgeStart()[ri]; ei < f.recEdgeEnd()[ri]; ei++) {
                for (int oi = f.edgeOptStart()[ei]; oi < f.edgeOptEnd()[ei]; oi++) {
                    int oid = f.optItemId()[oi];
                    if (oid >= 0 && targets.contains(f.idToItem()[oid])) {
                        cycleVersions[oid] = cycleVersion;
                    }
                }
            }
        }

        private void computeMemo(int id) {
            if (memoSet[id]) return;

            long baseValue = comb[id];
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

            memoCount[id] = result;
            memoContOnly[id] = containerOnly;
        }

        private long computeForRecipe(int ri, int cycleVersionToUse) {
            if (f.recGridSize()[ri] > gridSize) {
                return comb[f.recOutId()[ri]];
            }

            long maxOps = Long.MAX_VALUE;
            int[] optItemId = f.optItemId();
            Item[] optItemObj = f.optItemObj();
            long[] memoCount = this.memoCount;
            boolean[] memoSet = this.memoSet;
            int[] primaryRecIdx = f.primaryRecIdx();
            long[] comb = this.comb;

            for (int ei = f.recEdgeStart()[ri]; ei < f.recEdgeEnd()[ri]; ei++) {
                long avail = 0;

                for (int oi = f.edgeOptStart()[ei]; oi < f.edgeOptEnd()[ei]; oi++) {
                    int oid = optItemId[oi];
                    if (oid < 0) {
                        avail += combMap.getOrDefault(optItemObj[oi], 0);
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
            long[] comb = this.comb;

            for (int ei = f.recEdgeStart()[ri]; ei < f.recEdgeEnd()[ri]; ei++) {
                long avail = 0;
                for (int oi = f.edgeOptStart()[ei]; oi < f.edgeOptEnd()[ei]; oi++) {
                    int oid = optItemId[oi];
                    if (oid >= 0) {
                        avail += comb[oid];
                    } else {
                        avail += combMap.getOrDefault(optItemObj[oi], 0);
                    }
                }
                maxOps = Math.min(maxOps, avail / f.edgeCnt()[ei]);
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
                        available += invMap.getOrDefault(f.optItemObj()[oi], 0);
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
            dOpsCount[id] = result;
            return result;
        }
    }
}
