package com.clientcraftmk4.pipeline;

import net.minecraft.client.gui.screens.recipebook.RecipeCollection;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.crafting.display.RecipeDisplayId;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The complete, immutable result snapshot published by the pipeline (plan §4.4).
 * Replaces MK4's ten volatile fields: readers do one volatile read of
 * {@link ResolvePipeline#current()} and get a fully consistent snapshot.
 */
public record ResolveResult(
        List<RecipeCollection> collections,
        Map<RecipeDisplayId, Integer> counts,
        Set<RecipeDisplayId> containerCraftable,
        Set<Item> containerAvailableItems,
        Map<RecipeCollection, Integer> ranks,
        Set<RecipeCollection> autoCraftCollections,
        long cacheKey,
        long inventoryGeneration,
        long modelGeneration
) {
    public static final ResolveResult EMPTY = new ResolveResult(
            List.of(), Map.of(), Set.of(), Set.of(), Map.of(), Set.of(), 0, 0, 0);

    /** Returns a copy with a different collection list (used for the first-open placeholder). */
    public ResolveResult withCollections(List<RecipeCollection> c) {
        return new ResolveResult(c, counts, containerCraftable, containerAvailableItems,
                ranks, autoCraftCollections, cacheKey, inventoryGeneration, modelGeneration);
    }
}
