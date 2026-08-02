package com.clientcraftmk4.craft;

import com.clientcraftmk4.config.ClientCraftConfig;
import com.clientcraftmk4.core.InventoryProvider;
import com.clientcraftmk4.core.algorithms.CraftPlanner;
import com.clientcraftmk4.pipeline.ResolvePipeline;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.CraftingScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.world.inventory.AbstractCraftingMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.crafting.display.RecipeDisplayEntry;
import net.minecraft.world.item.crafting.display.RecipeDisplayId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Tick-driven craft executor — ported from MK4 mostly verbatim (plan §7.4).
 * One static step list, executed all-in-one-tick when {@code delayTicks == 0},
 * otherwise one step every {@code delayTicks + 1} ticks, via
 * {@code handlePlaceRecipe} + {@code handleContainerInput(QUICK_MOVE)}.
 *
 * <p>The MK4 {@code batchMode} flag is replaced by {@link #isRunning()}: while a
 * craft is in flight (or its post-craft stabilization poll is pending), the
 * pipeline skips resolves so the user sees the pre-craft counts until the
 * inventory settles — then a refresh is triggered.
 */
public class AutoCrafter {
    private static final Logger LOG = LoggerFactory.getLogger("clientcraftmk4");

    public enum Mode { ONCE, STACK, ALL }

    /** Result from CraftPlanner.plan containing the step list and whether craftAll can be used. */
    public record CraftPlan(List<List<RecipeDisplayId>> cycles, boolean directCraft) {}

    private static List<RecipeDisplayId> steps;
    private static boolean craftAll;
    private static int stepIndex;
    private static int tickCounter;
    private static long startTimeNs;
    private static int totalSteps;

    // Stability polling: after craft completes, wait for inventory to stop changing
    private static boolean pendingBatchClear = false;
    private static long lastSeenGen = -1;
    private static int stableFrames = 0;

    private AutoCrafter() {}

    /** True while a craft runs or its inventory-stabilization poll is pending (MK4's batchMode). */
    public static boolean isRunning() {
        return steps != null || pendingBatchClear;
    }

    public static void execute(RecipeDisplayEntry target, Mode mode) {
        if (steps != null) return;
        if (getHandler() == null) return;

        CraftPlan plan = CraftPlanner.plan(target, mode);
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
        // MK4: if (flat.size() > 10) batchMode = true;
        // MK5: the pipeline's submit() gate checks AutoCrafter.isRunning() instead.
    }

    public static void registerTickHandler() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            // Poll for inventory stability after craft completes
            if (pendingBatchClear) {
                InventoryProvider.poll(); // ensure the snapshot generation is up to date
                long gen = InventoryProvider.generation();
                if (gen == lastSeenGen) {
                    if (++stableFrames >= 2) {
                        pendingBatchClear = false;
                        // Trigger a resolve now that inventory has stabilized
                        ResolvePipeline.refreshNow();
                    }
                } else {
                    lastSeenGen = gen;
                    stableFrames = 0;
                }
            }

            if (steps == null) return;

            AbstractCraftingMenu handler = getHandler();
            if (handler == null || client.gameMode == null) {
                steps = null;
                pendingBatchClear = false;
                return;
            }

            int delay = ClientCraftConfig.delayTicks;
            if (delay <= 0) {
                long tExec = ClientCraftConfig.debugLogging ? System.nanoTime() : 0;
                for (RecipeDisplayId step : steps) {
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
        lastSeenGen = InventoryProvider.generation();
        stableFrames = 0;
    }

    private static void executeStep(Minecraft client, AbstractCraftingMenu handler, RecipeDisplayId step) {
        client.gameMode.handlePlaceRecipe(handler.containerId, step, craftAll);
        client.gameMode.handleContainerInput(handler.containerId, 0, 0, ContainerInput.QUICK_MOVE, client.player);
    }

    private static AbstractCraftingMenu getHandler() {
        Minecraft client = Minecraft.getInstance();
        //? if >=26.2 {
        if (client.gui.screen() instanceof CraftingScreen s) return s.getMenu();
        if (client.gui.screen() instanceof InventoryScreen s) return s.getMenu();
        //?}
        //? if <26.2 {
        /*if (client.screen instanceof CraftingScreen s) return s.getMenu();
        if (client.screen instanceof InventoryScreen s) return s.getMenu();*/
        //?}
        return null;
    }
}
