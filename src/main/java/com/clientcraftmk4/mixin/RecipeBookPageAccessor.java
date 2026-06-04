package com.clientcraftmk4.mixin;

import net.minecraft.client.gui.screens.recipebook.OverlayRecipeComponent;
import net.minecraft.client.gui.screens.recipebook.RecipeBookPage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(RecipeBookPage.class)
public interface RecipeBookPageAccessor {

    @Accessor("overlay")
    OverlayRecipeComponent getOverlay();
}
