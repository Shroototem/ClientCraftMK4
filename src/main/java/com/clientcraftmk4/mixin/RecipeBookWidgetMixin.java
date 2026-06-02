package com.clientcraftmk4.mixin;

import com.clientcraftmk4.ClientCraftTab;
import com.clientcraftmk4.AutoCrafter;
import com.clientcraftmk4.RecipeResolver;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.recipebook.RecipeBookPage;
import net.minecraft.client.gui.screens.recipebook.RecipeBookComponent;
import net.minecraft.client.gui.screens.recipebook.RecipeBookTabButton;
import net.minecraft.client.gui.screens.recipebook.RecipeCollection;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.display.RecipeDisplayId;
import net.minecraft.world.item.crafting.display.RecipeDisplayEntry;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

@Mixin(RecipeBookComponent.class)
public class RecipeBookWidgetMixin {

    @Shadow
    private RecipeBookTabButton selectedTab;

    @Shadow
    private EditBox searchBox;

    @Shadow
    @Final
    private RecipeBookPage recipeBookPage;

    @Unique
    private boolean clientcraft$hasAutoSwitched = false;

    @Inject(method = "tryPlaceRecipe", at = @At("HEAD"), cancellable = true)
    private void clientcraft$onSelect(RecipeCollection results, RecipeDisplayId recipeId, boolean craftAll, CallbackInfoReturnable<Boolean> cir) {
        if (selectedTab == null || !(selectedTab.getCategory() instanceof ClientCraftTab)) return;

        if (recipeId.index() < 0) {
            cir.setReturnValue(false);
            return;
        }

        RecipeDisplayEntry target = null;
        for (RecipeDisplayEntry entry : results.getRecipes()) {
            if (entry.id().equals(recipeId)) { target = entry; break; }
        }
        if (target == null) return;

        long window = Minecraft.getInstance().getWindow().handle();
        boolean ctrlHeld = org.lwjgl.glfw.GLFW.glfwGetKey(window, org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT_CONTROL) == org.lwjgl.glfw.GLFW.GLFW_PRESS
                || org.lwjgl.glfw.GLFW.glfwGetKey(window, org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT_CONTROL) == org.lwjgl.glfw.GLFW.GLFW_PRESS;
        AutoCrafter.Mode mode = !craftAll ? AutoCrafter.Mode.ONCE
                : ctrlHeld ? AutoCrafter.Mode.ALL : AutoCrafter.Mode.STACK;

        AutoCrafter.execute(target, mode);
        cir.setReturnValue(true);
    }

    @Inject(method = "updateCollections", at = @At("HEAD"), cancellable = true)
    private void clientcraft$refreshResults(boolean resetCurrentPage, boolean filteringCraftable, CallbackInfo ci) {
        // Auto-switch to ClientCraft tab once per screen open if it was the last used tab
        if (!clientcraft$hasAutoSwitched && RecipeResolver.lastTabWasClientCraft
                && selectedTab != null && !(selectedTab.getCategory() instanceof ClientCraftTab)) {
            clientcraft$hasAutoSwitched = true;
            List<RecipeBookTabButton> tabs = ((RecipeBookWidgetAccessor) this).getTabButtons();
            for (RecipeBookTabButton tab : tabs) {
                if (tab.getCategory() instanceof ClientCraftTab) {
                    selectedTab.unselect();
                    selectedTab = tab;
                    selectedTab.select();
                    break;
                }
            }
        }

        if (selectedTab == null || !(selectedTab.getCategory() instanceof ClientCraftTab)) {
            RecipeResolver.lastTabWasClientCraft = false;
            return;
        }

        RecipeResolver.lastTabWasClientCraft = true;

        // Register callback so background thread can refresh UI when done
        final boolean fc = filteringCraftable;
        RecipeResolver.setOnResultsPublished(() -> applyFilteredResults(false, fc));

        applyFilteredResults(resetCurrentPage, filteringCraftable);
        ci.cancel();
    }

    private void applyFilteredResults(boolean resetCurrentPage, boolean filteringCraftable) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null) return;

        List<RecipeCollection> list = client.player.getRecipeBook()
                .getCollection(selectedTab.getCategory());
        List<RecipeCollection> filtered = new ArrayList<>(list);
        filtered.removeIf(coll -> !coll.hasAnySelected());

        String query = searchBox != null ? searchBox.getValue().toLowerCase(Locale.ROOT) : "";
        if (!query.isEmpty()) {
            filtered.removeIf(coll -> {
                for (RecipeDisplayEntry entry : coll.getRecipes()) {
                    ItemStack result = RecipeResolver.resolveResult(entry.display());
                    if (!result.isEmpty()) {
                        String name = RecipeResolver.getLowerCaseName(result.getItem());
                        if (name.contains(query)) return false;
                    }
                }
                return true;
            });
        }

        if (filteringCraftable) {
            filtered.removeIf(coll -> {
                if (coll.hasCraftable()) return false;
                for (RecipeDisplayEntry entry : coll.getRecipes()) {
                    if (RecipeResolver.isContainerCraftable(entry.id())) return false;
                }
                return true;
            });
        }

        filtered.sort(Comparator.comparingInt(RecipeResolver::getCollectionRank));

        recipeBookPage.updateCollections(filtered, resetCurrentPage, filteringCraftable);
    }
}
