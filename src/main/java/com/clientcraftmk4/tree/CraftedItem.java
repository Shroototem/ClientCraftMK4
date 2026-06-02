package com.clientcraftmk4.tree;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.crafting.display.RecipeDisplayId;
import net.minecraft.world.item.crafting.display.RecipeDisplayEntry;

import java.util.List;

public record CraftedItem(
        Item item,
        int outputCount,
        List<IngredientEdge> ingredients,
        RecipeDisplayEntry recipeEntry,
        RecipeDisplayId recipeId,
        int gridSize,
        int depth
) implements RecipeNode {}
