package com.clientcraftmk4.craft;

import com.clientcraftmk4.config.ClientCraftConfig;
import com.clientcraftmk4.core.GameContext;
import com.clientcraftmk4.core.InventoryProvider;
import com.clientcraftmk4.core.algorithms.CraftPlanner;
import com.clientcraftmk4.pipeline.ResolvePipeline;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
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
 * One static step list, executed when {@code delayTicks == 0} (capped per tick on a remote
 * server), otherwise one step every {@code delayTicks + 1} ticks, via
 * {@code handlePlaceRecipe} + {@code handleContainerInput(QUICK_MOVE)}.
 *
 * <p>The MK4 {@code batchMode} flag is replaced by {@link #isRunning()}: while a
 * craft is in flight (or its post-craft stabilization poll is pending), the
 * pipeline skips resolves so the user sees the pre-craft counts until the
 * inventory settles — then a refresh is triggered.
 */
public class AutoCrafter {
    private static final Logger LOG = LoggerFactory.getLogger(com.clientcraftmk4.core.Constants.MOD_ID);

    public enum Mode { ONCE, STACK, ALL }

    /** Recipe steps placed by one click; craftAll is passed to handlePlaceRecipe for each. */
    public record Cycle(List<RecipeDisplayId> steps, boolean craftAll) {}

    /** Result from CraftPlanner.plan: the cycles in execution order. */
    public record CraftPlan(List<Cycle> cycles) {}

    private record PlannedStep(RecipeDisplayId id, boolean craftAll) {}

    private static List<PlannedStep> steps;
    private static int stepIndex;
    private static int tickCounter;
    private static long startTimeNs;
    private static int totalSteps;
    private static final int MAX_STEPS_PER_TICK = 12;   // 2 packets per step

    // Stability polling: after craft completes, wait for inventory to stop changing
    private static boolean pendingBatchClear = false;
    private static long lastSeenGen = -1;
    private static int stableFrames = 0;
    private static int pendingBatchTicks = 0;
    /** Give up waiting for a stable inventory after ~5s so a flickering generation
     * can't gate the pipeline via submit()'s isRunning() check forever. */
    private static final int PENDING_BATCH_TIMEOUT_TICKS = 100;

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
        List<PlannedStep> flat = new ArrayList<>();
        for (Cycle cycle : plan.cycles()) {
            for (RecipeDisplayId id : cycle.steps()) flat.add(new PlannedStep(id, cycle.craftAll()));
        }

        steps = flat;
        stepIndex = 0;
        tickCounter = 0;
        totalSteps = flat.size();
        // Once per user click — intentionally unconditional so the completion log is
        // correct even if debug logging is toggled mid-craft.
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
                if (pendingBatchClear && ++pendingBatchTicks >= PENDING_BATCH_TIMEOUT_TICKS) {
                    pendingBatchClear = false;
                    ResolvePipeline.refreshNow();
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
                // Cap the burst on a remote server (packet limiter); singleplayer runs it all at once.
                int from = stepIndex;
                int budget = client.isLocalServer() ? steps.size() : MAX_STEPS_PER_TICK;
                int end = Math.min(from + budget, steps.size());
                while (stepIndex < end) {
                    executeStep(client, handler, steps.get(stepIndex++));
                }
                if (ClientCraftConfig.debugLogging) {
                    long execNs = System.nanoTime() - tExec;
                    int ran = stepIndex - from;
                    LOG.info("[CC] Execute steps: {}ms for {} steps ({}us/step)",
                            execNs / 1_000_000, ran, ran > 0 ? execNs / 1_000 / ran : 0);
                }
                if (stepIndex >= steps.size()) {
                    logCompletion();
                    steps = null;
                }
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
        if (ClientCraftConfig.debugLogging)
            LOG.info("[CC] Auto-craft completed: {} step(s) in {}ms",
                    totalSteps, (System.nanoTime() - startTimeNs) / 1_000_000);
        pendingBatchClear = true;
        lastSeenGen = InventoryProvider.generation();
        stableFrames = 0;
        pendingBatchTicks = 0;
    }

    private static void executeStep(Minecraft client, AbstractCraftingMenu handler, PlannedStep step) {
        client.gameMode.handlePlaceRecipe(handler.containerId, step.id(), step.craftAll());
        client.gameMode.handleContainerInput(handler.containerId, 0, 0, ContainerInput.QUICK_MOVE, client.player);
    }

    private static AbstractCraftingMenu getHandler() {
        return GameContext.craftingMenu();
    }
}
