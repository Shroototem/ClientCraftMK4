package com.clientcraftmk4;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.gui.screen.ingame.CraftingScreen;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.client.MinecraftClient;
import net.minecraft.recipe.RecipeEntry;
import net.minecraft.screen.AbstractRecipeScreenHandler;
import net.minecraft.screen.slot.SlotActionType;

import java.util.ArrayList;
import java.util.List;

public class AutoCrafter {
    public enum Mode { ONCE, STACK, ALL }

    private static List<RecipeEntry<?>> steps;
    private static int stepIndex;
    private static int tickCounter;

    public static void execute(RecipeEntry<?> target, Mode mode) {
        if (steps != null) return;
        if (getHandler() == null) return;

        List<List<RecipeEntry<?>>> cycles = RecipeResolver.buildCraftCyclesForMode(target, mode);
        if (cycles == null || cycles.isEmpty()) return;

        List<RecipeEntry<?>> flat = new ArrayList<>();
        for (List<RecipeEntry<?>> cycle : cycles) flat.addAll(cycle);

        steps = flat;
        stepIndex = 0;
        tickCounter = 0;
    }

    public static void registerTickHandler() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (steps == null) return;

            AbstractRecipeScreenHandler handler = getHandler();
            if (handler == null || client.interactionManager == null) { steps = null; return; }

            // If the output slot still has items, the previous craft couldn't be
            // moved to inventory (full) — stop crafting.
            if (!handler.getSlot(0).getStack().isEmpty()) { steps = null; return; }

            int delay = ClientCraftConfig.delayTicks;
            if (delay <= 0) {
                for (RecipeEntry<?> step : steps) {
                    executeStep(client, handler, step);
                }
                steps = null;
            } else {
                if (tickCounter++ >= delay) {
                    tickCounter = 0;
                    if (stepIndex < steps.size()) {
                        executeStep(client, handler, steps.get(stepIndex++));
                    }
                    if (stepIndex >= steps.size()) steps = null;
                }
            }
        });
    }

    private static void executeStep(MinecraftClient client, AbstractRecipeScreenHandler handler, RecipeEntry<?> step) {
        client.interactionManager.clickRecipe(handler.syncId, step, false);
        client.interactionManager.clickSlot(handler.syncId, 0, 0, SlotActionType.QUICK_MOVE, client.player);
    }

    private static AbstractRecipeScreenHandler getHandler() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.currentScreen instanceof CraftingScreen s) return s.getScreenHandler();
        if (client.currentScreen instanceof InventoryScreen s) return s.getScreenHandler();
        return null;
    }
}
