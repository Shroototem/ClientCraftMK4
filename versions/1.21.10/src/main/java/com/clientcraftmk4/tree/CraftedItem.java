package com.clientcraftmk4.tree;

import net.minecraft.item.Item;
import net.minecraft.recipe.NetworkRecipeId;
import net.minecraft.recipe.RecipeDisplayEntry;

import java.util.List;

public record CraftedItem(
        Item item,
        int outputCount,
        List<IngredientEdge> ingredients,
        RecipeDisplayEntry recipeEntry,
        NetworkRecipeId recipeId,
        int gridSize,
        int depth
) implements RecipeNode {}
