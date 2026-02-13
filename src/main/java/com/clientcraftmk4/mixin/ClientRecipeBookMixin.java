package com.clientcraftmk4.mixin;

import com.clientcraftmk4.ClientCraftTab;
import com.clientcraftmk4.RecipeResolver;
import net.minecraft.client.gui.screen.recipebook.RecipeResultCollection;
import net.minecraft.client.recipebook.ClientRecipeBook;
import net.minecraft.recipe.book.RecipeBookGroup;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

@Mixin(ClientRecipeBook.class)
public class ClientRecipeBookMixin {

    @Inject(method = "getResultsForCategory", at = @At("HEAD"), cancellable = true)
    private void clientcraft$getResultsForCategory(RecipeBookGroup category, CallbackInfoReturnable<List<RecipeResultCollection>> cir) {
        if (category == ClientCraftTab.INSTANCE) {
            cir.setReturnValue(RecipeResolver.resolveForTab((ClientRecipeBook) (Object) this));
        }
    }
}
