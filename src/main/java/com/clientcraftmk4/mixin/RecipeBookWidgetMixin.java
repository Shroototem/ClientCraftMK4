package com.clientcraftmk4.mixin;

import com.clientcraftmk4.ClientCraftTab;
import com.clientcraftmk4.AutoCrafter;
import com.clientcraftmk4.RecipeResolver;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.recipebook.RecipeBookResults;
import net.minecraft.client.gui.screen.recipebook.RecipeBookWidget;
import net.minecraft.client.gui.screen.recipebook.RecipeGroupButtonWidget;
import net.minecraft.client.gui.screen.recipebook.RecipeResultCollection;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.item.ItemStack;
import net.minecraft.recipe.NetworkRecipeId;
import net.minecraft.recipe.RecipeDisplayEntry;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

@Mixin(RecipeBookWidget.class)
public class RecipeBookWidgetMixin {

    @Shadow
    private RecipeGroupButtonWidget currentTab;

    @Shadow
    private TextFieldWidget searchField;

    @Shadow
    @Final
    private RecipeBookResults recipesArea;

    @Inject(method = "select", at = @At("HEAD"), cancellable = true)
    private void clientcraft$onSelect(RecipeResultCollection results, NetworkRecipeId recipeId, boolean craftAll, CallbackInfoReturnable<Boolean> cir) {
        if (currentTab == null || !(currentTab.getCategory() instanceof ClientCraftTab)) return;

        if (recipeId.index() < 0) {
            cir.setReturnValue(false);
            return;
        }

        RecipeDisplayEntry target = null;
        for (RecipeDisplayEntry entry : results.getAllRecipes()) {
            if (entry.id().equals(recipeId)) { target = entry; break; }
        }
        if (target == null) return;

        long window = MinecraftClient.getInstance().getWindow().getHandle();
        boolean ctrlHeld = org.lwjgl.glfw.GLFW.glfwGetKey(window, org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT_CONTROL) == org.lwjgl.glfw.GLFW.GLFW_PRESS
                || org.lwjgl.glfw.GLFW.glfwGetKey(window, org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT_CONTROL) == org.lwjgl.glfw.GLFW.GLFW_PRESS;
        AutoCrafter.Mode mode = !craftAll ? AutoCrafter.Mode.ONCE
                : ctrlHeld ? AutoCrafter.Mode.ALL : AutoCrafter.Mode.STACK;

        AutoCrafter.execute(target, mode);
        cir.setReturnValue(true);
    }

    @Inject(method = "refreshResults", at = @At("HEAD"), cancellable = true)
    private void clientcraft$refreshResults(boolean resetCurrentPage, boolean filteringCraftable, CallbackInfo ci) {
        if (currentTab == null || !(currentTab.getCategory() instanceof ClientCraftTab)) return;

        // Register callback so background thread can refresh UI when done
        final boolean fc = filteringCraftable;
        RecipeResolver.setOnResultsPublished(() -> applyFilteredResults(false, fc));

        applyFilteredResults(resetCurrentPage, filteringCraftable);
        ci.cancel();
    }

    private void applyFilteredResults(boolean resetCurrentPage, boolean filteringCraftable) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return;

        List<RecipeResultCollection> list = client.player.getRecipeBook()
                .getResultsForCategory(currentTab.getCategory());
        List<RecipeResultCollection> filtered = new ArrayList<>(list);
        filtered.removeIf(coll -> !coll.hasDisplayableRecipes());

        String query = searchField != null ? searchField.getText().toLowerCase(Locale.ROOT) : "";
        if (!query.isEmpty()) {
            filtered.removeIf(coll -> {
                for (RecipeDisplayEntry entry : coll.getAllRecipes()) {
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
                if (coll.hasCraftableRecipes()) return false;
                for (RecipeDisplayEntry entry : coll.getAllRecipes()) {
                    if (RecipeResolver.isContainerCraftable(entry.id())) return false;
                }
                return true;
            });
        }

        filtered.sort(Comparator.comparingInt(RecipeResolver::getCollectionRank));

        recipesArea.setResults(filtered, resetCurrentPage, filteringCraftable);
    }
}
