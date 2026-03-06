package com.clientcraftmk4.tree;

import net.minecraft.item.Item;

public record IngredientOption(
        Item item,
        RecipeNode node
) {}
