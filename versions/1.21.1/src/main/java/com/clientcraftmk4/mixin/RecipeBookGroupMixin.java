package com.clientcraftmk4.mixin;

import com.clientcraftmk4.ClientCraftTab;
import net.minecraft.client.recipebook.RecipeBookGroup;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

@Mixin(RecipeBookGroup.class)
public class RecipeBookGroupMixin {

    private static final List<ItemStack> CRAFTING_TABLE_ICON = List.of(Items.CRAFTING_TABLE.getDefaultStack());

    @Inject(method = "getIcons", at = @At("HEAD"), cancellable = true)
    private void clientcraft$overrideIcon(CallbackInfoReturnable<List<ItemStack>> cir) {
        if ((Object) this == ClientCraftTab.INSTANCE) {
            cir.setReturnValue(CRAFTING_TABLE_ICON);
        }
    }
}
