package com.clientcraftmk4;

import net.minecraft.client.recipebook.RecipeBookGroup;

/**
 * In 1.21.1 RecipeBookGroup is an enum, so we can't create a custom instance.
 * We reuse UNKNOWN as our sentinel value — it's unused by vanilla.
 */
public class ClientCraftTab {
    public static final RecipeBookGroup INSTANCE = RecipeBookGroup.UNKNOWN;
    private ClientCraftTab() {}
}
