package com.clientcraftmk4.mixin;

import net.minecraft.client.gui.screens.recipebook.RecipeCollection;
import net.minecraft.world.item.crafting.display.RecipeDisplayId;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Set;

@Mixin(RecipeCollection.class)
public interface RecipeResultCollectionAccessor {

    @Accessor("craftable")
    Set<RecipeDisplayId> getCraftableRecipes();

    @Accessor("selected")
    Set<RecipeDisplayId> getDisplayableRecipes();
}
