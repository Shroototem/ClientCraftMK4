package com.clientcraftmk4.core;

import org.junit.jupiter.api.Test;

import java.util.BitSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Propagation logic of {@link DirtySet} over synthetic flat graphs — pure integers,
 * no Minecraft state. Graph fixture used below (item ids in brackets):
 *
 * <pre>
 *   [0] base ──► [1] mid ──► [2] top
 *      ▲                    ▲
 *      └──── [3] side ──────┘
 *   [4] isolated (own recipe)
 * </pre>
 *
 * Recipes: r0 produces [1], r1 produces [2], r2 produces [3], r3 produces [4].
 * Dependents: [0]→{[1],[3]}, [1]→{[2]}, [3]→{[2]}, [2]→{}, [4]→{}.
 */
class DirtySetTest {
    private static final int N_ITEMS = 5;
    private static final int N_RECIPES = 4;
    private static final int[][] DEPENDENTS = {
            {1, 3}, {2}, {}, {2}, {},
    };
    // itemRecFlat: item -> recipes producing it.
    private static final int[] REC_START = {0, 0, 1, 2, 3};
    private static final int[] REC_END = {0, 1, 2, 3, 4};
    private static final int[] REC_FLAT = {0, 1, 2, 3};

    private static BitSet dirty(int[] seeds, List<int[]> extras) {
        return DirtySet.dirtyRecipes(N_RECIPES, N_ITEMS, seeds, DEPENDENTS,
                REC_START, REC_END, REC_FLAT, extras);
    }

    @Test
    void changePropagatesTransitivelyToParents() {
        // Base [0] changed: [0] itself has no recipe, but parents [1],[3] and [2] do.
        BitSet d = dirty(new int[]{0}, List.of());
        assertTrue(d.get(0), "recipe producing [1]");
        assertTrue(d.get(2), "recipe producing [3]");
        assertTrue(d.get(1), "recipe producing [2] via transitive parents");
        assertFalse(d.get(3), "isolated recipe stays clean");
    }

    @Test
    void midChangeDoesNotDirtySiblings() {
        BitSet d = dirty(new int[]{1}, List.of());
        assertTrue(d.get(0));
        assertTrue(d.get(1));
        assertFalse(d.get(2));
        assertFalse(d.get(3));
    }

    @Test
    void isolatedChangeStaysLocal() {
        BitSet d = dirty(new int[]{4}, List.of());
        assertEquals(1, d.cardinality());
        assertTrue(d.get(3));
    }

    @Test
    void invalidSeedsIgnored() {
        BitSet d = dirty(new int[]{-1, 99, -50}, List.of());
        assertTrue(d.isEmpty());
    }

    @Test
    void emptySeedsStayEmpty() {
        assertTrue(dirty(new int[0], List.of()).isEmpty());
        assertTrue(dirty(new int[0], null).isEmpty());
    }

    @Test
    void tagExtrasUnionIn() {
        // Tag affecting recipes r1 and r3 unions with the (empty) propagation set.
        BitSet d = dirty(new int[0], List.of(new int[]{1, 3}, new int[]{1}));
        assertFalse(d.get(0));
        assertTrue(d.get(1));
        assertFalse(d.get(2));
        assertTrue(d.get(3));
    }

    @Test
    void outOfRangeRecipeIndicesDropped() {
        BitSet d = dirty(new int[0], List.of(new int[]{-1, 4, 100}));
        assertTrue(d.isEmpty(), "no valid recipe index in the extras");
    }
}
