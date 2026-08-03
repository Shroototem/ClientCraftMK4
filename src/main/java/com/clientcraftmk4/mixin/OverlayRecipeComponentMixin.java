package com.clientcraftmk4.mixin;

import com.clientcraftmk4.core.CraftModel;
import com.clientcraftmk4.core.RecipeDisplays;
import com.clientcraftmk4.mixin.accessor.RecipeCollectionAccessor;
import com.clientcraftmk4.pipeline.ResolvePipeline;
import com.clientcraftmk4.pipeline.ResolveResult;
import com.clientcraftmk4.ui.OverlayBuilder;
import com.clientcraftmk4.ui.VariantState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.recipebook.OverlayRecipeComponent;
import net.minecraft.client.gui.screens.recipebook.RecipeCollection;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.Identifier;
import net.minecraft.util.context.ContextMap;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.display.RecipeDisplayEntry;
import net.minecraft.world.item.crafting.display.RecipeDisplayId;
import net.minecraft.world.item.crafting.display.SlotDisplayContext;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.List;

/**
 * Thin shim over vanilla's {@code OverlayRecipeComponent}: click handling
 * (variant arrows / grid craft, plan §14.3) and grid rendering all defer to
 * {@link OverlayBuilder} / {@link VariantState}. Constants and layout math live
 * here for render; the logic lives in named classes.
 */
@Mixin(OverlayRecipeComponent.class)
public class OverlayRecipeComponentMixin {

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
    @Unique private static final int RESULT_PANEL_WIDTH = 28;
    @Unique private static final int RESULT_PANEL_GAP = 3;

    @Inject(method = "setVisible", at = @At("HEAD"))
    private void clientcraft$onSetVisible(boolean visible, CallbackInfo ci) {
        if (!visible) {
            OverlayBuilder.clearActiveGrid();
            VariantState.clear();
        }
    }

    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
    private void clientcraft$onMouseClicked(MouseButtonEvent event, boolean doubleClick, CallbackInfoReturnable<Boolean> cir) {
        if (!this.isVisible) return;
        OverlayBuilder.IngredientGrid grid = OverlayBuilder.getActiveGrid();
        if (grid == null) return;

        int mouseX = (int) event.x();
        int mouseY = (int) event.y();

        int gridPixelSize = 3 * TILE_SIZE;
        int overlayW = gridPixelSize + 2 * BORDER;
        int overlayH = gridPixelSize + 2 * BORDER + COUNTER_HEIGHT;
        int uiX = uiOriginX();
        int uiY = uiOriginY();

        // Only clicks inside the UI area are ours; anything outside falls
        // through to vanilla, which closes the overlay.
        if (mouseX < uiX - 5 || mouseX > uiX + overlayW + RESULT_PANEL_GAP + RESULT_PANEL_WIDTH + 5
                || mouseY < uiY - 5 || mouseY > uiY + overlayH + 5) {
            this.lastRecipeClicked = null;
            cir.setReturnValue(false);
            return;
        }

        // Right-click on an ingredient tile → navigate to that item's recipe
        if (event.button() == 1) {
            int col = (mouseX - (uiX + BORDER)) / TILE_SIZE;
            int row = (mouseY - (uiY + BORDER)) / TILE_SIZE;
            if (col >= 0 && col < 3 && row >= 0 && row < 3) {
                ItemStack stack = grid.get(row * 3 + col);
                if (!stack.isEmpty()) {
                    openIngredientRecipe(stack.getItem());
                    cir.setReturnValue(true);
                    return;
                }
            }
            cir.setReturnValue(true);
            return;
        }

        if (event.button() != 0) {
            this.lastRecipeClicked = null;
            cir.setReturnValue(true);
            return;
        }

        // Variant arrows (left/right)
        int counterY = uiY + gridPixelSize + 2 * BORDER;
        if (VariantState.getActiveVariantCount() > 1 && mouseY >= counterY && mouseY <= counterY + COUNTER_HEIGHT) {
            int leftBtn = uiX + BORDER;
            int leftBtnRight = leftBtn + ARROW_BTN_WIDTH;
            int rightBtnLeft = uiX + overlayW - BORDER - ARROW_BTN_WIDTH;
            int rightBtnRight = rightBtnLeft + ARROW_BTN_WIDTH;

            boolean handled = false;
            if (mouseX >= leftBtn && mouseX <= leftBtnRight) {
                RecipeDisplayEntry entry = VariantState.cycleActiveVariant(-1);
                if (entry != null) rebuildOverlay(entry);
                handled = true;
            } else if (mouseX >= rightBtnLeft && mouseX <= rightBtnRight) {
                RecipeDisplayEntry entry = VariantState.cycleActiveVariant(1);
                if (entry != null) rebuildOverlay(entry);
                handled = true;
            }

            if (handled) {
                this.lastRecipeClicked = null;
                cir.setReturnValue(true);
                return;
            }
        }

        // Check result slot click → craft current variant
        int panelY = uiY + (gridPixelSize + 2 * BORDER - RESULT_PANEL_WIDTH) / 2;
        int panelX = uiX + overlayW + RESULT_PANEL_GAP;
        boolean inResult = mouseX >= panelX && mouseX <= panelX + RESULT_PANEL_WIDTH
                && mouseY >= panelY && mouseY <= panelY + RESULT_PANEL_WIDTH;

        if (inResult) {
            RecipeDisplayEntry target = VariantState.getActiveVariant();
            RecipeCollection activeColl = VariantState.getActiveCollection();
            if (target != null && activeColl != null) {
                // Setting these makes vanilla's subsequent tryPlaceRecipe fire —
                // no extra AutoCrafter call here, that would double-craft (§14.4).
                this.lastRecipeClicked = target.id();
                this.collection = activeColl;
                cir.setReturnValue(true);
                return;
            }
        }

        // Click inside the UI that isn't an action: consume it so the overlay
        // stays open instead of closing.
        this.lastRecipeClicked = null;
        cir.setReturnValue(true);
    }

    @Unique
    private int uiOriginX() {
        int gridPixelSize = 3 * TILE_SIZE;
        int totalW = gridPixelSize + 2 * BORDER + RESULT_PANEL_GAP + RESULT_PANEL_WIDTH;
        return VariantState.getOverlayCenterX() - totalW / 2;
    }

    @Unique
    private int uiOriginY() {
        int gridPixelSize = 3 * TILE_SIZE;
        int totalH = gridPixelSize + 2 * BORDER + COUNTER_HEIGHT;
        return VariantState.getOverlayCenterY() - totalH / 2;
    }

    /** Rebuilds the overlay around the recipe(s) producing {@code item}. */
    @Unique
    private void openIngredientRecipe(Item item) {
        CraftModel model = CraftModel.current();
        if (model == null) return;
        List<RecipeDisplayEntry> recipes = model.recipeIndex().get(item);
        if (recipes == null || recipes.isEmpty()) return;

        ResolveResult rr = ResolvePipeline.current();
        List<RecipeDisplayEntry> variants = new ArrayList<>();
        for (RecipeDisplayEntry entry : recipes) {
            if (rr.counts().getOrDefault(entry.id(), 0) > 0 || rr.containerCraftable().contains(entry.id())) {
                variants.add(entry);
            }
        }
        if (variants.isEmpty()) variants.addAll(recipes);

        RecipeCollection coll = new RecipeCollection(variants);
        RecipeCollectionAccessor acc = (RecipeCollectionAccessor) coll;
        for (RecipeDisplayEntry entry : variants) {
            acc.displayable().add(entry.id());
            if (rr.counts().getOrDefault(entry.id(), 0) > 0 || rr.containerCraftable().contains(entry.id())) {
                acc.craftable().add(entry.id());
            }
        }

        int initialIndex = 0;
        for (int i = 0; i < variants.size(); i++) {
            if (rr.counts().getOrDefault(variants.get(i).id(), 0) > 0) {
                initialIndex = i;
                break;
            }
        }

        RecipeDisplayEntry target = variants.get(initialIndex);
        RecipeCollection ingredientCollection = OverlayBuilder.buildIngredientCollection(target);
        if (ingredientCollection == null) return;

        VariantState.setActiveVariants(variants, initialIndex, coll);
        ContextMap context = SlotDisplayContext.fromLevel(Minecraft.getInstance().level);
        ((OverlayRecipeComponent) (Object) this).init(ingredientCollection, context, false,
                VariantState.getOverlayButtonX(),
                VariantState.getOverlayButtonY(),
                VariantState.getOverlayCenterX(),
                VariantState.getOverlayCenterY(),
                VariantState.getOverlayButtonWidth());
        this.lastRecipeClicked = null;
    }

    @Unique
    private void rebuildOverlay(RecipeDisplayEntry entry) {
        RecipeCollection ingredientCollection = OverlayBuilder.buildIngredientCollection(entry);
        if (ingredientCollection == null) return;
        ContextMap context = SlotDisplayContext.fromLevel(Minecraft.getInstance().level);
        ((OverlayRecipeComponent) (Object) this).init(ingredientCollection, context, false,
                VariantState.getOverlayButtonX(),
                VariantState.getOverlayButtonY(),
                VariantState.getOverlayCenterX(),
                VariantState.getOverlayCenterY(),
                VariantState.getOverlayButtonWidth());
    }

    @Inject(method = "extractRenderState", at = @At("HEAD"), cancellable = true)
    private void clientcraft$renderIngredientGrid(GuiGraphicsExtractor context, int mouseX, int mouseY, float deltaTicks, CallbackInfo ci) {
        if (!this.isVisible) return;

        OverlayBuilder.IngredientGrid grid = OverlayBuilder.getActiveGrid();
        if (grid == null) return;

        OverlayBuilder.refreshActiveGridCraftability();

        int gridPixelSize = 3 * TILE_SIZE;
        int overlayW = gridPixelSize + 2 * BORDER;
        int overlayH = gridPixelSize + 2 * BORDER + COUNTER_HEIGHT;
        int lineColor = 0xFF373737;
        int backdropColor = 0xBF000000;

        int uiX = uiOriginX();
        int uiY = uiOriginY();

        int panelWidth = RESULT_PANEL_WIDTH + RESULT_PANEL_GAP;
        context.fill(uiX - 5, uiY - 5,
                uiX + overlayW + panelWidth + 5, uiY + overlayH + 5, backdropColor);

        context.blitSprite(RenderPipelines.GUI_TEXTURED, OVERLAY_RECIPE,
                uiX, uiY, overlayW, gridPixelSize + 2 * BORDER);

        context.fill(uiX, uiY + gridPixelSize + 2 * BORDER,
                uiX + overlayW, uiY + overlayH, 0xFF555555);
        context.fill(uiX, uiY + gridPixelSize + 2 * BORDER,
                uiX + overlayW, uiY + gridPixelSize + 2 * BORDER + 1, lineColor);

        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 3; col++) {
                int idx = row * 3 + col;
                int tileX = uiX + BORDER + col * TILE_SIZE;
                int tileY = uiY + BORDER + row * TILE_SIZE;

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

                    if (mouseX >= tileX && mouseX < tileX + TILE_SIZE && mouseY >= tileY && mouseY < tileY + TILE_SIZE) {
                        Minecraft mc = Minecraft.getInstance();
                        context.setComponentTooltipForNextFrame(
                                mc.font,
                                Screen.getTooltipFromItem(mc, stack),
                                mouseX, mouseY,
                                stack.get(DataComponents.TOOLTIP_STYLE));
                    }
                }
            }
        }

        int count = VariantState.getActiveVariantCount();

        // Result panel: the current variant's output item + count, to the right
        // of the ingredient grid, so recipes with different output amounts are
        // distinguishable while cycling variants.
        RecipeDisplayEntry active = VariantState.getActiveVariant();
        if (active != null) {
            CraftModel model = CraftModel.current();
            if (model != null) {
                ItemStack result = RecipeDisplays.resolveResult(active.display(), model.tagIndex());
                if (!result.isEmpty()) {
                    int panelY = uiY + (gridPixelSize + 2 * BORDER - RESULT_PANEL_WIDTH) / 2;
                    int panelX = uiX + overlayW + RESULT_PANEL_GAP;
                    context.blitSprite(RenderPipelines.GUI_TEXTURED, OVERLAY_RECIPE,
                            panelX, panelY, RESULT_PANEL_WIDTH, RESULT_PANEL_WIDTH);
                    context.fill(panelX + BORDER, panelY + BORDER,
                            panelX + RESULT_PANEL_WIDTH - BORDER, panelY + RESULT_PANEL_WIDTH - BORDER, 0xFF8B8B8B);

                    int iconX = panelX + (RESULT_PANEL_WIDTH - 16) / 2;
                    int iconY = panelY + (RESULT_PANEL_WIDTH - 16) / 2;
                    context.item(result, iconX, iconY);
                    context.itemDecorations(Minecraft.getInstance().font, result, iconX, iconY);

                    if (mouseX >= panelX && mouseX < panelX + RESULT_PANEL_WIDTH
                            && mouseY >= panelY && mouseY < panelY + RESULT_PANEL_WIDTH) {
                        Minecraft mc = Minecraft.getInstance();
                        context.setComponentTooltipForNextFrame(
                                mc.font,
                                Screen.getTooltipFromItem(mc, result),
                                mouseX, mouseY,
                                result.get(DataComponents.TOOLTIP_STYLE));
                    }
                }
            }
        }

        if (count > 1) {
            Font font = Minecraft.getInstance().font;
            int index = VariantState.getActiveVariantIndex();
            String text = (index + 1) + "/" + count;
            int textW = font.width(text);
            int textX = uiX + (overlayW - textW) / 2;
            int counterY = uiY + gridPixelSize + 2 * BORDER;
            int textY = counterY + (COUNTER_HEIGHT - font.lineHeight) / 2 + 1;

            int btnTop = counterY + 1;
            int btnBot = counterY + COUNTER_HEIGHT;
            int leftBtnRight = uiX + BORDER + ARROW_BTN_WIDTH;
            int rightBtnLeft = uiX + overlayW - BORDER - ARROW_BTN_WIDTH;

            context.fill(uiX + BORDER, btnTop, leftBtnRight, btnBot, ARROW_BTN_COLOR);
            context.fill(rightBtnLeft, btnTop, rightBtnLeft + ARROW_BTN_WIDTH, btnBot, ARROW_BTN_COLOR);

            context.text(font, text, textX, textY, 0xFFFFFFFF);

            int leftArrowX = uiX + BORDER + (ARROW_BTN_WIDTH - font.width("<")) / 2;
            int rightArrowX = rightBtnLeft + (ARROW_BTN_WIDTH - font.width(">")) / 2;
            context.text(font, "<", leftArrowX, textY, 0xFFFFFFFF);
            context.text(font, ">", rightArrowX, textY, 0xFFFFFFFF);
        }

        ci.cancel();
    }
}
