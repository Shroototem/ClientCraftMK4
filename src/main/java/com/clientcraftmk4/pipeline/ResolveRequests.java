package com.clientcraftmk4.pipeline;

import com.clientcraftmk4.core.GameContext;
import com.clientcraftmk4.core.CraftModel;
import com.clientcraftmk4.core.InventoryProvider;
import com.clientcraftmk4.core.InventorySnapshot;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.recipebook.RecipeCollection;
import net.minecraft.client.gui.screens.recipebook.SearchRecipeBookCategory;

import java.util.List;

/** Builds immutable {@link ResolveRequest}s from the current game state (render/main thread only). */
public final class ResolveRequests {
    private ResolveRequests() {}

    public static ResolveRequest fromContext() {
        int gridSize = GameContext.gridSize();
        CraftModel model = CraftModel.current();
        InventorySnapshot snap = InventoryProvider.current();
        List<RecipeCollection> allCrafting = Minecraft.getInstance().player.getRecipeBook()
                .getCollection(SearchRecipeBookCategory.CRAFTING);
        return new ResolveRequest(allCrafting, gridSize,
                ResolveRequest.cacheKey(snap.generation(), gridSize),
                model != null ? model.modelGeneration() : 0, snap,
                ResolvePipeline.current(), InventoryProvider.lastChangedTags());
    }
}
