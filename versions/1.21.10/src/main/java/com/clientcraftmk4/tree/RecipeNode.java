package com.clientcraftmk4.tree;

import net.minecraft.item.Item;
import net.minecraft.recipe.NetworkRecipeId;
import net.minecraft.recipe.RecipeDisplayEntry;

import java.util.List;

public sealed interface RecipeNode permits BaseResource, CraftedItem {
    Item item();
}
