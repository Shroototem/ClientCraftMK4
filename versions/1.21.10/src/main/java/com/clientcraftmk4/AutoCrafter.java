package com.clientcraftmk4;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.gui.screen.ingame.CraftingScreen;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.client.MinecraftClient;
import net.minecraft.recipe.NetworkRecipeId;
import net.minecraft.recipe.RecipeDisplayEntry;
import net.minecraft.screen.AbstractCraftingScreenHandler;
import net.minecraft.screen.slot.SlotActionType;

import java.util.ArrayList;
import java.util.List;

public class AutoCrafter {
    public enum Mode { ONCE, STACK, ALL }

    /** Result from buildCraftCyclesForMode containing the step list and whether craftAll can be used. */
    public record CraftPlan(List<List<NetworkRecipeId>> cycles, boolean directCraft) {}

    private static List<NetworkRecipeId> steps;
    private static boolean craftAll;
    private static int stepIndex;
    private static int tickCounter;

    public static void execute(RecipeDisplayEntry target, Mode mode) {
        if (steps != null) return;
        if (getHandler() == null) return;

        CraftPlan plan = RecipeResolver.buildCraftCyclesForMode(target, mode);
        if (plan == null || plan.cycles().isEmpty()) return;

        // Flatten all cycles into a single step list
        List<NetworkRecipeId> flat = new ArrayList<>();
        for (List<NetworkRecipeId> cycle : plan.cycles()) flat.addAll(cycle);

        steps = flat;
        craftAll = plan.directCraft();
        stepIndex = 0;
        tickCounter = 0;
    }

    public static void registerTickHandler() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (steps == null) return;

            AbstractCraftingScreenHandler handler = getHandler();
            if (handler == null || client.interactionManager == null) { steps = null; return; }

            int delay = ClientCraftConfig.delayTicks;
            if (delay <= 0) {
                for (NetworkRecipeId step : steps) {
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

    private static void executeStep(MinecraftClient client, AbstractCraftingScreenHandler handler, NetworkRecipeId step) {
        client.interactionManager.clickRecipe(handler.syncId, step, craftAll);
        client.interactionManager.clickSlot(handler.syncId, 0, 0, SlotActionType.QUICK_MOVE, client.player);
    }

    private static AbstractCraftingScreenHandler getHandler() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.currentScreen instanceof CraftingScreen s) return s.getScreenHandler();
        if (client.currentScreen instanceof InventoryScreen s) return s.getScreenHandler();
        return null;
    }
}
