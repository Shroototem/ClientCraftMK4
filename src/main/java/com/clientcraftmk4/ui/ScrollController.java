package com.clientcraftmk4.ui;

import com.clientcraftmk4.core.GameContext;
import com.clientcraftmk4.mixin.accessor.RecipeBookPageAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.recipebook.OverlayRecipeComponent;
import net.minecraft.client.gui.screens.recipebook.RecipeBookPage;
import net.minecraft.client.gui.screens.recipebook.RecipeCollection;
import net.minecraft.util.context.ContextMap;
import net.minecraft.world.item.crafting.display.RecipeDisplayEntry;
import net.minecraft.world.item.crafting.display.SlotDisplayContext;

import java.lang.ref.WeakReference;

/**
 * Scroll-wheel dispatch: overlay variant cycling → overlay no-op → book page
 * cycling (plan §13.7). Also owns the active book page reference with its
 * owning-screen guard so closed screens can't hijack scrolling.
 */
public final class ScrollController {
    private static final int BOOK_WIDTH = 147;
    private static final int BOOK_HEIGHT = 166;

    private static volatile WeakReference<RecipeBookPage> activeRecipeBookPage = new WeakReference<>(null);
    private static volatile WeakReference<Screen> activeRecipeBookScreen = new WeakReference<>(null);
    private static volatile int bookLeft, bookTop;

    private ScrollController() {}

    public static void setActivePage(RecipeBookPage page, int left, int top) {
        activeRecipeBookPage = new WeakReference<>(page);
        activeRecipeBookScreen = new WeakReference<>(GameContext.currentScreen());
        bookLeft = left;
        bookTop = top;
    }

    public static void clearActivePage() {
        activeRecipeBookPage = new WeakReference<>(null);
        activeRecipeBookScreen = new WeakReference<>(null);
    }

    /** The active page, or null if the owning screen is no longer open. */
    public static RecipeBookPage activePage() {
        Screen current = GameContext.currentScreen();
        Screen owner = activeRecipeBookScreen.get();
        if (current == null || current != owner) return null;
        return activeRecipeBookPage.get();
    }

    public static boolean isMouseOverBook(double mouseX, double mouseY) {
        return mouseX >= bookLeft && mouseX < bookLeft + BOOK_WIDTH
                && mouseY >= bookTop && mouseY < bookTop + BOOK_HEIGHT;
    }

    /**
     * Handles a scroll event; returns true when vanilla scrolling must be cancelled.
     * Priority: overlay variant cycle (variants > 1) → overlay visible (no-op, let
     * vanilla continue) → page cycle when hovering the book.
     */
    public static boolean handleScroll(RecipeBookPage page, int totalPages,
                                       double mouseX, double mouseY, double yoffset) {
        if (page == null) return false;

        RecipeBookPageAccessor acc = (RecipeBookPageAccessor) page;
        OverlayRecipeComponent overlay = acc.getOverlay();

        // Priority 1: overlay variant cycling
        if (overlay != null && overlay.isVisible() && VariantState.getActiveVariantCount() > 1) {
            int delta = yoffset > 0 ? -1 : 1;
            RecipeDisplayEntry next = VariantState.cycleActiveVariant(delta);
            if (next != null) {
                RecipeCollection ingredientCollection = OverlayBuilder.buildIngredientCollection(next);
                if (ingredientCollection != null) {
                    ContextMap context = SlotDisplayContext.fromLevel(Minecraft.getInstance().level);
                    overlay.init(
                            ingredientCollection,
                            context,
                            false,
                            VariantState.getOverlayButtonX(),
                            VariantState.getOverlayButtonY(),
                            VariantState.getOverlayCenterX(),
                            VariantState.getOverlayCenterY(),
                            VariantState.getOverlayButtonWidth()
                    );
                }
            }
            return true;
        }

        // Block page cycling when the overlay is open (even with only 1 variant)
        if (overlay != null && overlay.isVisible()) return false;

        // Priority 2: page cycling — only when hovering over the recipe book area
        if (yoffset == 0) return false;
        if (totalPages <= 1) return false;
        if (!isMouseOverBook(mouseX, mouseY)) return false;

        ((RecipeBookPageCycleAccessor) page).clientcraft$cyclePage((int) -Math.signum(yoffset));
        return true;
    }
}
