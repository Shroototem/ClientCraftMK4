package com.clientcraftmk4.ui;

import net.minecraft.client.gui.screens.recipebook.OverlayRecipeComponent;
import net.minecraft.client.gui.screens.recipebook.RecipeCollection;
import net.minecraft.world.item.crafting.display.RecipeDisplayEntry;

import java.lang.ref.WeakReference;
import java.util.List;

/**
 * Variant-browsing state for the ingredient-grid overlay (plan §13.6 / §5.7).
 * Render-thread confined; extracted from MK4's RecipeResolver statics.
 */
public final class VariantState {
    private static WeakReference<OverlayRecipeComponent> activeOverlayRef = new WeakReference<>(null);
    private static List<RecipeDisplayEntry> activeVariants = List.of();
    private static int activeVariantIndex = 0;
    private static RecipeCollection activeCollection = null;
    private static int overlayButtonX, overlayButtonY, overlayCenterX, overlayCenterY, overlayButtonWidth;

    private VariantState() {}

    public static void setActiveOverlay(OverlayRecipeComponent overlay) {
        activeOverlayRef = new WeakReference<>(overlay);
    }

    public static OverlayRecipeComponent getActiveOverlay() {
        return activeOverlayRef.get();
    }

    public static void clear() {
        activeOverlayRef = new WeakReference<>(null);
        activeVariants = List.of();
        activeVariantIndex = 0;
        activeCollection = null;
    }

    public static void setActiveVariants(List<RecipeDisplayEntry> variants, int initialIndex, RecipeCollection collection) {
        activeVariants = variants;
        activeVariantIndex = Math.clamp(initialIndex, 0, variants.size() - 1);
        activeCollection = collection;
    }

    public static void setOverlayPosition(int buttonX, int buttonY, int centerX, int centerY, int buttonWidth) {
        overlayButtonX = buttonX;
        overlayButtonY = buttonY;
        overlayCenterX = centerX;
        overlayCenterY = centerY;
        overlayButtonWidth = buttonWidth;
    }

    public static int getOverlayButtonX() { return overlayButtonX; }
    public static int getOverlayButtonY() { return overlayButtonY; }
    public static int getOverlayCenterX() { return overlayCenterX; }
    public static int getOverlayCenterY() { return overlayCenterY; }
    public static int getOverlayButtonWidth() { return overlayButtonWidth; }

    public static int getActiveVariantIndex() { return activeVariantIndex; }
    public static int getActiveVariantCount() { return activeVariants.size(); }
    public static RecipeDisplayEntry getActiveVariant() {
        return activeVariants.isEmpty() ? null : activeVariants.get(activeVariantIndex);
    }
    public static RecipeCollection getActiveCollection() { return activeCollection; }

    /** Cycles to the next/previous variant (wraps modulo) and returns the new entry. */
    public static RecipeDisplayEntry cycleActiveVariant(int delta) {
        if (activeVariants.isEmpty()) return null;
        int count = activeVariants.size();
        activeVariantIndex = ((activeVariantIndex + delta) % count + count) % count;
        return activeVariants.get(activeVariantIndex);
    }
}
