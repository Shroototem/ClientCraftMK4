package com.clientcraftmk4.mixin;

import com.clientcraftmk4.ui.ResultButtonRenderer;
import net.minecraft.client.gui.screens.recipebook.RecipeCollection;
import net.minecraft.world.entity.player.StackedItemContents;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.Predicate;

/**
 * Prevents vanilla from overwriting our craftability flags: vanilla's
 * {@code selectRecipes} uses RecipeFinder, which only checks direct ingredients,
 * not recursive sub-crafting. Skipped for ClientCraft collections (plan §13.5).
 */
@Mixin(RecipeCollection.class)
public class RecipeCollectionMixin {

    @Inject(method = "selectRecipes", at = @At("HEAD"), cancellable = true)
    private void clientcraft$skipPopulate(StackedItemContents stackedContents, Predicate<?> displayablePredicate, CallbackInfo ci) {
        if (ResultButtonRenderer.isAutoCraftCollection((RecipeCollection) (Object) this)) {
            ci.cancel();
        }
    }
}
