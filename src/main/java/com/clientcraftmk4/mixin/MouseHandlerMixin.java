package com.clientcraftmk4.mixin;

import com.clientcraftmk4.mixin.accessor.RecipeBookPageAccessor;
import com.clientcraftmk4.ui.ScrollController;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.gui.screens.recipebook.RecipeBookPage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Scroll wheel → variant cycle / page cycle (plan §13.7, §5.4 A8-A10). */
@Mixin(MouseHandler.class)
public class MouseHandlerMixin {

    @Shadow private double xpos;
    @Shadow private double ypos;

    @Inject(method = "onScroll", at = @At("HEAD"), cancellable = true)
    private void clientcraft$onScroll(long window, double xoffset, double yoffset, CallbackInfo ci) {
        RecipeBookPage page = ScrollController.activePage();
        if (page == null) return;

        RecipeBookPageAccessor acc = (RecipeBookPageAccessor) page;
        com.mojang.blaze3d.platform.Window win = Minecraft.getInstance().getWindow();
        double scaledX = MouseHandler.getScaledXPos(win, xpos);
        double scaledY = MouseHandler.getScaledYPos(win, ypos);

        if (ScrollController.handleScroll(page, acc.getTotalPages(), scaledX, scaledY, yoffset)) {
            ci.cancel();
        }
    }
}
