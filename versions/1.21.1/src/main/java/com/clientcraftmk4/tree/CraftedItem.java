package com.clientcraftmk4.tree;

import net.minecraft.item.Item;
import net.minecraft.recipe.RecipeEntry;
import net.minecraft.util.Identifier;

import java.util.List;

public record CraftedItem(
        Item item,
        int outputCount,
        List<IngredientEdge> ingredients,
        RecipeEntry<?> recipeEntry,
        Identifier recipeId,
        int gridSize,
        int depth
) implements RecipeNode {}
