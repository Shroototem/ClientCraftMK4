package com.clientcraftmk4.mixin;

import com.clientcraftmk4.ClientCraftTab;
import net.minecraft.client.gui.screens.recipebook.CraftingRecipeBookComponent;
import net.minecraft.client.gui.screens.recipebook.RecipeBookComponent;
import net.minecraft.world.item.Items;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Mixin(CraftingRecipeBookComponent.class)
public class CraftingRecipeBookWidgetMixin {

    @ModifyArg(
            method = "<init>",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/screens/recipebook/RecipeBookComponent;<init>(Lnet/minecraft/world/inventory/RecipeBookMenu;Ljava/util/List;)V"),
            index = 1
    )
    private static List<RecipeBookComponent.TabInfo> clientcraft$addTab(List<RecipeBookComponent.TabInfo> tabs) {
        List<RecipeBookComponent.TabInfo> extended = new ArrayList<>(tabs);
        extended.add(new RecipeBookComponent.TabInfo(Items.CRAFTING_TABLE.getDefaultInstance(), Optional.empty(), ClientCraftTab.INSTANCE));
        return extended;
    }
}
