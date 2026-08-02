package com.clientcraftmk4.pipeline;

import com.clientcraftmk4.core.GameContext;
import com.clientcraftmk4.core.CraftModel;
import com.clientcraftmk4.core.InventoryProvider;
import com.clientcraftmk4.core.InventorySnapshot;
import net.minecraft.client.ClientRecipeBook;

/** Builds immutable {@link ResolveRequest}s from the current game state. */
public final class ResolveRequests {
    private ResolveRequests() {}

    public static ResolveRequest fromContext(ClientRecipeBook book) {
        int gridSize = GameContext.gridSize();
        CraftModel model = CraftModel.current();
        InventorySnapshot snap = InventoryProvider.current();
        long cacheKey = snap.generation() * 7L + gridSize;
        return new ResolveRequest(book, gridSize, cacheKey,
                model != null ? model.modelGeneration() : 0, snap);
    }
}
