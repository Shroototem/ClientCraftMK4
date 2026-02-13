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

        // Ignore clicks on our fake ingredient tiles (negative IDs)
        if (recipeId.index() < 0) {
            cir.setReturnValue(false);
            return;
        }

        RecipeDisplayEntry target = null;
        for (RecipeDisplayEntry entry : results.getAllRecipes()) {
            if (entry.id().equals(recipeId)) { target = entry; break; }
        }
        if (target == null) return;

        // Verify the recipe is craftable before starting
        List<NetworkRecipeId> sequence = RecipeResolver.buildCraftSequence(target);
        if (sequence == null || sequence.isEmpty()) return;

        AutoCrafter.Mode mode;
        long window = MinecraftClient.getInstance().getWindow().getHandle();
        boolean ctrlHeld = org.lwjgl.glfw.GLFW.glfwGetKey(window, org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT_CONTROL) == org.lwjgl.glfw.GLFW.GLFW_PRESS
                || org.lwjgl.glfw.GLFW.glfwGetKey(window, org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT_CONTROL) == org.lwjgl.glfw.GLFW.GLFW_PRESS;
        if (craftAll && ctrlHeld) {
            mode = AutoCrafter.Mode.ALL;
        } else if (craftAll) {
            mode = AutoCrafter.Mode.STACK;
        } else {
            mode = AutoCrafter.Mode.ONCE;
        }

        AutoCrafter.execute(target, mode);
        cir.setReturnValue(true);
    }

    /**
     * Override search filtering for the ClientCraft tab.
     * Vanilla's search uses a search manager index that doesn't contain our custom collections,
     * so we filter by matching the search text against recipe output item names ourselves.
     */
    @Inject(method = "refreshResults", at = @At("HEAD"), cancellable = true)
    private void clientcraft$refreshResults(boolean resetCurrentPage, boolean filteringCraftable, CallbackInfo ci) {
        if (currentTab == null || !(currentTab.getCategory() instanceof ClientCraftTab)) return;

        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return;

        List<RecipeResultCollection> list = client.player.getRecipeBook()
                .getResultsForCategory(currentTab.getCategory());
        List<RecipeResultCollection> filtered = new ArrayList<>(list);
        filtered.removeIf(coll -> !coll.hasDisplayableRecipes());

        // Apply our own search filtering
        String query = searchField != null ? searchField.getText().toLowerCase(Locale.ROOT) : "";
        if (!query.isEmpty()) {
            filtered.removeIf(coll -> {
                for (RecipeDisplayEntry entry : coll.getAllRecipes()) {
                    ItemStack result = RecipeResolver.resolveResult(entry.display());
                    if (!result.isEmpty()) {
                        String name = result.getName().getString().toLowerCase(Locale.ROOT);
                        if (name.contains(query)) return false;
                    }
                }
                return true;
            });
        }

        if (filteringCraftable) {
            filtered.removeIf(coll -> !coll.hasCraftableRecipes());
        }

        recipesArea.setResults(filtered, resetCurrentPage, filteringCraftable);
        ci.cancel();
    }
}
