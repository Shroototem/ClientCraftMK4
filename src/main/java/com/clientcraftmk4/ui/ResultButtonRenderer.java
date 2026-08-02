package com.clientcraftmk4.ui;

import com.clientcraftmk4.pipeline.ResolvePipeline;
import net.minecraft.client.gui.screens.recipebook.RecipeCollection;
import net.minecraft.world.item.crafting.display.RecipeDisplayId;

/**
 * Read-side helpers for result-button rendering (plan §15.1). Each call does a
 * single volatile read of {@link ResolvePipeline#current()} — fully consistent.
 */
public final class ResultButtonRenderer {
    private ResultButtonRenderer() {}

    public static boolean isAutoCraftCollection(RecipeCollection collection) {
        return ResolvePipeline.current().autoCraftCollections().contains(collection);
    }

    public static int getCraftCount(RecipeDisplayId id) {
        return ResolvePipeline.current().counts().getOrDefault(id, 0);
    }

    public static boolean isContainerCraftable(RecipeDisplayId id) {
        return ResolvePipeline.current().containerCraftable().contains(id);
    }

    public static int getCollectionRank(RecipeCollection coll) {
        return ResolvePipeline.current().ranks().getOrDefault(coll, 2);
    }
}
