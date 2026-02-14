package com.clientcraftmk4;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.gui.screen.ingame.CraftingScreen;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.client.MinecraftClient;
import net.minecraft.recipe.NetworkRecipeId;
import net.minecraft.recipe.RecipeDisplayEntry;
import net.minecraft.screen.AbstractCraftingScreenHandler;
import net.minecraft.screen.slot.SlotActionType;

import java.util.List;

public class AutoCrafter {
    public enum Mode { ONCE, STACK, ALL }

    private static List<List<NetworkRecipeId>> craftCycles;
    private static int cycleIndex;
    private static int tickCounter;

    public static void execute(RecipeDisplayEntry target, Mode mode) {
        if (craftCycles != null) return;
        if (getHandler() == null) return;

        List<List<NetworkRecipeId>> cycles = RecipeResolver.buildCraftCyclesForMode(target, mode);
        if (cycles == null || cycles.isEmpty()) return;

        craftCycles = cycles;
        cycleIndex = 0;
        tickCounter = 0;
    }

    public static void registerTickHandler() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (craftCycles == null) return;

            AbstractCraftingScreenHandler handler = getHandler();
            if (handler == null || client.interactionManager == null) { craftCycles = null; return; }

            int delay = ClientCraftConfig.delayTicks;
            if (delay <= 0) {
                for (List<NetworkRecipeId> cycle : craftCycles) {
                    executeCycle(client, handler, cycle);
                }
                craftCycles = null;
            } else {
                if (tickCounter++ >= delay) {
                    tickCounter = 0;
                    if (cycleIndex < craftCycles.size()) {
                        executeCycle(client, handler, craftCycles.get(cycleIndex++));
                    }
                    if (cycleIndex >= craftCycles.size()) craftCycles = null;
                }
            }
        });
    }

    private static void executeCycle(MinecraftClient client, AbstractCraftingScreenHandler handler, List<NetworkRecipeId> cycle) {
        for (NetworkRecipeId step : cycle) {
            client.interactionManager.clickRecipe(handler.syncId, step, false);
            client.interactionManager.clickSlot(handler.syncId, 0, 0, SlotActionType.QUICK_MOVE, client.player);
        }
    }

    private static AbstractCraftingScreenHandler getHandler() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.currentScreen instanceof CraftingScreen s) return s.getScreenHandler();
        if (client.currentScreen instanceof InventoryScreen s) return s.getScreenHandler();
        return null;
    }
}
