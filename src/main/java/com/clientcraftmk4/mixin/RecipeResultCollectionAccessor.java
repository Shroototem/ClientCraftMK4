package com.clientcraftmk4.mixin;

import net.minecraft.client.gui.screen.recipebook.RecipeResultCollection;
import net.minecraft.recipe.NetworkRecipeId;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Set;

@Mixin(RecipeResultCollection.class)
public interface RecipeResultCollectionAccessor {

    @Accessor("craftableRecipes")
    Set<NetworkRecipeId> getCraftableRecipes();

    @Accessor("displayableRecipes")
    Set<NetworkRecipeId> getDisplayableRecipes();
}
