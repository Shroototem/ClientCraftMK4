package com.clientcraftmk4.mixin;

import com.clientcraftmk4.RecipeBookPageCycleAccessor;
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
import java.util.ArrayList;

@Mixin(RecipeBookPage.class)
public class RecipeBookResultsMixin implements RecipeBookPageCycleAccessor {

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
            if (!RecipeResolver.isAutoCraftCollection(collection)) return;

            List<RecipeDisplayEntry> variants = new ArrayList<>();
            for (RecipeDisplayEntry entry : collection.getRecipes()) {
                if (collection.isCraftable(entry.id()) || RecipeResolver.isContainerCraftable(entry.id())) {
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
            RecipeCollection ingredientCollection = RecipeResolver.buildIngredientCollection(target);
            if (ingredientCollection == null) return;

            RecipeResolver.setActiveVariants(variants, initialIndex, collection);
            RecipeResolver.setOverlayPosition(
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
            RecipeResolver.setActiveOverlay(overlay);

            cir.setReturnValue(true);
            return;
        }
    }
}
