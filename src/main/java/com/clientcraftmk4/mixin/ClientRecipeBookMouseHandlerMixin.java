package com.clientcraftmk4.mixin;

import com.clientcraftmk4.RecipeBookPageCycleAccessor;
import com.clientcraftmk4.RecipeResolver;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.gui.screens.recipebook.RecipeBookPage;
import net.minecraft.client.gui.screens.recipebook.RecipeCollection;
import net.minecraft.util.context.ContextMap;
import net.minecraft.world.item.crafting.display.RecipeDisplayEntry;
import net.minecraft.world.item.crafting.display.SlotDisplayContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MouseHandler.class)
public class ClientRecipeBookMouseHandlerMixin {

    @Shadow private double xpos;
    @Shadow private double ypos;

    @Inject(method = "onScroll", at = @At("HEAD"), cancellable = true)
    private void clientcraft$onScroll(long window, double xoffset, double yoffset, CallbackInfo ci) {
        RecipeBookPage page = RecipeResolver.getActiveRecipeBookPage();
        if (page == null) return;

        RecipeBookPageAccessor pageAccessor = (RecipeBookPageAccessor) page;

        // Priority 1: overlay variant cycling
        if (pageAccessor.getOverlay() != null && pageAccessor.getOverlay().isVisible()
                && RecipeResolver.getActiveVariantCount() > 1) {
            int delta = yoffset > 0 ? -1 : 1;
            RecipeDisplayEntry next = RecipeResolver.cycleActiveVariant(delta);
            if (next != null) {
                RecipeCollection ingredientCollection = RecipeResolver.buildIngredientCollection(next);
                if (ingredientCollection != null) {
                    ContextMap context = SlotDisplayContext.fromLevel(Minecraft.getInstance().level);
                    pageAccessor.getOverlay().init(
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
            return;
        }

        // Block page cycling when overlay is open (even with only 1 variant)
        if (pageAccessor.getOverlay() != null && pageAccessor.getOverlay().isVisible()) return;

        // Priority 2: page cycling — only when hovering over the recipe book area
        if (yoffset == 0) return;
        if (pageAccessor.getTotalPages() <= 1) return;

        com.mojang.blaze3d.platform.Window win = Minecraft.getInstance().getWindow();
        double scaledX = MouseHandler.getScaledXPos(win, xpos);
        double scaledY = MouseHandler.getScaledYPos(win, ypos);
        if (!RecipeResolver.isMouseOverRecipeBook(scaledX, scaledY)) return;

        ((RecipeBookPageCycleAccessor) page).clientcraft$cyclePage((int) -Math.signum(yoffset));
        ci.cancel();
    }
}
