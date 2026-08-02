package com.clientcraftmk4.ui;

import net.minecraft.client.gui.screens.recipebook.RecipeBookPage;

/** Lets the scroll handler and page mixin cycle recipe book pages. */
public interface RecipeBookPageCycleAccessor {
    void clientcraft$cyclePage(int delta);
}
