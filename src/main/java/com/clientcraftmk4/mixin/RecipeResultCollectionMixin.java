package com.clientcraftmk4.mixin;

import com.clientcraftmk4.RecipeResolver;
import net.minecraft.client.gui.screens.recipebook.RecipeCollection;
import net.minecraft.world.entity.player.StackedItemContents;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.Predicate;

@Mixin(RecipeCollection.class)
public class RecipeResultCollectionMixin {

    /**
     * Prevent vanilla from overwriting our craftability flags.
     * Vanilla's selectRecipes uses RecipeFinder which only checks direct ingredients,
     * not recursive sub-crafting. Skip it for our ClientCraft collections.
     */
    @Inject(method = "selectRecipes", at = @At("HEAD"), cancellable = true)
    private void clientcraft$skipPopulate(StackedItemContents stackedContents, Predicate<?> displayablePredicate, CallbackInfo ci) {
        if (RecipeResolver.isAutoCraftCollection((RecipeCollection) (Object) this)) {
            ci.cancel();
        }
    }
}
