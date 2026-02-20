package com.clientcraftmk4.mixin;

import com.clientcraftmk4.RecipeResolver;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.recipebook.AnimatedResultButton;
import net.minecraft.client.gui.screen.recipebook.RecipeResultCollection;
import net.minecraft.item.ItemStack;
import net.minecraft.recipe.RecipeEntry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(AnimatedResultButton.class)
public abstract class AnimatedResultButtonMixin {

    @Shadow
    public abstract RecipeEntry<?> currentRecipe();

    @Shadow
    public abstract RecipeResultCollection getResultCollection();

    @Inject(method = "renderWidget", at = @At("TAIL"))
    private void clientcraft$renderCraftCount(DrawContext context, int mouseX, int mouseY, float deltaTicks, CallbackInfo ci) {
        if (!RecipeResolver.isAutoCraftCollection(getResultCollection())) return;

        AnimatedResultButton self = (AnimatedResultButton) (Object) this;
        RecipeEntry<?> current = currentRecipe();
        if (current == null) return;

        if (RecipeResolver.isContainerCraftable(current.id())) {
            int x = self.getX();
            int y = self.getY();
            context.fill(x, y, x + self.getWidth(), y + self.getHeight(), 0x807B2FBE);
        }

        int count = RecipeResolver.getCraftCount(current.id());
        if (count <= 0) return;

        TextRenderer textRenderer = MinecraftClient.getInstance().textRenderer;
        ItemStack displayStack = RecipeResolver.resolveResult(current);
        context.drawItemInSlot(textRenderer, displayStack, self.getX() + 4, self.getY() + 4, String.valueOf(count));
    }
}
