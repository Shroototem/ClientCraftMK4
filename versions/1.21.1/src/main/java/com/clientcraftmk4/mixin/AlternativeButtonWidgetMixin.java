package com.clientcraftmk4.mixin;

import com.clientcraftmk4.RecipeResolver;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.recipebook.RecipeAlternativesWidget;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(RecipeAlternativesWidget.class)
public class AlternativeButtonWidgetMixin {

    @Shadow
    private boolean visible;

    @Shadow
    private int buttonX;

    @Shadow
    private int buttonY;

    private static final Identifier OVERLAY_RECIPE = Identifier.of("minecraft", "recipe_book/overlay_recipe");

    @Inject(method = "setVisible", at = @At("HEAD"))
    private void clientcraft$onSetVisible(boolean visible, CallbackInfo ci) {
        if (!visible) RecipeResolver.clearActiveIngredientGrid();
    }

    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void clientcraft$renderIngredientGrid(DrawContext context, int mouseX, int mouseY, float deltaTicks, CallbackInfo ci) {
        if (!this.visible) return;

        RecipeResolver.IngredientGrid grid = RecipeResolver.getActiveIngredientGrid();
        if (grid == null) return;

        int tileSize = 24;
        int gridX = this.buttonX + 4;
        int gridY = this.buttonY + 4;
        int lineColor = 0xFF373737;

        // Match vanilla's z-translate so the overlay renders above recipe result buttons
        context.getMatrices().push();
        context.getMatrices().translate(0.0f, 0.0f, 1000.0f);

        context.drawGuiTexture(OVERLAY_RECIPE,
                this.buttonX, this.buttonY, 3 * tileSize + 8, 3 * tileSize + 8);

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
                    context.drawItem(stack, ix, iy);
                    context.drawItemInSlot(MinecraftClient.getInstance().textRenderer, stack, ix, iy);
                }
            }
        }

        context.getMatrices().pop();
        ci.cancel();
    }
}
