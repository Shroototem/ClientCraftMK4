package com.clientcraftmk4.pipeline;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Packing semantics of {@link ResolveRequest#cacheKey(long, int)} and the
 * queue-drop decision of {@link ResolvePipeline}: a same-generation grid-size
 * change is an environment change that must re-run, while duplicates and older
 * generations are dropped.
 */
class ResolveRequestCacheKeyTest {

    @Test
    void encodeDecodeRoundtrip() {
        long key = ResolveRequest.cacheKey(1234L, 3);
        assertEquals(1234L, ResolveRequest.generationOf(key));
        assertEquals(3, ResolveRequest.gridSizeOf(key));

        key = ResolveRequest.cacheKey(987654321L, 2);
        assertEquals(987654321L, ResolveRequest.generationOf(key));
        assertEquals(2, ResolveRequest.gridSizeOf(key));
    }

    @Test
    void sameGenerationGridChangesAreDistinct() {
        assertNotEquals(ResolveRequest.cacheKey(42L, 2), ResolveRequest.cacheKey(42L, 3));
    }

    @Test
    void newerGenerationAlwaysDominatesOlderOneRegardlessOfGrid() {
        for (int oldGrid = 0; oldGrid <= 3; oldGrid++) {
            for (int newGrid = 0; newGrid <= 3; newGrid++) {
                assertTrue(
                        ResolveRequest.generationOf(ResolveRequest.cacheKey(51L, newGrid))
                                > ResolveRequest.generationOf(ResolveRequest.cacheKey(50L, oldGrid)),
                        "generation bits must outrank grid bits");
            }
        }
    }

    @Test
    void duplicateOfPublishedStateIsDropped() {
        long key = ResolveRequest.cacheKey(7L, 3);
        assertTrue(ResolvePipeline.shouldDropQueued(key, key));
        assertTrue(ResolvePipeline.shouldDropQueued(
                ResolveRequest.cacheKey(6L, 2), ResolveRequest.cacheKey(7L, 3)),
                "older generation must be dropped");
    }

    @Test
    void sameGenerationDifferentGridIsKept() {
        assertFalse(ResolvePipeline.shouldDropQueued(
                ResolveRequest.cacheKey(7L, 2), ResolveRequest.cacheKey(7L, 3)),
                "grid-size change with unchanged inventory is a real environment change");
        assertFalse(ResolvePipeline.shouldDropQueued(
                ResolveRequest.cacheKey(7L, 3), ResolveRequest.cacheKey(7L, 2)));
    }

    @Test
    void newerGenerationIsAlwaysKept() {
        assertFalse(ResolvePipeline.shouldDropQueued(
                ResolveRequest.cacheKey(8L, 2), ResolveRequest.cacheKey(7L, 3)));
    }
}
