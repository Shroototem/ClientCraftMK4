package com.clientcraftmk4.tree;

import net.minecraft.world.item.Item;

public record IngredientOption(
        Item item,
        RecipeNode node
) {}
