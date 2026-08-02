package com.clientcraftmk4.core;

import net.minecraft.world.item.Item;

public record IngredientOption(
        Item item,
        RecipeNode node
) {}
