package com.clientcraftmk4.mixin;

import com.clientcraftmk4.RecipeResolver;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.screen.recipebook.AnimatedResultButton;
import net.minecraft.client.gui.screen.recipebook.RecipeAlternativesWidget;
import net.minecraft.client.gui.screen.recipebook.RecipeBookResults;
import net.minecraft.client.gui.screen.recipebook.RecipeResultCollection;
import net.minecraft.recipe.RecipeDisplayEntry;
import net.minecraft.recipe.display.SlotDisplayContexts;
import net.minecraft.util.context.ContextParameterMap;
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
    private void clientcraft$onRightClick(Click click, int left, int top, int width, int height, boolean bl, CallbackInfoReturnable<Boolean> cir) {
        if (click.button() != 1) return; // Only right-click

        // If alternatives widget is already open, let vanilla handle it
        if (alternatesWidget.isVisible()) return;

        // Find which button the mouse is over
        for (AnimatedResultButton button : resultButtons) {
            if (!button.visible) continue;
            if (!button.isMouseOver(click.x(), click.y())) continue;

            RecipeResultCollection collection = button.getResultCollection();
            if (!RecipeResolver.isAutoCraftCollection(collection)) return;

            // Prefer a craftable recipe variant, fall back to the displayed one
            RecipeDisplayEntry best = null;
            for (RecipeDisplayEntry entry : collection.getAllRecipes()) {
                if (collection.isCraftable(entry.id()) || RecipeResolver.isContainerCraftable(entry.id())) {
                    best = entry;
                    break;
                }
                if (best == null && entry.id().equals(button.getCurrentId())) best = entry;
            }
            if (best != null) {
                RecipeResultCollection ingredientCollection = RecipeResolver.buildIngredientCollection(best);
                if (ingredientCollection == null) return;

                ContextParameterMap context = SlotDisplayContexts.createParameters(
                        MinecraftClient.getInstance().world);

                alternatesWidget.showAlternativesForResult(
                        ingredientCollection,
                        context,
                        false,
                        button.getX(),
                        button.getY(),
                        left + width / 2,
                        top + 13 + height / 2,
                        button.getWidth()
                );

                cir.setReturnValue(true);
                return;
            }
        }
    }
}
