package com.clientcraftmk4.mixin;

import com.clientcraftmk4.RecipeResolver;
import net.minecraft.client.gui.screen.recipebook.RecipeResultCollection;
import net.minecraft.recipe.RecipeFinder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.Predicate;

@Mixin(RecipeResultCollection.class)
public class RecipeResultCollectionMixin {

    /**
     * Prevent vanilla from overwriting our craftability flags.
     * Vanilla's populateRecipes uses RecipeFinder which only checks direct ingredients,
     * not recursive sub-crafting. Skip it for our ClientCraft collections.
     */
    @Inject(method = "populateRecipes", at = @At("HEAD"), cancellable = true)
    private void clientcraft$skipPopulate(RecipeFinder finder, Predicate<?> displayablePredicate, CallbackInfo ci) {
        if (RecipeResolver.isAutoCraftCollection((RecipeResultCollection) (Object) this)) {
            ci.cancel();
        }
    }
}
