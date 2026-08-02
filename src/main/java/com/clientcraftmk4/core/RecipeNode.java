package com.clientcraftmk4.core;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.crafting.display.RecipeDisplay;

import java.util.List;

/** A node in the recipe graph: either a {@link BaseResource} or a {@link CraftedItem}. */
public sealed interface RecipeNode permits BaseResource, CraftedItem {
    Item item();
}
