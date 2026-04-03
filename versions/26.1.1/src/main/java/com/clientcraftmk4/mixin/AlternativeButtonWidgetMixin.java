package com.clientcraftmk4.mixin;

import com.clientcraftmk4.RecipeResolver;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.recipebook.OverlayRecipeComponent;
import net.minecraft.world.item.ItemStack;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(OverlayRecipeComponent.class)
public class AlternativeButtonWidgetMixin {

    @Shadow
    private boolean isVisible;

    @Shadow
    private int x;

    @Shadow
    private int y;

    private static final Identifier OVERLAY_RECIPE = Identifier.withDefaultNamespace("recipe_book/overlay_recipe");

    @Inject(method = "setVisible", at = @At("HEAD"))
    private void clientcraft$onSetVisible(boolean visible, CallbackInfo ci) {
        if (!visible) RecipeResolver.clearActiveIngredientGrid();
    }

    @Inject(method = "extractRenderState", at = @At("HEAD"), cancellable = true)
    private void clientcraft$renderIngredientGrid(GuiGraphicsExtractor context, int mouseX, int mouseY, float deltaTicks, CallbackInfo ci) {
        if (!this.isVisible) return;

        RecipeResolver.IngredientGrid grid = RecipeResolver.getActiveIngredientGrid();
        if (grid == null) return;

        int tileSize = 24;
        int gridX = this.x + 4;
        int gridY = this.y + 4;
        int lineColor = 0xFF373737;

        context.blitSprite(RenderPipelines.GUI_TEXTURED, OVERLAY_RECIPE,
                this.x, this.y, 3 * tileSize + 8, 3 * tileSize + 8);

        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 3; col++) {
                int idx = row * 3 + col;
                int tileX = gridX + col * tileSize;
                int tileY = gridY + row * tileSize;

                int color = grid.hasCraftable(idx) ? 0xFF8B8B8B
                          : grid.isInContainer(idx) ? 0xFF7B2FBE : 0xFF555555;
                context.fill(tileX, tileY, tileX + tileSize, tileY + tileSize, color);

                if (col > 0) context.fill(tileX, tileY, tileX + 1, tileY + tileSize, lineColor);
                if (row > 0) context.fill(tileX, tileY, tileX + tileSize, tileY + 1, lineColor);

                ItemStack stack = grid.get(idx);
                if (!stack.isEmpty()) {
                    int ix = tileX + 4, iy = tileY + 4;
                    context.item(stack, ix, iy);
                    context.itemDecorations(Minecraft.getInstance().font, stack, ix, iy);
                }
            }
        }

        ci.cancel();
    }
}
