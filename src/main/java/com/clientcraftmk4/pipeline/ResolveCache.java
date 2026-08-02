package com.clientcraftmk4.pipeline;

import com.clientcraftmk4.core.CraftModel;
import com.clientcraftmk4.core.InventoryProvider;

/**
 * One-stop cache reset (MK4's {@code RecipeResolver.clearCache}): world leave,
 * config-screen Done, and disconnect all funnel through here (plan §8.7).
 */
public final class ResolveCache {
    private ResolveCache() {}

    public static void clearAll() {
        CraftModel.reset();
        InventoryProvider.reset();
        ResolvePipeline.reset();
    }
}
