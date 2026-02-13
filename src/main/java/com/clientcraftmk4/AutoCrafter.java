package com.clientcraftmk4;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.gui.screen.ingame.CraftingScreen;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.ItemStack;
import net.minecraft.recipe.NetworkRecipeId;
import net.minecraft.recipe.RecipeDisplayEntry;
import net.minecraft.screen.AbstractCraftingScreenHandler;
import net.minecraft.screen.slot.SlotActionType;

import java.util.List;

public class AutoCrafter {
    public enum Mode { ONCE, STACK, ALL }

    private static List<List<NetworkRecipeId>> craftCycles;

    public static void execute(RecipeDisplayEntry target, Mode mode) {
        if (craftCycles != null) return;
        if (getHandler() == null) return;

        ItemStack output = RecipeResolver.resolveResult(target.display());
        int outputCount = output.getCount();
        int maxCraftable = RecipeResolver.countMaxRepeats(target);
        int totalRepeats;

        switch (mode) {
            case ONCE -> totalRepeats = Math.min(1, maxCraftable);
            case STACK -> {
                int stackRepeats = (output.getMaxCount() + outputCount - 1) / outputCount;
                totalRepeats = Math.min(stackRepeats, maxCraftable);
            }
            case ALL -> totalRepeats = maxCraftable;
            default -> totalRepeats = 0;
        }

        if (totalRepeats <= 0) return;

        List<List<NetworkRecipeId>> cycles = RecipeResolver.buildAllCraftCycles(target, totalRepeats);
        if (cycles == null || cycles.isEmpty()) return;

        craftCycles = cycles;
    }

    public static void registerTickHandler() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (craftCycles == null) return;

            AbstractCraftingScreenHandler handler = getHandler();
            if (handler == null || client.interactionManager == null) { craftCycles = null; return; }

            for (List<NetworkRecipeId> cycle : craftCycles) {
                for (NetworkRecipeId step : cycle) {
                    client.interactionManager.clickRecipe(handler.syncId, step, false);
                    client.interactionManager.clickSlot(handler.syncId, 0, 0, SlotActionType.QUICK_MOVE, client.player);
                }
            }
            craftCycles = null;
        });
    }

    private static AbstractCraftingScreenHandler getHandler() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.currentScreen instanceof CraftingScreen s) return s.getScreenHandler();
        if (client.currentScreen instanceof InventoryScreen s) return s.getScreenHandler();
        return null;
    }
}
