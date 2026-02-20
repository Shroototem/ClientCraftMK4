package com.clientcraftmk4.mixin;

import com.clientcraftmk4.RecipeResolver;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.recipebook.AnimatedResultButton;
import net.minecraft.client.gui.screen.recipebook.RecipeAlternativesWidget;
import net.minecraft.client.gui.screen.recipebook.RecipeBookResults;
import net.minecraft.client.gui.screen.recipebook.RecipeResultCollection;
import net.minecraft.recipe.RecipeEntry;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

@Mixin(RecipeBookResults.class)
public class RecipeBookResultsMixin {

    @Shadow
    @Final
    private List<AnimatedResultButton> resultButtons;

    @Shadow
    @Final
    private RecipeAlternativesWidget alternatesWidget;

    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
    private void clientcraft$onRightClick(double mouseX, double mouseY, int button, int left, int top, int width, int height, CallbackInfoReturnable<Boolean> cir) {
        if (button != 1) return; // Only right-click

        // If alternatives widget is already open, let vanilla handle it
        if (alternatesWidget.isVisible()) return;

        // Find which button the mouse is over
        for (AnimatedResultButton btn : resultButtons) {
            if (!btn.visible) continue;
            if (!btn.isMouseOver(mouseX, mouseY)) continue;

            RecipeResultCollection collection = btn.getResultCollection();
            if (!RecipeResolver.isAutoCraftCollection(collection)) return;

            // Prefer a craftable recipe variant, fall back to the displayed one
            RecipeEntry<?> best = null;
            RecipeEntry<?> current = btn.currentRecipe();
            for (RecipeEntry<?> entry : collection.getAllRecipes()) {
                if (collection.isCraftable(entry) || RecipeResolver.isContainerCraftable(entry.id())) {
                    best = entry;
                    break;
                }
                if (best == null && current != null && entry.id().equals(current.id())) best = entry;
            }
            if (best != null) {
                RecipeResultCollection ingredientCollection = RecipeResolver.buildIngredientCollection(best);
                if (ingredientCollection == null) return;

                alternatesWidget.showAlternativesForResult(
                        MinecraftClient.getInstance(),
                        ingredientCollection,
                        btn.getX(),
                        btn.getY(),
                        left + width / 2,
                        top + 13 + height / 2,
                        (float) btn.getWidth()
                );

                cir.setReturnValue(true);
                return;
            }
        }
    }
}
