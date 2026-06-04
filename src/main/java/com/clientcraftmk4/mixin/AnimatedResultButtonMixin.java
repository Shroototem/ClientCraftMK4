package com.clientcraftmk4.mixin;

import com.clientcraftmk4.RecipeResolver;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.recipebook.RecipeButton;
import net.minecraft.client.gui.screens.recipebook.RecipeCollection;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.display.RecipeDisplayId;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(RecipeButton.class)
public abstract class AnimatedResultButtonMixin {

    @Shadow
    public abstract RecipeDisplayId getCurrentRecipe();

    @Shadow
    public abstract ItemStack getDisplayStack();

    @Shadow
    public abstract RecipeCollection getCollection();

    @Inject(method = "extractWidgetRenderState", at = @At("TAIL"))
    private void clientcraft$renderCraftCount(GuiGraphicsExtractor context, int mouseX, int mouseY, float deltaTicks, CallbackInfo ci) {
        if (!RecipeResolver.isAutoCraftCollection(getCollection())) return;

        RecipeButton self = (RecipeButton) (Object) this;
        int count = RecipeResolver.getCraftCount(getCurrentRecipe());
        boolean container = RecipeResolver.isContainerCraftable(getCurrentRecipe());
        int x = self.getX(), y = self.getY(), w = self.getWidth(), h = self.getHeight();

        if (container) {
            context.fill(x, y, x + w, y + h, 0x807B2FBE);
        }

        if (count > 0) {
            Font textRenderer = Minecraft.getInstance().font;
            context.itemDecorations(textRenderer, getDisplayStack(), x + 4, y + 4, String.valueOf(count));
        } else if (!container) {
            context.fill(x, y, x + w, y + h, 0x80555555);
        }
    }
}
