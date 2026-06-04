package com.clientcraftmk4.mixin;

import com.clientcraftmk4.RecipeResolver;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.recipebook.OverlayRecipeComponent;
import net.minecraft.client.gui.screens.recipebook.RecipeCollection;
import net.minecraft.util.context.ContextMap;
import net.minecraft.world.item.crafting.display.RecipeDisplayEntry;
import net.minecraft.world.item.crafting.display.SlotDisplayContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(net.minecraft.client.MouseHandler.class)
public class ClientRecipeBookMouseHandlerMixin {

    @Inject(method = "onScroll", at = @At("HEAD"), cancellable = true)
    private void clientcraft$onScroll(long window, double xoffset, double yoffset, CallbackInfo ci) {
        OverlayRecipeComponent overlay = RecipeResolver.getActiveOverlay();
        if (overlay == null || !overlay.isVisible()) return;
        if (RecipeResolver.getActiveVariantCount() <= 1) return;

        int delta = yoffset > 0 ? -1 : 1;
        RecipeDisplayEntry next = RecipeResolver.cycleActiveVariant(delta);
        if (next != null) {
            RecipeCollection ingredientCollection = RecipeResolver.buildIngredientCollection(next);
            if (ingredientCollection != null) {
                ContextMap context = SlotDisplayContext.fromLevel(Minecraft.getInstance().level);
                overlay.init(
                        ingredientCollection,
                        context,
                        false,
                        RecipeResolver.getOverlayButtonX(),
                        RecipeResolver.getOverlayButtonY(),
                        RecipeResolver.getOverlayCenterX(),
                        RecipeResolver.getOverlayCenterY(),
                        RecipeResolver.getOverlayButtonWidth()
                );
            }
        }
        ci.cancel();
    }
}
