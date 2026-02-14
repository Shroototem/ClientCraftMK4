package com.clientcraftmk4.mixin;

import com.clientcraftmk4.RecipeResolver;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.RenderPipelines;
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

    private static final Identifier OVERLAY_RECIPE = Identifier.ofVanilla("recipe_book/overlay_recipe");

    /**
     * Clear the ingredient grid when the widget is hidden.
     */
    @Inject(method = "setVisible", at = @At("HEAD"))
    private void clientcraft$onSetVisible(boolean visible, CallbackInfo ci) {
        if (!visible) {
            RecipeResolver.clearActiveIngredientGrid();
        }
    }

    /**
     * When our ingredient grid is active, completely override rendering
     * to draw a 3x3 grid with full-size items in each tile.
     */
    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void clientcraft$renderIngredientGrid(DrawContext context, int mouseX, int mouseY, float deltaTicks, CallbackInfo ci) {
        if (!this.visible) return;

        RecipeResolver.IngredientGrid grid = RecipeResolver.getActiveIngredientGrid();
        if (grid == null) return;

        int cols = 3;
        int rows = 3;
        int tileSize = 24;
        int bgW = cols * tileSize + 8;
        int bgH = rows * tileSize + 8;

        // Draw the popup background
        context.drawGuiTexture(RenderPipelines.GUI_TEXTURED, OVERLAY_RECIPE,
                this.buttonX, this.buttonY, bgW, bgH);

        int gridX = this.buttonX + 4;
        int gridY = this.buttonY + 4;
        int gridW = cols * tileSize;
        int gridH = rows * tileSize;

        int haveColor = 0xFF8B8B8B;       // Light grey — player has this item
        int containerColor = 0xFF7B2FBE;  // Purple — item is in a carried container
        int missingColor = 0xFF555555;     // Dark grey — player doesn't have it

        // Fill each tile with the appropriate shade
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 3; col++) {
                int idx = row * 3 + col;
                int tileX = gridX + col * tileSize;
                int tileY = gridY + row * tileSize;
                int color;
                if (grid.hasCraftable(idx)) color = haveColor;
                else if (grid.isInContainer(idx)) color = containerColor;
                else color = missingColor;
                context.fill(tileX, tileY, tileX + tileSize, tileY + tileSize, color);
            }
        }

        // Draw separator lines between tiles
        int lineColor = 0xFF373737;
        for (int i = 1; i < cols; i++) {
            int lx = gridX + i * tileSize;
            context.fill(lx, gridY, lx + 1, gridY + gridH, lineColor);
        }
        for (int i = 1; i < rows; i++) {
            int ly = gridY + i * tileSize;
            context.fill(gridX, ly, gridX + gridW, ly + 1, lineColor);
        }

        // Draw items
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 3; col++) {
                int idx = row * 3 + col;
                ItemStack stack = grid.get(idx);
                if (!stack.isEmpty()) {
                    int ix = gridX + col * tileSize + 4;
                    int iy = gridY + row * tileSize + 4;
                    context.drawItem(stack, ix, iy);
                    context.drawStackOverlay(MinecraftClient.getInstance().textRenderer, stack, ix, iy);
                }
            }
        }

        ci.cancel();
    }
}
