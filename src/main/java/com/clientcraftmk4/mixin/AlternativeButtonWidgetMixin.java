package com.clientcraftmk4.mixin;

import com.clientcraftmk4.RecipeResolver;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.recipebook.OverlayRecipeComponent;
import net.minecraft.client.gui.screens.recipebook.RecipeCollection;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.util.context.ContextMap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.display.RecipeDisplayEntry;
import net.minecraft.world.item.crafting.display.RecipeDisplayId;
import net.minecraft.world.item.crafting.display.SlotDisplayContext;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(OverlayRecipeComponent.class)
public class AlternativeButtonWidgetMixin {

    @Shadow
    private boolean isVisible;

    @Shadow
    private int x;

    @Shadow
    private int y;

    @Shadow
    private @Nullable RecipeDisplayId lastRecipeClicked;

    @Shadow
    private RecipeCollection collection;

    @Unique
    private static final Identifier OVERLAY_RECIPE = Identifier.withDefaultNamespace("recipe_book/overlay_recipe");

    @Unique private static final int TILE_SIZE = 24;
    @Unique private static final int BORDER = 4;
    @Unique private static final int COUNTER_HEIGHT = 14;
    @Unique private static final int ARROW_BTN_WIDTH = 14;
    @Unique private static final int ARROW_BTN_COLOR = 0xFF6A6A6A;

    @Inject(method = "setVisible", at = @At("HEAD"))
    private void clientcraft$onSetVisible(boolean visible, CallbackInfo ci) {
        if (!visible) RecipeResolver.clearActiveIngredientGrid();
    }

    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
    private void clientcraft$onMouseClicked(MouseButtonEvent event, boolean doubleClick, CallbackInfoReturnable<Boolean> cir) {
        if (!this.isVisible) return;
        RecipeResolver.IngredientGrid grid = RecipeResolver.getActiveIngredientGrid();
        if (grid == null) return;
        if (event.button() != 0) return;

        int mouseX = (int) event.x();
        int mouseY = (int) event.y();
        int gridPixelSize = 3 * TILE_SIZE;
        int overlayW = gridPixelSize + 2 * BORDER;
        int counterY = this.y + gridPixelSize + 2 * BORDER;

        if (RecipeResolver.getActiveVariantCount() > 1 && mouseY >= counterY && mouseY <= counterY + COUNTER_HEIGHT) {
            int leftBtn = this.x + BORDER;
            int leftBtnRight = leftBtn + ARROW_BTN_WIDTH;
            int rightBtnLeft = this.x + overlayW - BORDER - ARROW_BTN_WIDTH;
            int rightBtnRight = rightBtnLeft + ARROW_BTN_WIDTH;

            boolean handled = false;
            if (mouseX >= leftBtn && mouseX <= leftBtnRight) {
                RecipeDisplayEntry entry = RecipeResolver.cycleActiveVariant(-1);
                if (entry != null) rebuildOverlay(entry);
                handled = true;
            } else if (mouseX >= rightBtnLeft && mouseX <= rightBtnRight) {
                RecipeDisplayEntry entry = RecipeResolver.cycleActiveVariant(1);
                if (entry != null) rebuildOverlay(entry);
                handled = true;
            }

            if (handled) {
                cir.setReturnValue(true);
                return;
            }
        }

        // Check grid area click → craft current variant
        int gridX = this.x + BORDER;
        int gridY = this.y + BORDER;
        int gridRight = gridX + gridPixelSize;
        int gridBottom = gridY + gridPixelSize;

        if (mouseX >= gridX && mouseX <= gridRight && mouseY >= gridY && mouseY <= gridBottom) {
            RecipeDisplayEntry target = RecipeResolver.getActiveVariant();
            RecipeCollection activeColl = RecipeResolver.getActiveCollection();
            if (target != null && activeColl != null) {
                this.lastRecipeClicked = target.id();
                this.collection = activeColl;
                cir.setReturnValue(true);
                return;
            }
        }
    }

    @Unique
    private void rebuildOverlay(RecipeDisplayEntry entry) {
        RecipeCollection ingredientCollection = RecipeResolver.buildIngredientCollection(entry);
        if (ingredientCollection == null) return;
        ContextMap context = SlotDisplayContext.fromLevel(Minecraft.getInstance().level);
        ((OverlayRecipeComponent) (Object) this).init(ingredientCollection, context, false,
                RecipeResolver.getOverlayButtonX(),
                RecipeResolver.getOverlayButtonY(),
                RecipeResolver.getOverlayCenterX(),
                RecipeResolver.getOverlayCenterY(),
                RecipeResolver.getOverlayButtonWidth());
    }

    @Inject(method = "extractRenderState", at = @At("HEAD"), cancellable = true)
    private void clientcraft$renderIngredientGrid(GuiGraphicsExtractor context, int mouseX, int mouseY, float deltaTicks, CallbackInfo ci) {
        if (!this.isVisible) return;

        RecipeResolver.IngredientGrid grid = RecipeResolver.getActiveIngredientGrid();
        if (grid == null) return;

        RecipeResolver.refreshActiveGridCraftability();

        int gridPixelSize = 3 * TILE_SIZE;
        int overlayW = gridPixelSize + 2 * BORDER;
        int overlayH = gridPixelSize + 2 * BORDER + COUNTER_HEIGHT;
        int lineColor = 0xFF373737;

        context.blitSprite(RenderPipelines.GUI_TEXTURED, OVERLAY_RECIPE,
                this.x, this.y, overlayW, gridPixelSize + 2 * BORDER);

        context.fill(this.x, this.y + gridPixelSize + 2 * BORDER,
                this.x + overlayW, this.y + overlayH, 0xFF555555);
        context.fill(this.x, this.y + gridPixelSize + 2 * BORDER,
                this.x + overlayW, this.y + gridPixelSize + 2 * BORDER + 1, lineColor);

        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 3; col++) {
                int idx = row * 3 + col;
                int tileX = this.x + BORDER + col * TILE_SIZE;
                int tileY = this.y + BORDER + row * TILE_SIZE;

                int color = grid.hasCraftable(idx) ? 0xFF8B8B8B
                          : grid.isInContainer(idx) ? 0xFF7B2FBE : 0xFF555555;
                context.fill(tileX, tileY, tileX + TILE_SIZE, tileY + TILE_SIZE, color);

                if (col > 0) context.fill(tileX, tileY, tileX + 1, tileY + TILE_SIZE, lineColor);
                if (row > 0) context.fill(tileX, tileY, tileX + TILE_SIZE, tileY + 1, lineColor);

                ItemStack stack = grid.get(idx);
                if (!stack.isEmpty()) {
                    int ix = tileX + 4, iy = tileY + 4;
                    context.item(stack, ix, iy);
                    context.itemDecorations(Minecraft.getInstance().font, stack, ix, iy);
                }
            }
        }

        int count = RecipeResolver.getActiveVariantCount();
        if (count > 1) {
            Font font = Minecraft.getInstance().font;
            int index = RecipeResolver.getActiveVariantIndex();
            String text = (index + 1) + "/" + count;
            int textW = font.width(text);
            int textX = this.x + (overlayW - textW) / 2;
            int counterY = this.y + gridPixelSize + 2 * BORDER;
            int textY = counterY + (COUNTER_HEIGHT - font.lineHeight) / 2 + 1;

            // Arrow button backgrounds
            int btnTop = counterY + 1;
            int btnBot = counterY + COUNTER_HEIGHT;
            int leftBtnRight = this.x + BORDER + ARROW_BTN_WIDTH;
            int rightBtnLeft = this.x + overlayW - BORDER - ARROW_BTN_WIDTH;

            context.fill(this.x + BORDER, btnTop, leftBtnRight, btnBot, ARROW_BTN_COLOR);
            context.fill(rightBtnLeft, btnTop, rightBtnLeft + ARROW_BTN_WIDTH, btnBot, ARROW_BTN_COLOR);

            // Center counter text
            context.text(font, text, textX, textY, 0xFFFFFFFF);

            // Arrows centered in their buttons
            int leftArrowX = this.x + BORDER + (ARROW_BTN_WIDTH - font.width("<")) / 2;
            int rightArrowX = rightBtnLeft + (ARROW_BTN_WIDTH - font.width(">")) / 2;
            context.text(font, "<", leftArrowX, textY, 0xFFFFFFFF);
            context.text(font, ">", rightArrowX, textY, 0xFFFFFFFF);
        }

        ci.cancel();
    }
}
