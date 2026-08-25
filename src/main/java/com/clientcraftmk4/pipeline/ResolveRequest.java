package com.clientcraftmk4.pipeline;

import com.clientcraftmk4.core.InventorySnapshot;
import net.minecraft.client.gui.screens.recipebook.RecipeCollection;

import java.util.List;

/**
 * Immutable input for one resolve (plan §9.1). Captured on the render thread,
 * consumed on the worker thread. The recipe-collection list is snapshotted at
 * capture time so the worker never touches vanilla {@code ClientRecipeBook}
 * state while the main thread may rebuild it.
 */
public record ResolveRequest(
        List<RecipeCollection> allCrafting,
        int gridSize,
        long cacheKey,
        long modelGeneration,
        InventorySnapshot snapshot
) {
    /**
     * Packs the inventory generation above two grid-size bits. Generations are
     * strictly ordered in the high bits; a same-generation grid-size change
     * still produces a distinct key ({@code drainPending} relies on both
     * properties to tell duplicates apart from environment changes).
     */
    public static long cacheKey(long inventoryGeneration, int gridSize) {
        return inventoryGeneration << 2 | gridSize;
    }

    /** The inventory generation encoded in a {@link #cacheKey(long, int)} value. */
    public static long generationOf(long cacheKey) {
        return cacheKey >> 2;
    }

    /** The grid size encoded in a {@link #cacheKey(long, int)} value. */
    public static int gridSizeOf(long cacheKey) {
        return (int) (cacheKey & 3);
    }
}
