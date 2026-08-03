package com.clientcraftmk4.mixin;

import com.clientcraftmk4.core.CraftModel;
import com.clientcraftmk4.core.RecipeDisplays;
import com.clientcraftmk4.craft.AutoCrafter;
import com.clientcraftmk4.mixin.accessor.RecipeBookComponentAccessor;
import com.clientcraftmk4.pipeline.ResolvePipeline;
import com.clientcraftmk4.ui.ClientCraftTab;
import com.clientcraftmk4.ui.ResultButtonRenderer;
import com.clientcraftmk4.ui.ScrollController;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.recipebook.RecipeBookComponent;
import net.minecraft.client.gui.screens.recipebook.RecipeBookPage;
import net.minecraft.client.gui.screens.recipebook.RecipeBookTabButton;
import net.minecraft.client.gui.screens.recipebook.RecipeCollection;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.display.RecipeDisplayEntry;
import net.minecraft.world.item.crafting.display.RecipeDisplayId;
import org.lwjgl.glfw.GLFW;
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

/**
 * Tab selection / refresh hijack + {@code tryPlaceRecipe} mode dispatch
 * (plan §13.3 / §13.4 / §14.5). The updateCollections inject owns the
 * auto-switch, the scroll-page registration and the pipeline callback.
 */
@Mixin(RecipeBookComponent.class)
public class RecipeBookComponentMixin {

    @Shadow
    private RecipeBookTabButton selectedTab;

    @Shadow
    private EditBox searchBox;

    @Shadow
    @Final
    private RecipeBookPage recipeBookPage;

    @Shadow
    private int width;

    @Shadow
    private int height;

    @Shadow
    private int xOffset;

    /**
     * The last tab the user chose via a tab-button click. Only explicit clicks
     * write this, and only on books that actually have the ClientCraft tab
     * (crafting table / inventory) — furnace & co. tabs are a different book
     * and must never clear the crafting-book memory.
     */
    @Inject(method = "onTabButtonPress", at = @At("HEAD"))
    private void clientcraft$rememberTabChoice(Button button, CallbackInfo ci) {
        if (!(button instanceof RecipeBookTabButton recipeBookTabButton)) return;
        List<RecipeBookTabButton> tabs = ((RecipeBookComponentAccessor) this).getTabButtons();
        for (RecipeBookTabButton tab : tabs) {
            if (tab.getCategory() instanceof ClientCraftTab) {
                ClientCraftTab.lastTabWasClientCraft = recipeBookTabButton.getCategory() instanceof ClientCraftTab;
                break;
            }
        }
    }

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
        boolean ctrlHeld = GLFW.glfwGetKey(window, GLFW.GLFW_KEY_LEFT_CONTROL) == GLFW.GLFW_PRESS
                || GLFW.glfwGetKey(window, GLFW.GLFW_KEY_RIGHT_CONTROL) == GLFW.GLFW_PRESS;
        AutoCrafter.Mode mode = !craftAll ? AutoCrafter.Mode.ONCE
                : ctrlHeld ? AutoCrafter.Mode.ALL : AutoCrafter.Mode.STACK;

        AutoCrafter.execute(target, mode);
        cir.setReturnValue(true);
    }

    @Inject(method = "updateCollections", at = @At("HEAD"), cancellable = true)
    private void clientcraft$refreshResults(boolean resetCurrentPage, boolean filteringCraftable, CallbackInfo ci) {
        // Auto-switch to the ClientCraft tab whenever it was the last tab the
        // user selected. Runs on every refresh (not once per screen), so it
        // survives opening other inventories in between.
        if (ClientCraftTab.lastTabWasClientCraft
                && selectedTab != null && !(selectedTab.getCategory() instanceof ClientCraftTab)) {
            List<RecipeBookTabButton> tabs = ((RecipeBookComponentAccessor) this).getTabButtons();
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
            ScrollController.clearActivePage();
            return;
        }

        ClientCraftTab.lastTabWasClientCraft = true;

        int bookLeft = (width - 147) / 2 - xOffset;
        int bookTop = (height - 166) / 2;
        ScrollController.setActivePage(recipeBookPage, bookLeft, bookTop);

        // Register callback so the background thread can refresh the UI when done
        final boolean fc = filteringCraftable;
        ResolvePipeline.setOnPublished(r -> applyFilteredResults(false, fc));

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

        CraftModel model = CraftModel.current();
        if (model != null) {
            String query = searchBox != null ? searchBox.getValue().toLowerCase(Locale.ROOT) : "";
            if (!query.isEmpty()) {
                filtered.removeIf(coll -> {
                    for (RecipeDisplayEntry entry : coll.getRecipes()) {
                        ItemStack result = RecipeDisplays.resolveResult(entry.display(), model.tagIndex());
                        if (!result.isEmpty()) {
                            String name = model.recipeIndex().getLowerCaseName(result.getItem());
                            if (name.contains(query)) return false;
                        }
                    }
                    return true;
                });
            }
        }

        if (filteringCraftable) {
            filtered.removeIf(coll -> {
                if (coll.hasCraftable()) return false;
                for (RecipeDisplayEntry entry : coll.getRecipes()) {
                    if (ResultButtonRenderer.isContainerCraftable(entry.id())) return false;
                }
                return true;
            });
        }

        filtered.sort(Comparator.comparingInt(ResultButtonRenderer::getCollectionRank));

        recipeBookPage.updateCollections(filtered, resetCurrentPage, filteringCraftable);
    }
}
