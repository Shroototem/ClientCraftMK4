package com.clientcraftmk4.core.algorithms;

import com.clientcraftmk4.core.CraftModel;
import com.clientcraftmk4.core.RecipeGraph;
import com.clientcraftmk4.core.WorkMap;
import com.clientcraftmk4.core.resolver.QtyResolveContext;
import com.clientcraftmk4.core.resolver.ResolveContext;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.crafting.display.RecipeDisplayEntry;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Exact craft-count ground truth — a byte-equivalent port of MK4's
 * {@code simulateCraftCount} / {@code tryResolveOnce} / {@code tryResolveQty}.
 * Feasibility is monotonic in k, so the count is found by binary search over a
 * batched feasibility check (O(log 999 · tree)). Only the data structure
 * changed: {@link WorkMap} journal instead of HashMap snapshots (plan §6.6).
 *
 * <p>The worker thread reuses scratch buffers (one {@link WorkMap}, one
 * in-progress set, one context per thread) so repeated binary-search attempts
 * allocate nothing after the first warm-up.
 */
public final class ExactSimulator {
    private static final ThreadLocal<WorkMap> SCRATCH = new ThreadLocal<>();
    private static final ThreadLocal<Set<Item>> IN_PROGRESS = ThreadLocal.withInitial(HashSet::new);
    private static final ThreadLocal<QtyResolveContext> QTY_CTX = new ThreadLocal<>();
    private static final ThreadLocal<ResolveContext> CTX = new ThreadLocal<>();

    private ExactSimulator() {}

    /** The largest k in [0, maxCrafts] such that {@code tryResolveQty(entry, inventory, k)} succeeds. */
    public static int simulateCraftCount(CraftModel model, int gridSize,
                                         RecipeDisplayEntry entry, Map<Item, Integer> inventory,
                                         int maxCrafts) {
        if (maxCrafts <= 0 || inventory == null || inventory.isEmpty()) return 0;
        int lo = 0, hi = maxCrafts;
        while (lo < hi) {
            int mid = lo + (hi - lo + 1) / 2;
            if (tryResolveQty(model, gridSize, entry, inventory, mid)) {
                lo = mid;
            } else {
                hi = mid - 1;
            }
        }
        return lo;
    }

    /** Single resolve() attempt against a fresh copy of the given inventory. */
    public static boolean tryResolveOnce(CraftModel model, int gridSize,
                                         RecipeDisplayEntry entry, Map<Item, Integer> inventory) {
        if (inventory == null || inventory.isEmpty()) return false;
        WorkMap work = scratch(model.graph());
        work.resetTo(inventory, model.graph());
        Set<Item> inProgress = IN_PROGRESS.get();
        inProgress.clear();
        ResolveContext ctx = CTX.get();
        if (ctx == null) {
            ctx = ResolveContext.of(model, gridSize);
            CTX.set(ctx);
        }
        return ctx.resolve(entry, work, null, inProgress, 0, null);
    }

    private static boolean tryResolveQty(CraftModel model, int gridSize,
                                         RecipeDisplayEntry entry, Map<Item, Integer> inventory, int k) {
        if (k <= 0) return true;
        WorkMap work = scratch(model.graph());
        work.resetTo(inventory, model.graph());
        Set<Item> inProgress = IN_PROGRESS.get();
        inProgress.clear();
        QtyResolveContext ctx = QTY_CTX.get();
        if (ctx == null) {
            ctx = QtyResolveContext.of(model, gridSize);
            QTY_CTX.set(ctx);
        }
        return ctx.resolveQty(entry, work, k, null, inProgress, 0, null);
    }

    private static WorkMap scratch(RecipeGraph graph) {
        WorkMap w = SCRATCH.get();
        int needed = graph.flat() != null ? Math.max(16, graph.flat().n()) : 16;
        if (w == null || w.capacity() < needed) {
            w = new WorkMap(needed);
            SCRATCH.set(w);
        }
        return w;
    }
}
