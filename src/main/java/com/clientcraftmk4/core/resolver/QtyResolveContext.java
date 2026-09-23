package com.clientcraftmk4.core.resolver;

import com.clientcraftmk4.core.Constants;
import com.clientcraftmk4.core.CraftModel;
import com.clientcraftmk4.core.RecipeGraph.GraphFlatData;
import com.clientcraftmk4.core.InventoryProvider;
import com.clientcraftmk4.core.RecipeDisplays;
import com.clientcraftmk4.core.RecipeGraph;
import com.clientcraftmk4.core.RecipeIndex;
import com.clientcraftmk4.core.TagIndex;
import com.clientcraftmk4.core.WorkMap;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.display.RecipeDisplayEntry;
import net.minecraft.world.item.crafting.display.RecipeDisplayId;
import net.minecraft.world.item.crafting.display.SlotDisplay;

import java.util.BitSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Batched feasibility search — the byte-equivalent port of MK4's
 * {@code resolveQty}/{@code trySubCraftQty}/{@code tryTagFallbackQty}, running
 * on the journal-rollback {@link WorkMap}. Satisfies {@code qty} crafts of an
 * entry in a single pass: each slot's per-craft need is multiplied by qty,
 * deficits are sub-crafted in bulk ({@code ceil(deficit/subOutput)} crafts,
 * leftovers retained), with the same cycle guards and depth-≤-1 tag fallback as
 * {@link ResolveContext}.
 */
public final class QtyResolveContext {
    private final RecipeIndex index;
    private final TagIndex tags;
    private final RecipeGraph graph;
    private final int gridSize;
    private final long modelGeneration;

    private QtyResolveContext(RecipeIndex index, TagIndex tags, RecipeGraph graph, int gridSize, long modelGeneration) {
        this.index = index;
        this.tags = tags;
        this.graph = graph;
        this.gridSize = gridSize;
        this.modelGeneration = modelGeneration;
    }

    public static QtyResolveContext of(CraftModel model, int gridSize) {
        return new QtyResolveContext(model.recipeIndex(), model.tagIndex(), model.graph(), gridSize, model.modelGeneration());
    }

    /** Test-friendly factory over explicit components (no Minecraft required). */
    public static QtyResolveContext of(RecipeIndex index, TagIndex tags, RecipeGraph graph, int gridSize) {
        return new QtyResolveContext(index, tags, graph, gridSize, -1);
    }

    /** Model generation this context was built from (stale-cache detection). */
    public long modelGeneration() {
        return modelGeneration;
    }

    /** Grid size this context was built for (stale-cache detection). */
    public int gridSize() {
        return gridSize;
    }

    /**
     * Memoised recipe outputs, keyed by live-inventory-map identity (same scheme as
     * {@link ResolveContext}: the published map is never mutated, so identity is an exact
     * change detector and the cache reproduces uncached behavior exactly).
     */
    private Map<Item, Integer> outBasis = null;
    private final Map<RecipeDisplayId, ResolvedOutput> outCache = new HashMap<>();

    private record ResolvedOutput(Item item, int count) {}

    /** Recipe output item under the live inventory (null = unresolvable), memoised. */
    public Item outItem(RecipeDisplayEntry entry) {
        return outFor(entry).item();
    }

    /** Recipe output count under the live inventory (0 = unresolvable), memoised. */
    public int outCount(RecipeDisplayEntry entry) {
        return outFor(entry).count();
    }

    private ResolvedOutput outFor(RecipeDisplayEntry entry) {
        Map<Item, Integer> live = InventoryProvider.latest().inventory();
        if (live != outBasis) {
            outCache.clear();
            outBasis = live;
        }
        ResolvedOutput o = outCache.get(entry.id());
        if (o == null) {
            ItemStack s = RecipeDisplays.resolveSlot(entry.display().result(), live, tags, false);
            o = s.isEmpty() ? new ResolvedOutput(null, 0) : new ResolvedOutput(s.getItem(), s.getCount());
            outCache.put(entry.id(), o);
        }
        return o;
    }

    public boolean resolveQty(RecipeDisplayEntry entry, WorkMap work, int qty,
                              List<RecipeDisplayId> stepsOut, Set<Item> inProgress,
                              int depth, Item rootOutput) {
        if (depth > Constants.MAX_DEPTH || qty <= 0) return false;

        List<SlotDisplay> slots = RecipeDisplays.getSlots(entry);
        if (slots == null || slots.isEmpty()) return false;

        Item outputItem = outItem(entry);
        if (outputItem != null && !inProgress.add(outputItem)) return false;
        if (rootOutput == null) rootOutput = outputItem;

        int mark = work.mark();
        int stepsStart = stepsOut != null ? stepsOut.size() : 0;

        for (SlotDisplay slot : slots) {
            if (slot instanceof SlotDisplay.Empty) continue;

            // Item fast path: no ItemStack alloc per slot (see RecipeDisplays.resolveSlotItem).
            Item item = RecipeDisplays.resolveSlotItem(slot, work, graph, tags);
            if (item == null) {
                work.rollbackTo(mark);
                rollbackSteps(stepsOut, stepsStart);
                if (outputItem != null) inProgress.remove(outputItem);
                return false;
            }
            int id = graph.id(item);
            int need = qty;

            int have = work.get(id);
            int take = Math.min(have, need);
            if (take > 0) work.consume(id, take);
            int deficit = need - take;

            if (deficit > 0) {
                if (!trySubCraftQty(item, deficit, work, stepsOut, inProgress, depth, rootOutput)
                        && !(depth <= 1 && tryTagFallbackQty(slot, item, deficit, work, stepsOut, inProgress, depth, rootOutput))) {
                    work.rollbackTo(mark);
                    rollbackSteps(stepsOut, stepsStart);
                    if (outputItem != null) inProgress.remove(outputItem);
                    return false;
                }
            }
        }

        if (stepsOut != null) stepsOut.add(entry.id());
        if (outputItem != null) inProgress.remove(outputItem);
        return true;
    }

    private boolean trySubCraftQty(Item item, int deficit, WorkMap work,
                                   List<RecipeDisplayId> stepsOut, Set<Item> inProgress,
                                   int depth, Item rootOutput) {
        List<RecipeDisplayEntry> subs = index.get(item);
        if (subs == null) return false;
        for (int i = 0, len = subs.size(); i < len; i++) {
            RecipeDisplayEntry sub = subs.get(i);
            if (!RecipeDisplays.fitsInGrid(sub.display(), gridSize)) continue;
            int subOutput = outCount(sub);
            if (subOutput <= 0) continue;
            // Precomputed required set (pure slot-tree function, see RecipeIndex):
            // one contains instead of a slot walk per candidate per deficit per attempt.
            if (rootOutput != null && index.requiredItems(sub).contains(rootOutput)) continue;

            int crafts = (deficit + subOutput - 1) / subOutput;
            int mark = work.mark();    // MK4: full-map copy per alternative — now O(1)
            if (resolveQty(sub, work, crafts, stepsOut, inProgress, depth + 1, rootOutput)) {
                int produced = crafts * subOutput;
                work.produce(graph.id(item), produced - deficit); // keep leftovers
                return true;
            }
            work.rollbackTo(mark);
        }
        return false;
    }

    private boolean tryTagFallbackQty(SlotDisplay slot, Item alreadyTried, int deficit, WorkMap work,
                                      List<RecipeDisplayId> stepsOut, Set<Item> inProgress,
                                      int depth, Item rootOutput) {
        if (slot instanceof SlotDisplay.WithRemainder d)
            return tryTagFallbackQty(d.input(), alreadyTried, deficit, work, stepsOut, inProgress, depth, rootOutput);

        if (slot instanceof SlotDisplay.TagSlotDisplay d) {
            TagKey<Item> tag = RecipeDisplays.getSlotTag(d);
            int need = deficit;
            GraphFlatData f = graph.flat();
            BitSet bits = tags.flatTagBits(tag);
            for (int i = 0; i < work.presentSize() && need > 0; i++) {
                int pid = work.presentIdAt(i);
                if (work.get(pid) < 1) continue;
                Item it = f.idToItem()[pid];
                if (it.equals(alreadyTried)) continue;
                boolean matches = bits != null ? bits.get(pid) : it.builtInRegistryHolder().is(tag);
                if (matches) {
                    int take = Math.min(need, work.get(pid));
                    work.consume(pid, take);
                    need -= take;
                }
            }
            if (need <= 0) return true;
            List<Item> craftable = tags.craftableMembers(tag);
            if (craftable != null) {
                for (Item alt : craftable) {
                    if (alt.equals(alreadyTried)) continue;
                    if (trySubCraftQty(alt, need, work, stepsOut, inProgress, depth, rootOutput)) return true;
                }
            }
            return false;
        }

        if (slot instanceof SlotDisplay.Composite d) {
            int need = deficit;
            for (SlotDisplay sub : d.contents()) {
                if (need <= 0) break;
                Item it = RecipeDisplays.resolveSlotItem(sub, work, graph, tags);
                if (it == null || it.equals(alreadyTried)) continue;
                int id = graph.id(it);
                int have = work.get(id);
                int take = Math.min(need, have);
                if (take > 0) { work.consume(id, take); need -= take; }
                if (need > 0 && trySubCraftQty(it, need, work, stepsOut, inProgress, depth, rootOutput)) need = 0;
            }
            return need <= 0;
        }
        return false;
    }

    private static void rollbackSteps(List<RecipeDisplayId> stepsOut, int stepsStart) {
        if (stepsOut != null) while (stepsOut.size() > stepsStart) stepsOut.removeLast();
    }
}
