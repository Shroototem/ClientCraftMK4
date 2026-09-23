package com.clientcraftmk4.pipeline;

import com.clientcraftmk4.config.ClientCraftConfig;
import com.clientcraftmk4.core.CountEngine;
import com.clientcraftmk4.core.CraftModel;
import com.clientcraftmk4.core.GameContext;
import com.clientcraftmk4.core.InventoryProvider;
import com.clientcraftmk4.core.InventorySnapshot;
import com.clientcraftmk4.craft.AutoCrafter;
import net.minecraft.client.ClientRecipeBook;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.recipebook.RecipeCollection;
import net.minecraft.client.gui.screens.recipebook.SearchRecipeBookCategory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Owns the resolve worker thread, dedupes requests, and publishes results
 * atomically (plan §9.1). Fixes MK4's request-dropping bug: exactly one queued
 * request survives via {@code pending} instead of a DiscardOldestPolicy queue
 * that silently dropped requests arriving mid-compute.
 *
 * <p>With {@code debugLogging} enabled, every submit / compute / publish /
 * discard decision is logged as {@code [CC] Resolve: ...} so in-game
 * behaviour can be diagnosed from the log.
 */
public final class ResolvePipeline {
    private static final Logger LOG = LoggerFactory.getLogger(com.clientcraftmk4.core.Constants.MOD_ID);

    private static final AtomicReference<ResolveRequest> pending = new AtomicReference<>();
    private static volatile ResolveResult latest = ResolveResult.EMPTY;
    private static final AtomicBoolean computing = new AtomicBoolean();
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "ClientCraft-Resolver");
        t.setDaemon(true);
        return t;
    });
    private static volatile Consumer<ResolveResult> onPublished = r -> {};

    private ResolvePipeline() {}

    public static ResolveResult current() {
        return latest;
    }

    public static void setOnPublished(Consumer<ResolveResult> callback) {
        onPublished = callback;
    }

    /** Entry point used by the {@code ClientRecipeBook.getCollection} mixin. */
    public static List<RecipeCollection> collectionsForTab(ClientRecipeBook book) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return List.of();

        int gridSize = GameContext.gridSize();
        CraftModel model = CraftModel.current();
        if (model == null || model.recipeIndex().isEmpty()) return List.of();

        InventorySnapshot snap = InventoryProvider.current();
        List<RecipeCollection> allCrafting = book.getCollection(SearchRecipeBookCategory.CRAFTING);
        submit(new ResolveRequest(allCrafting, gridSize,
                ResolveRequest.cacheKey(snap.generation(), gridSize), model.modelGeneration(), snap,
                latest, InventoryProvider.lastChangedTags()));

        ResolveResult cur = latest;
        if (cur.collections().isEmpty()) {
            // First open (or after a recipe reload): show a placeholder so the tab is
            // populated instantly while the background resolve runs.
            if (ClientCraftConfig.debugLogging) {
                LOG.info("[CC] Tab: returning placeholder ({} collections)", allCrafting.size());
            }
            List<RecipeCollection> placeholder = CollectionAssembler.placeholder(allCrafting);
            latest = cur.withCollections(placeholder);
            return placeholder;
        }
        return cur.collections();
    }

    public static void submit(ResolveRequest r) {
        if (AutoCrafter.isRunning()) return;    // MK4's batchMode gate
        ResolveResult cur = latest;
        if (cur.cacheKey() == r.cacheKey() && !cur.collections().isEmpty()) return;
        if (!computing.compareAndSet(false, true)) {
            // Already computing: keep at most one queued request, and never queue a
            // duplicate of the in-flight request (the worker will publish its result).
            ResolveRequest p = pending.get();
            if (p == null || p.cacheKey() != r.cacheKey()) {
                pending.set(r);
            }
            return;
        }
        WORKER.submit(() -> compute(r));
    }

    /** Forces a fresh resolve from the current game state (AutoCrafter's post-craft refresh). */
    public static void refreshNow() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        submit(ResolveRequests.fromContext());
    }

    private static void compute(ResolveRequest r) {
        // Tracks whether the render-thread drain was scheduled: the computing flag is
        // cleared inside drainPending (render thread), not in the finally below. Clearing
        // it here on the worker opens a window where a new submit sneaks past the CAS
        // and queues a duplicate compute before the pending drain runs.
        boolean posted = false;
        try {
            boolean debug = ClientCraftConfig.debugLogging;
            long t0 = debug ? System.nanoTime() : 0;
            CountEngine.Previous prev = null;
            ResolveResult p = r.previous();
            if (p != null && p.snapshot() != null) {
                prev = new CountEngine.Previous(p.counts(), p.containerCraftable(), p.snapshot(),
                        ResolveRequest.gridSizeOf(p.cacheKey()));
            }
            var counts = CountEngine.compute(r.allCrafting(), r.gridSize(), r.snapshot(), r.modelGeneration(),
                    prev, r.changedTags());
            if (counts.isEmpty()) {
                // Stale (model advanced / no world) — the newer queued request will follow.
                if (ClientCraftConfig.debugLogging) {
                    LOG.info("[CC] Resolve: result discarded (stale model or empty)");
                }
                // MK4's retry: re-run the callback so the tab re-submits against the
                // fresh model even if no explicit refresh arrives.
                RenderSync.run(() -> {
                    Consumer<ResolveResult> cb = onPublished;
                    if (cb != null) cb.accept(latest);
                    drainPending(-1);
                });
                posted = true;
                return;
            }
            ResolveResult result = CollectionAssembler.assemble(r, counts);
            if (debug) {
                LOG.info("[CC] Resolve: computed {} collections, {} counted entries, {} container entries ({}ms)",
                        result.collections().size(), result.counts().size(),
                        result.containerCraftable().size(), (System.nanoTime() - t0) / 1_000_000);
            }
            // Publish + drain on the render thread so a queued duplicate of the request
            // we just published is dropped instead of recomputed.
            RenderSync.run(() -> {
                if (CraftModel.generation() != r.modelGeneration()) {
                    // Recipes changed mid-compute: don't publish stale data; re-run the
                    // callback so the tab re-submits against the fresh model (MK4's retry).
                    if (ClientCraftConfig.debugLogging) {
                        LOG.info("[CC] Resolve: publish discarded (model generation advanced {} -> {})",
                                r.modelGeneration(), CraftModel.generation());
                    }
                    Consumer<ResolveResult> cb = onPublished;
                    if (cb != null) cb.accept(latest);
                    drainPending(-1);
                    return;
                }
                latest = result;
                Consumer<ResolveResult> cb = onPublished;
                if (cb != null) cb.accept(result);
                drainPending(r.cacheKey());
            });
            posted = true;
        } catch (Exception e) {
            LOG.error("[CC] Background resolve failed", e);
            // Self-heal: re-run the callback so the tab re-submits instead of staying
            // stuck on the placeholder.
            RenderSync.run(() -> {
                Consumer<ResolveResult> cb = onPublished;
                if (cb != null) cb.accept(latest);
                drainPending(-1);
            });
            posted = true;
        } finally {
            // Fallback for when the render-thread post itself threw (e.g. client
            // shutting down): without this the pipeline would wedge with computing
            // stuck true. The normal path clears the flag in drainPending instead.
            if (!posted) computing.set(false);
        }
    }

    /**
     * Submits the queued request, if any, unless it is stale relative to the
     * request that was just published: an older inventory generation, or the
     * exact same (generation, grid size) pair. A same-generation grid-size
     * change is a genuine environment change and must re-run — dropping it
     * would leave, say, 3×3 results rendered in the 2×2 grid.
     */
    private static void drainPending(long justPublishedKey) {
        // Render thread owns this flag now (all submit() callers are render-thread too,
        // so no interleaving is possible here — unlike the old worker-side clear).
        computing.set(false);
        ResolveRequest next = pending.getAndSet(null);
        if (next != null && !shouldDropQueued(next.cacheKey(), justPublishedKey)) {
            submit(next);
        }
    }

    /** Stops the worker thread (client shutdown). In-flight work is discarded. */
    public static void shutdown() {
        pending.set(null);
        WORKER.shutdownNow();
    }

    private static final AtomicBoolean warmupPending = new AtomicBoolean();

    /**
     * Marks that recipe data was (re)synced and the caches are cold. A client tick consumes
     * the flag and submits a warmup resolve once the world is ready — the worker then pays
     * the cold cost (graph build, cache fills, JIT) while the tab still shows its placeholder,
     * instead of on first tab open. Fully deduped by the normal submit path.
     */
    public static void requestWarmup() {
        warmupPending.set(true);
    }

    public static void clearWarmup() {
        warmupPending.set(false);
    }

    /** Runs the pending warmup resolve, if any and if the world is ready. Tick-thread only. */
    public static void warmupIfRequested() {
        if (!warmupPending.getAndSet(false)) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        try {
            submit(ResolveRequests.fromContext());
        } catch (Exception e) {
            LOG.error("[CC] Warmup resolve failed", e);
        }
    }

    /** Pure drop-decision for {@link #drainPending} (unit-tested without Minecraft state). */
    static boolean shouldDropQueued(long queuedKey, long justPublishedKey) {
        long queuedGen = ResolveRequest.generationOf(queuedKey);
        long pubGen = ResolveRequest.generationOf(justPublishedKey);
        return queuedGen < pubGen
                || (queuedGen == pubGen
                    && ResolveRequest.gridSizeOf(queuedKey) == ResolveRequest.gridSizeOf(justPublishedKey));
    }

    /** Clears everything (world leave / config change). */
    public static void reset() {
        computing.set(false);
        pending.set(null);
        latest = ResolveResult.EMPTY;
        onPublished = r -> {};
    }

    /** Clears the published result only (recipe reload). */
    public static void resetLatest() {
        latest = ResolveResult.EMPTY;
    }
}
