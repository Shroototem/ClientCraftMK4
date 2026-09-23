package com.clientcraftmk4.ui;

import com.clientcraftmk4.pipeline.ResolvePipeline;
import com.clientcraftmk4.pipeline.ResolveResult;
import net.minecraft.client.gui.screens.recipebook.RecipeCollection;
import net.minecraft.world.item.crafting.display.RecipeDisplayId;

/**
 * Read-side helpers for result-button rendering (plan §15.1). Prefer the
 * {@link ResolveResult}-taking overloads with a single {@link ResolvePipeline#current()}
 * snapshot per button/frame: one volatile read instead of three, and a consistent
 * view even if the worker publishes mid-frame.
 */
public final class ResultButtonRenderer {
    private ResultButtonRenderer() {}

    public static ResolveResult snapshot() {
        return ResolvePipeline.current();
    }

    public static boolean isAutoCraftCollection(ResolveResult r, RecipeCollection collection) {
        return r.autoCraftCollections().contains(collection);
    }

    public static int getCraftCount(ResolveResult r, RecipeDisplayId id) {
        return r.counts().getOrDefault(id, 0);
    }

    public static boolean isContainerCraftable(ResolveResult r, RecipeDisplayId id) {
        return r.containerCraftable().contains(id);
    }

    public static int getCollectionRank(ResolveResult r, RecipeCollection coll) {
        return r.ranks().getOrDefault(coll, 2);
    }

    public static boolean isAutoCraftCollection(RecipeCollection collection) {
        return isAutoCraftCollection(ResolvePipeline.current(), collection);
    }

    public static int getCraftCount(RecipeDisplayId id) {
        return getCraftCount(ResolvePipeline.current(), id);
    }

    public static boolean isContainerCraftable(RecipeDisplayId id) {
        return isContainerCraftable(ResolvePipeline.current(), id);
    }

    public static int getCollectionRank(RecipeCollection coll) {
        return getCollectionRank(ResolvePipeline.current(), coll);
    }
}
