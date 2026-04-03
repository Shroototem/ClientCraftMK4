package com.clientcraftmk4.mixin;

import com.clientcraftmk4.RecipeResolver;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.gui.screens.recipebook.RecipeButton;
import net.minecraft.client.gui.screens.recipebook.OverlayRecipeComponent;
import net.minecraft.client.gui.screens.recipebook.RecipeBookPage;
import net.minecraft.client.gui.screens.recipebook.RecipeCollection;
import net.minecraft.world.item.crafting.display.RecipeDisplayEntry;
import net.minecraft.world.item.crafting.display.SlotDisplayContext;
import net.minecraft.util.context.ContextMap;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

@Mixin(RecipeBookPage.class)
public class RecipeBookResultsMixin {

    @Shadow
    @Final
    private List<RecipeButton> buttons;

    @Shadow
    @Final
    private OverlayRecipeComponent overlay;

    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
    private void clientcraft$onRightClick(MouseButtonEvent event, int left, int top, int width, int height, boolean bl, CallbackInfoReturnable<Boolean> cir) {
        if (event.button() != 1) return; // Only right-click

        // If alternatives widget is already open, let vanilla handle it
        if (overlay.isVisible()) return;

        // Find which button the mouse is over
        for (RecipeButton button : buttons) {
            if (!button.visible) continue;
            if (!button.isMouseOver(event.x(), event.y())) continue;

            RecipeCollection collection = button.getCollection();
            if (!RecipeResolver.isAutoCraftCollection(collection)) return;

            // Prefer a craftable recipe variant, fall back to the displayed one
            RecipeDisplayEntry best = null;
            for (RecipeDisplayEntry entry : collection.getRecipes()) {
                if (collection.isCraftable(entry.id()) || RecipeResolver.isContainerCraftable(entry.id())) {
                    best = entry;
                    break;
                }
                if (best == null && entry.id().equals(button.getCurrentRecipe())) best = entry;
            }
            if (best != null) {
                RecipeCollection ingredientCollection = RecipeResolver.buildIngredientCollection(best);
                if (ingredientCollection == null) return;

                ContextMap context = SlotDisplayContext.fromLevel(
                        Minecraft.getInstance().level);

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

                cir.setReturnValue(true);
                return;
            }
        }
    }
}
