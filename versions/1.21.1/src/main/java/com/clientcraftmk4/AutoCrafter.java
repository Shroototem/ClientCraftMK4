package com.clientcraftmk4;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.gui.screen.ingame.CraftingScreen;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.client.MinecraftClient;
import net.minecraft.recipe.RecipeEntry;
import net.minecraft.screen.AbstractRecipeScreenHandler;
import net.minecraft.screen.slot.SlotActionType;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

public class AutoCrafter {
    private static final Logger LOG = LoggerFactory.getLogger("ClientCraftMK4");

    public enum Mode { ONCE, STACK, ALL }

    /** Result from buildCraftCyclesForMode containing the step list and whether craftAll can be used. */
    public record CraftPlan(List<List<RecipeEntry<?>>> cycles, boolean directCraft) {}

    private static List<RecipeEntry<?>> steps;
    private static boolean craftAll;
    private static int stepIndex;
    private static int tickCounter;
    private static long startTimeNs;
    private static int totalSteps;

    // Stability polling: after craft completes, wait for inventory to stop changing
    private static boolean pendingBatchClear = false;
    private static long lastSeenGen = -1;
    private static int stableFrames = 0;

    public static void execute(RecipeEntry<?> target, Mode mode) {
        if (steps != null) return;
        if (getHandler() == null) return;

        CraftPlan plan = RecipeResolver.buildCraftCyclesForMode(target, mode);
        if (plan == null || plan.cycles().isEmpty()) return;

        List<RecipeEntry<?>> flat = new ArrayList<>();
        for (List<RecipeEntry<?>> cycle : plan.cycles()) flat.addAll(cycle);

        steps = flat;
        craftAll = plan.directCraft();
        stepIndex = 0;
        tickCounter = 0;
        totalSteps = flat.size();
        startTimeNs = System.nanoTime();
        if (flat.size() > 10) RecipeResolver.batchMode = true;
    }

    public static void registerTickHandler() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            // Poll for inventory stability after craft completes
            if (pendingBatchClear) {
                RecipeResolver.pollInventory(); // ensure inventoryGeneration is up to date
                long gen = RecipeResolver.getInventoryGeneration();
                if (gen == lastSeenGen) {
                    if (++stableFrames >= 2) {
                        RecipeResolver.batchMode = false;
                        pendingBatchClear = false;
                        // Trigger a resolve now that inventory has stabilized
                        RecipeResolver.triggerRefresh();
                    }
                } else {
                    lastSeenGen = gen;
                    stableFrames = 0;
                }
            }

            if (steps == null) return;

            AbstractRecipeScreenHandler handler = getHandler();
            if (handler == null || client.interactionManager == null) {
                steps = null;
                RecipeResolver.batchMode = false;
                pendingBatchClear = false;
                return;
            }

            // If the output slot still has items, the previous craft couldn't be
            // moved to inventory (full) -- stop crafting.
            if (!handler.getSlot(0).getStack().isEmpty()) { steps = null; return; }

            int delay = ClientCraftConfig.delayTicks;
            if (delay <= 0) {
                long tExec = ClientCraftConfig.debugLogging ? System.nanoTime() : 0;
                for (RecipeEntry<?> step : steps) {
                    executeStep(client, handler, step);
                }
                if (ClientCraftConfig.debugLogging) {
                    long execNs = System.nanoTime() - tExec;
                    LOG.info("[CC] Execute steps: {}ms for {} steps ({}us/step)",
                            execNs / 1_000_000, steps.size(), steps.size() > 0 ? execNs / 1_000 / steps.size() : 0);
                }
                logCompletion();
                steps = null;
            } else {
                if (tickCounter++ >= delay) {
                    tickCounter = 0;
                    if (stepIndex < steps.size()) {
                        executeStep(client, handler, steps.get(stepIndex++));
                    }
                    if (stepIndex >= steps.size()) {
                        logCompletion();
                        steps = null;
                    }
                }
            }
        });
    }

    private static void logCompletion() {
        long elapsedMs = (System.nanoTime() - startTimeNs) / 1_000_000;
        if (ClientCraftConfig.debugLogging)
            LOG.info("[CC] Auto-craft completed: {} step(s) in {}ms", totalSteps, elapsedMs);
        pendingBatchClear = true;
        lastSeenGen = RecipeResolver.getInventoryGeneration();
        stableFrames = 0;
    }

    private static void executeStep(MinecraftClient client, AbstractRecipeScreenHandler handler, RecipeEntry<?> step) {
        client.interactionManager.clickRecipe(handler.syncId, step, craftAll);
        client.interactionManager.clickSlot(handler.syncId, 0, 0, SlotActionType.QUICK_MOVE, client.player);
    }

    private static AbstractRecipeScreenHandler getHandler() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.currentScreen instanceof CraftingScreen s) return s.getScreenHandler();
        if (client.currentScreen instanceof InventoryScreen s) return s.getScreenHandler();
        return null;
    }
}
