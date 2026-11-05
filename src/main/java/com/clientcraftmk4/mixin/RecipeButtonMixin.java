package com.clientcraftmk4.mixin;

import com.clientcraftmk4.ui.ResultButtonRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.recipebook.RecipeButton;
import net.minecraft.client.gui.screens.recipebook.RecipeCollection;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.display.RecipeDisplayId;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Renders the craft-count badge and the grey/purple tints on result buttons (plan §15.1). */
@Mixin(RecipeButton.class)
public abstract class RecipeButtonMixin {

    @Shadow
    public abstract RecipeDisplayId getCurrentRecipe();

    @Shadow
    public abstract ItemStack getDisplayStack();

    @Shadow
    public abstract RecipeCollection getCollection();

    @Inject(method = "extractWidgetRenderState", at = @At("TAIL"))
    private void clientcraft$renderCraftCount(GuiGraphicsExtractor context, int mouseX, int mouseY, float deltaTicks, CallbackInfo ci) {
        // One pipeline snapshot per button per frame: previously 3 volatile reads plus
        // 3 getCurrentRecipe() evaluations; now 1 read and 1 evaluation.
        com.clientcraftmk4.pipeline.ResolveResult snapshot = ResultButtonRenderer.snapshot();
        if (!ResultButtonRenderer.isAutoCraftCollection(snapshot, getCollection())) return;

        RecipeButton self = (RecipeButton) (Object) this;
        RecipeDisplayId currentRecipe = getCurrentRecipe();
        int count = ResultButtonRenderer.getCraftCount(snapshot, currentRecipe);
        boolean container = ResultButtonRenderer.isContainerCraftable(snapshot, currentRecipe);
        int x = self.getX(), y = self.getY(), w = self.getWidth(), h = self.getHeight();

        if (container) {
            context.fill(x, y, x + w, y + h, 0x807B2FBE);
        }

        if (count > 0) {
            Font textRenderer = Minecraft.getInstance().font;
            context.itemDecorations(textRenderer, getDisplayStack(), x + 4, y + 4, String.valueOf(count));
        } else if (!container) {
            context.fill(x, y, x + w, y + h, 0x80555555);
        }
    }
}
