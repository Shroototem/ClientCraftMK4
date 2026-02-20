package com.clientcraftmk4.mixin;

import com.clientcraftmk4.RecipeResolver;
import net.minecraft.client.gui.screen.recipebook.RecipeResultCollection;
import net.minecraft.recipe.RecipeMatcher;
import net.minecraft.recipe.book.RecipeBook;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(RecipeResultCollection.class)
public class RecipeResultCollectionMixin {

    /**
     * Prevent vanilla from overwriting our craftability flags.
     * Vanilla's computeCraftables uses RecipeMatcher which only checks direct ingredients,
     * not recursive sub-crafting. Skip it for our ClientCraft collections.
     */
    @Inject(method = "computeCraftables", at = @At("HEAD"), cancellable = true)
    private void clientcraft$skipComputeCraftables(RecipeMatcher recipeFinder, int gridWidth, int gridHeight, RecipeBook recipeBook, CallbackInfo ci) {
        if (RecipeResolver.isAutoCraftCollection((RecipeResultCollection) (Object) this)) {
            ci.cancel();
        }
    }
}
