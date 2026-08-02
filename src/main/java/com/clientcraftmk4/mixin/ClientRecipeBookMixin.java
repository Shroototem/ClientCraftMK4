package com.clientcraftmk4.mixin;

import com.clientcraftmk4.core.CraftModel;
import com.clientcraftmk4.pipeline.ResolvePipeline;
import com.clientcraftmk4.ui.ClientCraftTab;
import net.minecraft.client.ClientRecipeBook;
import net.minecraft.client.gui.screens.recipebook.RecipeCollection;
import net.minecraft.world.item.crafting.ExtendedRecipeBookCategory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/** Routes the ClientCraft category to the pipeline and invalidates on recipe reload. */
@Mixin(ClientRecipeBook.class)
public class ClientRecipeBookMixin {

    @Inject(method = "getCollection", at = @At("HEAD"), cancellable = true)
    private void clientcraft$getCollection(ExtendedRecipeBookCategory category, CallbackInfoReturnable<List<RecipeCollection>> cir) {
        if (category == ClientCraftTab.INSTANCE) {
            cir.setReturnValue(ResolvePipeline.collectionsForTab((ClientRecipeBook) (Object) this));
        }
    }

    @Inject(method = "rebuildCollections", at = @At("TAIL"))
    private void clientcraft$onRecipesRefreshed(CallbackInfo ci) {
        CraftModel.markDirty();
        ResolvePipeline.resetLatest();
    }
}
