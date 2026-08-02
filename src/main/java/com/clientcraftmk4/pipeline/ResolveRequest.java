package com.clientcraftmk4.pipeline;

import com.clientcraftmk4.core.InventorySnapshot;
import net.minecraft.client.ClientRecipeBook;

/**
 * Immutable input for one resolve (plan §9.1). Captured on the render thread,
 * consumed on the worker thread.
 */
public record ResolveRequest(
        ClientRecipeBook recipeBook,
        int gridSize,
        long cacheKey,
        long modelGeneration,
        InventorySnapshot snapshot
) {}
