package com.clientcraftmk4.mixin;

import com.clientcraftmk4.ClientCraftTab;
import com.clientcraftmk4.AutoCrafter;
import com.clientcraftmk4.RecipeResolver;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.recipebook.RecipeBookGhostSlots;
import net.minecraft.client.gui.screen.recipebook.RecipeBookResults;
import net.minecraft.client.gui.screen.recipebook.RecipeBookWidget;
import net.minecraft.client.gui.screen.recipebook.RecipeGroupButtonWidget;
import net.minecraft.client.gui.screen.recipebook.RecipeResultCollection;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.item.ItemStack;
import net.minecraft.recipe.RecipeEntry;
import net.minecraft.recipe.book.RecipeBookCategory;
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

@Mixin(RecipeBookWidget.class)
public class RecipeBookWidgetMixin {

    @Shadow
    private RecipeGroupButtonWidget currentTab;

    @Shadow
    private TextFieldWidget searchField;

    @Shadow
    @Final
    private RecipeBookResults recipesArea;

    @Shadow
    @Final
    private List<RecipeGroupButtonWidget> tabButtons;

    @Shadow
    private int parentWidth;

    @Shadow
    private int parentHeight;

    @Shadow
    private int leftOffset;

    @Shadow
    @Final
    protected RecipeBookGhostSlots ghostSlots;

    @Unique
    private boolean clientcraft$hasAutoSwitched = false;

    @Unique
    private boolean clientcraft$wasClientCraftTab = false;

    @Inject(method = "reset", at = @At("HEAD"))
    private void clientcraft$onReset(CallbackInfo ci) {
        clientcraft$hasAutoSwitched = false;
        // Save this before refreshResults() clears it (reset calls refreshResults before refreshTabButtons)
        clientcraft$wasClientCraftTab = RecipeResolver.lastTabWasClientCraft;
    }

    /**
     * In 1.21.1 there's no CraftingRecipeBookWidget, so we inject our custom tab
     * by hooking into refreshTabButtons at TAIL to add and position our tab.
     * Vanilla only positions tabs where hasKnownRecipes() returns true, so we
     * must handle our custom tab's positioning manually.
     */
    @Inject(method = "refreshTabButtons", at = @At("TAIL"))
    private void clientcraft$addAndPositionCustomTab(CallbackInfo ci) {
        RecipeGroupButtonWidget customTab = null;
        for (RecipeGroupButtonWidget tab : tabButtons) {
            if (tab.getCategory() == ClientCraftTab.INSTANCE) {
                customTab = tab;
                break;
            }
        }
        if (customTab == null) {
            customTab = new RecipeGroupButtonWidget(ClientCraftTab.INSTANCE);
            tabButtons.add(customTab);
        }

        int x = (parentWidth - 147) / 2 - leftOffset - 30;
        int y = (parentHeight - 166) / 2 + 3;
        int tabIndex = 0;
        for (RecipeGroupButtonWidget tab : tabButtons) {
            if (tab.visible && tab.getCategory() != ClientCraftTab.INSTANCE) {
                tabIndex++;
            }
        }
        customTab.visible = true;
        customTab.setPosition(x, y + 27 * tabIndex);

        // Auto-switch to our tab if it was the last used tab.
        // We use the saved flag because reset() calls refreshResults() BEFORE
        // refreshTabButtons(), and refreshResults clears lastTabWasClientCraft.
        if (!clientcraft$hasAutoSwitched && clientcraft$wasClientCraftTab
                && currentTab != null && currentTab.getCategory() != ClientCraftTab.INSTANCE) {
            clientcraft$hasAutoSwitched = true;
            currentTab.setToggled(false);
            currentTab = customTab;
            currentTab.setToggled(true);
            applyFilteredResults(true);
        }
    }

    /**
     * In 1.21.1, RecipeBookWidget doesn't have a "select" method. Instead,
     * recipe clicks are handled through RecipeBookResults -> RecipeBookWidget.onRecipesDisplayed.
     * We intercept mouseClicked at RETURN so vanilla's recipesArea.mouseClicked() runs first
     * and sets the freshly-clicked recipe via getLastClickedRecipe().
     */
    @Inject(method = "mouseClicked", at = @At("RETURN"))
    private void clientcraft$onMouseClicked(double mouseX, double mouseY, int button, CallbackInfoReturnable<Boolean> cir) {
        if (currentTab == null || currentTab.getCategory() != ClientCraftTab.INSTANCE) return;

        RecipeEntry<?> lastClicked = recipesArea.getLastClickedRecipe();
        if (lastClicked == null) return;

        if (lastClicked.id().getPath().startsWith("fake_")) return;

        // Clear the ghost recipe that vanilla placed when it handled the click
        ghostSlots.reset();

        long window = MinecraftClient.getInstance().getWindow().getHandle();
        boolean shiftHeld = org.lwjgl.glfw.GLFW.glfwGetKey(window, org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT_SHIFT) == org.lwjgl.glfw.GLFW.GLFW_PRESS
                || org.lwjgl.glfw.GLFW.glfwGetKey(window, org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT_SHIFT) == org.lwjgl.glfw.GLFW.GLFW_PRESS;
        boolean ctrlHeld = org.lwjgl.glfw.GLFW.glfwGetKey(window, org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT_CONTROL) == org.lwjgl.glfw.GLFW.GLFW_PRESS
                || org.lwjgl.glfw.GLFW.glfwGetKey(window, org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT_CONTROL) == org.lwjgl.glfw.GLFW.GLFW_PRESS;
        AutoCrafter.Mode mode = !shiftHeld ? AutoCrafter.Mode.ONCE
                : ctrlHeld ? AutoCrafter.Mode.ALL : AutoCrafter.Mode.STACK;

        AutoCrafter.execute(lastClicked, mode);
    }

    /**
     * Suppress ghost recipe rendering while on the ClientCraft tab.
     * Vanilla sets ghost slots when a recipe click is sent to the server,
     * but our AutoCrafter handles crafting directly — the ghost overlay is unwanted.
     */
    @Inject(method = "drawGhostSlots", at = @At("HEAD"), cancellable = true)
    private void clientcraft$suppressGhostSlots(CallbackInfo ci) {
        if (currentTab != null && currentTab.getCategory() == ClientCraftTab.INSTANCE) {
            ghostSlots.reset();
            ci.cancel();
        }
    }

    @Inject(method = "refreshResults", at = @At("HEAD"), cancellable = true)
    private void clientcraft$refreshResults(boolean resetCurrentPage, CallbackInfo ci) {
        if (currentTab == null || currentTab.getCategory() != ClientCraftTab.INSTANCE) {
            RecipeResolver.lastTabWasClientCraft = false;
            return;
        }

        RecipeResolver.lastTabWasClientCraft = true;

        // Register callback so background thread can refresh UI when done
        RecipeResolver.setOnResultsPublished(() -> applyFilteredResults(false));

        applyFilteredResults(resetCurrentPage);
        ci.cancel();
    }

    private void applyFilteredResults(boolean resetCurrentPage) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return;

        List<RecipeResultCollection> list = client.player.getRecipeBook()
                .getResultsForGroup(currentTab.getCategory());
        List<RecipeResultCollection> filtered = new ArrayList<>(list);
        filtered.removeIf(coll -> !coll.hasFittingRecipes());

        String query = searchField != null ? searchField.getText().toLowerCase(Locale.ROOT) : "";
        if (!query.isEmpty()) {
            filtered.removeIf(coll -> {
                for (RecipeEntry<?> entry : coll.getAllRecipes()) {
                    ItemStack result = RecipeResolver.resolveResult(entry);
                    if (!result.isEmpty()) {
                        String name = RecipeResolver.getLowerCaseName(result.getItem());
                        if (name.contains(query)) return false;
                    }
                }
                return true;
            });
        }

        boolean filteringCraftable = client.player.getRecipeBook().isFilteringCraftable(RecipeBookCategory.CRAFTING);
        if (filteringCraftable) {
            filtered.removeIf(coll -> {
                if (coll.hasCraftableRecipes()) return false;
                for (RecipeEntry<?> entry : coll.getAllRecipes()) {
                    if (RecipeResolver.isContainerCraftable(entry.id())) return false;
                }
                return true;
            });
        }

        filtered.sort(Comparator.comparingInt(RecipeResolver::getCollectionRank));

        recipesArea.setResults(filtered, resetCurrentPage);
    }
}
