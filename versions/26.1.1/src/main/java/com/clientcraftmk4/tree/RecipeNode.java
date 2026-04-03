package com.clientcraftmk4.tree;

import net.minecraft.world.item.Item;

public sealed interface RecipeNode permits BaseResource, CraftedItem {
    Item item();
}
