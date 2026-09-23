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
 * Single-craft feasibility search — the byte-equivalent port of MK4's
 * {@code resolve}/{@code trySubCraft}/{@code tryTagFallback}, running on the
 * journal-rollback {@link WorkMap} instead of HashMap copies (plan §6.4).
 *
 * <p>Subtle behaviours preserved (plan §1.4): MAX_DEPTH 10; inProgress
 * re-entry guard; root-output cycle guard; tag fallback at depth ≤ 1 only;
 * sub-craft surplus retention ({@code produce(outputCount - 1)}); sub-recipe
 * order = recipe-book order; tag fallback scan is deterministic (insertion
 * order instead of MK4's undefined HashMap order — plan §7.3).
 */
public final class ResolveContext {
    private final RecipeIndex index;
    private final TagIndex tags;
    private final RecipeGraph graph;
    private final int gridSize;
    private final long modelGeneration;

    private ResolveContext(RecipeIndex index, TagIndex tags, RecipeGraph graph, int gridSize, long modelGeneration) {
        this.index = index;
        this.tags = tags;
        this.graph = graph;
        this.gridSize = gridSize;
        this.modelGeneration = modelGeneration;
    }

    public static ResolveContext of(CraftModel model, int gridSize) {
        return new ResolveContext(model.recipeIndex(), model.tagIndex(), model.graph(), gridSize, model.modelGeneration());
    }

    /** Test-friendly factory over explicit components (no Minecraft required). */
    public static ResolveContext of(RecipeIndex index, TagIndex tags, RecipeGraph graph, int gridSize) {
        return new ResolveContext(index, tags, graph, gridSize, -1);
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
     * Memoised recipe outputs. The live inventory map is immutable once published (the
     * provider builds fresh maps per read and never mutates them), so map *identity* is an
     * exact change detector: same object ⟹ same content ⟹ cached outputs valid. A changed
     * live map clears the cache, reproducing uncached behavior exactly.
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

    public boolean resolve(RecipeDisplayEntry entry, WorkMap work, List<RecipeDisplayId> stepsOut,
                           Set<Item> inProgress, int depth, Item rootOutput) {
        if (depth > Constants.MAX_DEPTH) return false;

        List<SlotDisplay> slots = RecipeDisplays.getSlots(entry);
        if (slots == null || slots.isEmpty()) return false;

        Item outputItem = outItem(entry);
        if (outputItem != null && !inProgress.add(outputItem)) return false;
        if (rootOutput == null) rootOutput = outputItem;

        int mark = work.mark();
        int stepsStart = stepsOut != null ? stepsOut.size() : 0;

        boolean success = true;
        for (SlotDisplay slot : slots) {
            if (slot instanceof SlotDisplay.Empty) continue;

            // Item fast path: no ItemStack alloc per slot (see RecipeDisplays.resolveSlotItem).
            Item item = RecipeDisplays.resolveSlotItem(slot, work, graph, tags);
            if (item == null) { success = false; break; }

            int id = graph.id(item);
            int have = work.get(id);
            if (have >= 1) {
                work.consume(id, 1);
                continue;
            }

            if (!trySubCraft(item, work, stepsOut, inProgress, depth, rootOutput)
                    && !(depth <= 1 && tryTagFallback(slot, item, work, stepsOut, inProgress, depth, rootOutput))) {
                success = false;
                break;
            }
        }

        if (!success) {
            work.rollbackTo(mark);
            if (stepsOut != null) while (stepsOut.size() > stepsStart) stepsOut.removeLast();
            if (outputItem != null) inProgress.remove(outputItem);
            return false;
        }

        if (stepsOut != null) stepsOut.add(entry.id());
        if (outputItem != null) inProgress.remove(outputItem);
        return true;
    }

    private boolean trySubCraft(Item item, WorkMap work, List<RecipeDisplayId> stepsOut,
                                Set<Item> inProgress, int depth, Item rootOutput) {
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

            if (resolve(sub, work, stepsOut, inProgress, depth + 1, rootOutput)) {
                // Surplus from a N>1 sub-recipe stays in the working map for later edges.
                work.produce(graph.id(item), subOutput - 1);
                return true;
            }
        }
        return false;
    }

    private boolean tryTagFallback(SlotDisplay slot, Item alreadyTried, WorkMap work,
                                   List<RecipeDisplayId> stepsOut, Set<Item> inProgress,
                                   int depth, Item rootOutput) {
        if (slot instanceof SlotDisplay.WithRemainder d)
            return tryTagFallback(d.input(), alreadyTried, work, stepsOut, inProgress, depth, rootOutput);

        if (slot instanceof SlotDisplay.TagSlotDisplay d) {
            TagKey<Item> tag = RecipeDisplays.getSlotTag(d);
            // Scan the working map directly for items matching this tag (deterministic
            // id order; the global tag indices miss sub-crafted leftovers). Membership
            // is a BitSet read — the old registry holder.is(tag) walk per present item
            // was the hottest registry call in modpacks.
            GraphFlatData f = graph.flat();
            BitSet bits = tags.flatTagBits(tag);
            for (int i = 0; i < work.presentSize(); i++) {
                int pid = work.presentIdAt(i);
                if (work.get(pid) < 1) continue;
                Item it = f.idToItem()[pid];
                if (it.equals(alreadyTried)) continue;
                boolean matches = bits != null ? bits.get(pid) : it.builtInRegistryHolder().is(tag);
                if (matches) {
                    work.consume(pid, 1);
                    return true;
                }
            }
            List<Item> craftable = tags.craftableMembers(tag);
            if (craftable != null) {
                for (Item alt : craftable) {
                    if (alt.equals(alreadyTried)) continue;
                    if (trySubCraft(alt, work, stepsOut, inProgress, depth, rootOutput)) return true;
                }
            }
            return false;
        }

        if (slot instanceof SlotDisplay.Composite d) {
            for (SlotDisplay sub : d.contents()) {
                Item it = RecipeDisplays.resolveSlotItem(sub, work, graph, tags);
                if (it == null || it.equals(alreadyTried)) continue;
                int have = work.get(graph.id(it));
                if (have >= 1) { work.consume(graph.id(it), 1); return true; }
                if (trySubCraft(it, work, stepsOut, inProgress, depth, rootOutput)) return true;
            }
            return false;
        }

        return false;
    }
}
