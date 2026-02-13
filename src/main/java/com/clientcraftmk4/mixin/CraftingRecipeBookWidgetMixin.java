package com.clientcraftmk4.mixin;

import com.clientcraftmk4.ClientCraftTab;
import net.minecraft.client.gui.screen.recipebook.CraftingRecipeBookWidget;
import net.minecraft.client.gui.screen.recipebook.RecipeBookWidget;
import net.minecraft.item.Items;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Mixin(CraftingRecipeBookWidget.class)
public class CraftingRecipeBookWidgetMixin {

    @ModifyArg(
            method = "<init>",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/screen/recipebook/RecipeBookWidget;<init>(Lnet/minecraft/screen/AbstractRecipeScreenHandler;Ljava/util/List;)V"),
            index = 1
    )
    private static List<RecipeBookWidget.Tab> clientcraft$addTab(List<RecipeBookWidget.Tab> tabs) {
        List<RecipeBookWidget.Tab> extended = new ArrayList<>(tabs);
        extended.add(new RecipeBookWidget.Tab(Items.CRAFTING_TABLE.getDefaultStack(), Optional.empty(), ClientCraftTab.INSTANCE));
        return extended;
    }
}
