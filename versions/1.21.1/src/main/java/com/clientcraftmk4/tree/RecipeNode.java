package com.clientcraftmk4.tree;

import net.minecraft.item.Item;

public sealed interface RecipeNode permits BaseResource, CraftedItem {
    Item item();
}
