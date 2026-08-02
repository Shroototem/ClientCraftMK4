package com.clientcraftmk4.core.resolver;

import com.clientcraftmk4.core.Constants;
import com.clientcraftmk4.core.CraftModel;
import com.clientcraftmk4.core.RecipeGraph.GraphFlatData;
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

import java.util.List;
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

    private QtyResolveContext(RecipeIndex index, TagIndex tags, RecipeGraph graph, int gridSize) {
        this.index = index;
        this.tags = tags;
        this.graph = graph;
        this.gridSize = gridSize;
    }

    public static QtyResolveContext of(CraftModel model, int gridSize) {
        return new QtyResolveContext(model.recipeIndex(), model.tagIndex(), model.graph(), gridSize);
    }

    /** Test-friendly factory over explicit components (no Minecraft required). */
    public static QtyResolveContext of(RecipeIndex index, TagIndex tags, RecipeGraph graph, int gridSize) {
        return new QtyResolveContext(index, tags, graph, gridSize);
    }

    public boolean resolveQty(RecipeDisplayEntry entry, WorkMap work, int qty,
                              List<RecipeDisplayId> stepsOut, Set<Item> inProgress,
                              int depth, Item rootOutput) {
        if (depth > Constants.MAX_DEPTH || qty <= 0) return false;

        List<SlotDisplay> slots = RecipeDisplays.getSlots(entry.display());
        if (slots == null || slots.isEmpty()) return false;

        Item outputItem = RecipeDisplays.getOutputItem(entry.display(), tags);
        if (outputItem != null && !inProgress.add(outputItem)) return false;
        if (rootOutput == null) rootOutput = outputItem;

        int mark = work.mark();
        int stepsStart = stepsOut != null ? stepsOut.size() : 0;

        for (SlotDisplay slot : slots) {
            if (slot instanceof SlotDisplay.Empty) continue;

            ItemStack resolved = resolveSlot(slot, work);
            if (resolved.isEmpty()) {
                work.rollbackTo(mark);
                rollbackSteps(stepsOut, stepsStart);
                if (outputItem != null) inProgress.remove(outputItem);
                return false;
            }
            Item item = resolved.getItem();
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
            int subOutput = RecipeDisplays.getOutputCount(sub.display(), tags);
            if (subOutput <= 0) continue;
            if (rootOutput != null && RecipeDisplays.recipeConsumesItem(sub, rootOutput)) continue;

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
            TagKey<Item> tag = d.tag();
            int need = deficit;
            GraphFlatData f = graph.flat();
            for (int i = 0; i < work.presentSize() && need > 0; i++) {
                int pid = work.presentIdAt(i);
                if (work.get(pid) < 1) continue;
                Item it = f.idToItem()[pid];
                if (it.equals(alreadyTried)) continue;
                if (it.builtInRegistryHolder().is(tag)) {
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
                ItemStack r = resolveSlot(sub, work);
                if (r.isEmpty() || r.getItem().equals(alreadyTried)) continue;
                Item it = r.getItem();
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

    private ItemStack resolveSlot(SlotDisplay slot, WorkMap work) {
        if (slot instanceof SlotDisplay.Empty) return ItemStack.EMPTY;
        if (slot instanceof SlotDisplay.ItemSlotDisplay d) return new ItemStack(d.item());
        if (slot instanceof SlotDisplay.ItemStackSlotDisplay d) {
            return new ItemStack(d.stack().item().value(), d.stack().count());
        }
        if (slot instanceof SlotDisplay.TagSlotDisplay d) {
            TagKey<Item> tag = d.tag();
            Set<Item> matches = tags.inventoryTagMembers(tag);
            if (matches != null) {
                for (Item item : matches) {
                    if (work.get(graph.id(item)) > 0) return new ItemStack(item);
                }
            }
            List<Item> craft = tags.craftableMembers(tag);
            if (craft != null) {
                for (Item item : craft) {
                    if (work.get(graph.id(item)) > 0) return new ItemStack(item);
                }
            }
            if (craft != null && !craft.isEmpty()) return new ItemStack(craft.getFirst());
            return tags.anyTagMember(tag);
        }
        if (slot instanceof SlotDisplay.WithRemainder d) return resolveSlot(d.input(), work);
        if (slot instanceof SlotDisplay.Composite d) {
            ItemStack fallback = ItemStack.EMPTY;
            for (SlotDisplay sub : d.contents()) {
                ItemStack r = resolveSlot(sub, work);
                if (!r.isEmpty()) {
                    if (work.get(graph.id(r.getItem())) > 0) return r;
                    if (fallback.isEmpty()) fallback = r;
                }
            }
            return fallback;
        }
        return ItemStack.EMPTY;
    }

    private static void rollbackSteps(List<RecipeDisplayId> stepsOut, int stepsStart) {
        if (stepsOut != null) while (stepsOut.size() > stepsStart) stepsOut.removeLast();
    }
}
