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

        if (RecipeResolver.isContainerCraftable(getCurrentRecipe())) {
            int x = self.getX();
            int y = self.getY();
            context.fill(x, y, x + self.getWidth(), y + self.getHeight(), 0x807B2FBE);
        }

        int count = RecipeResolver.getCraftCount(getCurrentRecipe());
        if (count <= 0) return;

        Font textRenderer = Minecraft.getInstance().font;
        context.itemDecorations(textRenderer, getDisplayStack(), self.getX() + 4, self.getY() + 4, String.valueOf(count));
    }
}
