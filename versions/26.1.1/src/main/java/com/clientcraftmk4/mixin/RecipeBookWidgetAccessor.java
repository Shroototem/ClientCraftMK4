package com.clientcraftmk4.mixin;

import net.minecraft.client.gui.screens.recipebook.RecipeBookComponent;
import net.minecraft.client.gui.screens.recipebook.RecipeBookTabButton;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

@Mixin(RecipeBookComponent.class)
public interface RecipeBookWidgetAccessor {

    @Accessor("tabButtons")
    List<RecipeBookTabButton> getTabButtons();
}
