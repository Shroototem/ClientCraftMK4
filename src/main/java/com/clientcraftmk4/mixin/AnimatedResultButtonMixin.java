package com.clientcraftmk4.mixin;

import com.clientcraftmk4.RecipeResolver;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.recipebook.AnimatedResultButton;
import net.minecraft.client.gui.screen.recipebook.RecipeResultCollection;
import net.minecraft.item.ItemStack;
import net.minecraft.recipe.NetworkRecipeId;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(AnimatedResultButton.class)
public abstract class AnimatedResultButtonMixin {

    @Shadow
    public abstract NetworkRecipeId getCurrentId();

    @Shadow
    public abstract ItemStack getDisplayStack();

    @Shadow
    public abstract RecipeResultCollection getResultCollection();

    @Inject(method = "renderWidget", at = @At("TAIL"))
    private void clientcraft$renderCraftCount(DrawContext context, int mouseX, int mouseY, float deltaTicks, CallbackInfo ci) {
        if (!RecipeResolver.isAutoCraftCollection(getResultCollection())) return;

        int count = RecipeResolver.getCraftCount(getCurrentId());
        if (count <= 0) return;

        AnimatedResultButton self = (AnimatedResultButton) (Object) this;
        TextRenderer textRenderer = MinecraftClient.getInstance().textRenderer;
        context.drawStackOverlay(textRenderer, getDisplayStack(), self.getX() + 4, self.getY() + 4, String.valueOf(count));
    }
}
