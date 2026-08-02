package com.clientcraftmk4.ui;

import net.minecraft.world.item.crafting.ExtendedRecipeBookCategory;

/** The custom recipe book tab (MK4's ClientCraftTab singleton). */
public class ClientCraftTab implements ExtendedRecipeBookCategory {
    public static final ClientCraftTab INSTANCE = new ClientCraftTab();

    /** Whether the ClientCraft tab was the last tab the user had open (auto-switch state). */
    public static boolean lastTabWasClientCraft = false;

    private ClientCraftTab() {}
}
