package com.clientcraftmk4.core;

import java.util.BitSet;
import java.util.List;

/**
 * Dirty-recipe computation for incremental resolves — flat integer ids only, vanilla-free
 * so it can be unit tested in isolation.
 *
 * <p>A recipe's outcome can change only if the availability of something in its transitive
 * ingredient closure changed (concrete options propagate through the item dependents graph;
 * tag satisfaction propagates through the caller-supplied tag→recipe sets, which cover both
 * new picks and craftable-order flips). Everything else provably recomputes identically, so
 * clean recipes reuse their published counts.
 */
public final class DirtySet {
    private DirtySet() {}

    /**
     * @param totalRecipes  flat recipe count (dirty set indexes it)
     * @param nItems        flat item count (seed/visited arrays size it)
     * @param seedItemIds   changed flat item ids (invalid ids ignored)
     * @param dependentItems per item id: parent output item ids (order irrelevant — fixpoint)
     * @param itemRecStart  per item id: start offset into {@code itemRecFlat}
     * @param itemRecEnd    per item id: end offset into {@code itemRecFlat}
     * @param itemRecFlat   flat recipe indices producing each item
     * @param extraRecipeSets additional dirty recipe sets (tag-derived), unioned in
     * @return dirty recipe indices
     */
    public static BitSet dirtyRecipes(int totalRecipes, int nItems, int[] seedItemIds,
                                      int[][] dependentItems,
                                      int[] itemRecStart, int[] itemRecEnd, int[] itemRecFlat,
                                      List<int[]> extraRecipeSets) {
        boolean[] dirtyItem = new boolean[nItems];
        int[] stack = new int[nItems];
        int top = 0;
        for (int s : seedItemIds) {
            if (s >= 0 && s < nItems && !dirtyItem[s]) {
                dirtyItem[s] = true;
                stack[top++] = s;
            }
        }
        while (top > 0) {
            int cur = stack[--top];
            if (cur >= dependentItems.length) continue;
            int[] parents = dependentItems[cur];
            if (parents == null) continue;
            for (int p : parents) {
                if (p >= 0 && p < nItems && !dirtyItem[p]) {
                    dirtyItem[p] = true;
                    stack[top++] = p;
                }
            }
        }
        BitSet dirty = new BitSet(totalRecipes);
        int items = Math.min(nItems, itemRecStart.length);
        if (itemRecEnd.length < items) items = itemRecEnd.length;
        for (int i = 0; i < items; i++) {
            if (!dirtyItem[i]) continue;
            for (int k = itemRecStart[i]; k < itemRecEnd[i]; k++) {
                int ri = itemRecFlat[k];
                if (ri >= 0 && ri < totalRecipes) dirty.set(ri);
            }
        }
        if (extraRecipeSets != null) {
            for (int[] arr : extraRecipeSets) {
                if (arr == null) continue;
                for (int ri : arr) {
                    if (ri >= 0 && ri < totalRecipes) dirty.set(ri);
                }
            }
        }
        return dirty;
    }
}
