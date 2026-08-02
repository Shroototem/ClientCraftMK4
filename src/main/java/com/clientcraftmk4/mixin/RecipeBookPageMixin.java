package com.clientcraftmk4.mixin;

import com.clientcraftmk4.ui.OverlayBuilder;
import com.clientcraftmk4.ui.RecipeBookPageCycleAccessor;
import com.clientcraftmk4.ui.ResultButtonRenderer;
import com.clientcraftmk4.ui.VariantState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.recipebook.OverlayRecipeComponent;
import net.minecraft.client.gui.screens.recipebook.RecipeBookPage;
import net.minecraft.client.gui.screens.recipebook.RecipeButton;
import net.minecraft.client.gui.screens.recipebook.RecipeCollection;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.util.context.ContextMap;
import net.minecraft.world.item.crafting.display.RecipeDisplayEntry;
import net.minecraft.world.item.crafting.display.SlotDisplayContext;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.List;

/**
 * Right-click → ingredient-grid overlay + page cycling (plan §13.6, §5.4 A4).
 * Variants = craftable ∪ container-craftable entries of the clicked collection;
 * the initial index lands on the currently-viewed recipe.
 */
@Mixin(RecipeBookPage.class)
public class RecipeBookPageMixin implements RecipeBookPageCycleAccessor {

    @Shadow
    @Final
    private List<RecipeButton> buttons;

    @Shadow
    @Final
    private OverlayRecipeComponent overlay;

    @Shadow
    private int currentPage;

    @Shadow
    private int totalPages;

    @Shadow
    private void updateButtonsForPage() {}

    @Override
    public void clientcraft$cyclePage(int delta) {
        int next = currentPage + delta;
        if (next >= 0 && next < totalPages) {
            currentPage = next;
            updateButtonsForPage();
        }
    }

    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
    private void clientcraft$onRightClick(MouseButtonEvent event, int left, int top, int width, int height, boolean bl, CallbackInfoReturnable<Boolean> cir) {
        if (event.button() != 1) return;
        if (overlay.isVisible()) return;

        for (RecipeButton button : buttons) {
            if (!button.visible) continue;
            if (!button.isMouseOver(event.x(), event.y())) continue;

            RecipeCollection collection = button.getCollection();
            if (!ResultButtonRenderer.isAutoCraftCollection(collection)) return;

            List<RecipeDisplayEntry> variants = new ArrayList<>();
            for (RecipeDisplayEntry entry : collection.getRecipes()) {
                if (collection.isCraftable(entry.id()) || ResultButtonRenderer.isContainerCraftable(entry.id())) {
                    variants.add(entry);
                }
            }
            if (variants.isEmpty()) {
                for (RecipeDisplayEntry entry : collection.getRecipes()) {
                    if (entry.id().equals(button.getCurrentRecipe())) {
                        variants.add(entry);
                        break;
                    }
                }
            }
            if (variants.isEmpty()) return;

            int initialIndex = 0;
            for (int i = 0; i < variants.size(); i++) {
                if (variants.get(i).id().equals(button.getCurrentRecipe())) {
                    initialIndex = i;
                    break;
                }
            }

            RecipeDisplayEntry target = variants.get(initialIndex);
            RecipeCollection ingredientCollection = OverlayBuilder.buildIngredientCollection(target);
            if (ingredientCollection == null) return;

            VariantState.setActiveVariants(variants, initialIndex, collection);
            VariantState.setOverlayPosition(
                    button.getX(), button.getY(),
                    left + width / 2, top + 13 + height / 2,
                    button.getWidth());

            ContextMap context = SlotDisplayContext.fromLevel(Minecraft.getInstance().level);
            overlay.init(
                    ingredientCollection,
                    context,
                    false,
                    button.getX(),
                    button.getY(),
                    left + width / 2,
                    top + 13 + height / 2,
                    button.getWidth()
            );
            VariantState.setActiveOverlay(overlay);

            cir.setReturnValue(true);
            return;
        }
    }
}
