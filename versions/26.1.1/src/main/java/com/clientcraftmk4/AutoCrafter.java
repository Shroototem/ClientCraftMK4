package com.clientcraftmk4;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.gui.screens.inventory.CraftingScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.crafting.display.RecipeDisplayId;
import net.minecraft.world.item.crafting.display.RecipeDisplayEntry;
import net.minecraft.world.inventory.AbstractCraftingMenu;
import net.minecraft.world.inventory.ContainerInput;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

public class AutoCrafter {
    private static final Logger LOG = LoggerFactory.getLogger("ClientCraftMK4");

    public enum Mode { ONCE, STACK, ALL }

    /** Result from buildCraftCyclesForMode containing the step list and whether craftAll can be used. */
    public record CraftPlan(List<List<RecipeDisplayId>> cycles, boolean directCraft) {}

    private static List<RecipeDisplayId> steps;
    private static boolean craftAll;
    private static int stepIndex;
    private static int tickCounter;
    private static long startTimeNs;
    private static int totalSteps;

    public static void execute(RecipeDisplayEntry target, Mode mode) {
        if (steps != null) return;
        if (getHandler() == null) return;

        CraftPlan plan = RecipeResolver.buildCraftCyclesForMode(target, mode);
        if (plan == null || plan.cycles().isEmpty()) return;

        // Flatten all cycles into a single step list
        List<RecipeDisplayId> flat = new ArrayList<>();
        for (List<RecipeDisplayId> cycle : plan.cycles()) flat.addAll(cycle);

        steps = flat;
        craftAll = plan.directCraft();
        stepIndex = 0;
        tickCounter = 0;
        totalSteps = flat.size();
        startTimeNs = System.nanoTime();
    }

    public static void registerTickHandler() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (steps == null) return;

            AbstractCraftingMenu handler = getHandler();
            if (handler == null || client.gameMode == null) { steps = null; return; }

            int delay = ClientCraftConfig.delayTicks;
            if (delay <= 0) {
                for (RecipeDisplayId step : steps) {
                    executeStep(client, handler, step);
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
        LOG.info("[CC] Auto-craft completed: {} step(s) in {}ms", totalSteps, elapsedMs);
        RecipeResolver.onAutoCraftComplete();
    }

    private static void executeStep(Minecraft client, AbstractCraftingMenu handler, RecipeDisplayId step) {
        client.gameMode.handlePlaceRecipe(handler.containerId, step, craftAll);
        client.gameMode.handleContainerInput(handler.containerId, 0, 0, ContainerInput.QUICK_MOVE, client.player);
    }

    private static AbstractCraftingMenu getHandler() {
        Minecraft client = Minecraft.getInstance();
        if (client.screen instanceof CraftingScreen s) return s.getMenu();
        if (client.screen instanceof InventoryScreen s) return s.getMenu();
        return null;
    }
}
