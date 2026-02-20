package com.clientcraftmk4.mixin;

import net.minecraft.client.gui.screen.recipebook.RecipeResultCollection;
import net.minecraft.recipe.RecipeEntry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Set;

@Mixin(RecipeResultCollection.class)
public interface RecipeResultCollectionAccessor {

    @Accessor("craftableRecipes")
    Set<RecipeEntry<?>> getCraftableRecipes();

    @Accessor("fittingRecipes")
    Set<RecipeEntry<?>> getFittingRecipes();
}
