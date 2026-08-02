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
    private static final Logger LOG = LoggerFactory.getLogger("clientcraftmk4");

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
        InventoryProvider.current();
        if (model == null || model.recipeIndex().isEmpty()) return List.of();

        InventorySnapshot snap = InventoryProvider.current();
        long cacheKey = snap.generation() * 7L + gridSize;
        submit(new ResolveRequest(book, gridSize, cacheKey, model.modelGeneration(), snap));

        ResolveResult cur = latest;
        if (cur.collections().isEmpty()) {
            // First open (or after a recipe reload): show a placeholder so the tab is
            // populated instantly while the background resolve runs.
            if (ClientCraftConfig.debugLogging) {
                LOG.info("[CC] Tab: returning placeholder ({} collections)",
                        book.getCollection(net.minecraft.client.gui.screens.recipebook.SearchRecipeBookCategory.CRAFTING).size());
            }
            List<RecipeCollection> placeholder = CollectionAssembler.placeholder(book);
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
        submit(ResolveRequests.fromContext(mc.player.getRecipeBook()));
    }

    private static void compute(ResolveRequest r) {
        try {
            long t0 = System.nanoTime();
            var counts = CountEngine.compute(r.recipeBook(), r.gridSize(), r.snapshot(), r.modelGeneration());
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
                return;
            }
            ResolveResult result = CollectionAssembler.assemble(r, counts);
            if (ClientCraftConfig.debugLogging) {
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
        } catch (Exception e) {
            LOG.error("[CC] Background resolve failed", e);
            // Self-heal: re-run the callback so the tab re-submits instead of staying
            // stuck on the placeholder.
            RenderSync.run(() -> {
                Consumer<ResolveResult> cb = onPublished;
                if (cb != null) cb.accept(latest);
                drainPending(-1);
            });
        } finally {
            computing.set(false);
        }
    }

    /**
     * Submits the queued request, if any, unless it is a duplicate of the request that
     * was just published (cacheKey encodes the inventory generation — monotonic).
     */
    private static void drainPending(long justPublishedKey) {
        ResolveRequest next = pending.getAndSet(null);
        if (next != null && next.cacheKey() > justPublishedKey) {
            WORKER.submit(() -> compute(next));
        }
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
