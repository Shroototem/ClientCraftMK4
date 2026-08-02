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

import java.util.List;
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

    private ResolveContext(RecipeIndex index, TagIndex tags, RecipeGraph graph, int gridSize) {
        this.index = index;
        this.tags = tags;
        this.graph = graph;
        this.gridSize = gridSize;
    }

    public static ResolveContext of(CraftModel model, int gridSize) {
        return new ResolveContext(model.recipeIndex(), model.tagIndex(), model.graph(), gridSize);
    }

    /** Test-friendly factory over explicit components (no Minecraft required). */
    public static ResolveContext of(RecipeIndex index, TagIndex tags, RecipeGraph graph, int gridSize) {
        return new ResolveContext(index, tags, graph, gridSize);
    }

    public boolean resolve(RecipeDisplayEntry entry, WorkMap work, List<RecipeDisplayId> stepsOut,
                           Set<Item> inProgress, int depth, Item rootOutput) {
        if (depth > Constants.MAX_DEPTH) return false;

        List<SlotDisplay> slots = RecipeDisplays.getSlots(entry.display());
        if (slots == null || slots.isEmpty()) return false;

        Item outputItem = RecipeDisplays.getOutputItem(entry.display(), tags);
        if (outputItem != null && !inProgress.add(outputItem)) return false;
        if (rootOutput == null) rootOutput = outputItem;

        int mark = work.mark();
        int stepsStart = stepsOut != null ? stepsOut.size() : 0;

        boolean success = true;
        for (SlotDisplay slot : slots) {
            if (slot instanceof SlotDisplay.Empty) continue;

            ItemStack resolved = resolveSlot(slot, work);
            if (resolved.isEmpty()) { success = false; break; }
            Item item = resolved.getItem();

            int have = work.get(graph.id(item));
            if (have >= 1) {
                work.consume(graph.id(item), 1);
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
            int subOutput = RecipeDisplays.getOutputCount(sub.display(), tags);
            if (subOutput <= 0) continue;
            if (rootOutput != null && RecipeDisplays.recipeConsumesItem(sub, rootOutput)) continue;

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
            TagKey<Item> tag = d.tag();
            // Scan the working map directly for items matching this tag (deterministic
            // insertion order; the global tag indices miss sub-crafted leftovers).
            GraphFlatData f = graph.flat();
            for (int i = 0; i < work.presentSize(); i++) {
                int pid = work.presentIdAt(i);
                if (work.get(pid) < 1) continue;
                Item it = f.idToItem()[pid];
                if (it.equals(alreadyTried)) continue;
                if (it.builtInRegistryHolder().is(tag)) {
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
                ItemStack r = resolveSlot(sub, work);
                if (r.isEmpty() || r.getItem().equals(alreadyTried)) continue;
                Item it = r.getItem();
                int have = work.get(graph.id(it));
                if (have >= 1) { work.consume(graph.id(it), 1); return true; }
                if (trySubCraft(it, work, stepsOut, inProgress, depth, rootOutput)) return true;
            }
            return false;
        }

        return false;
    }

    /**
     * WorkMap-backed equivalent of {@link RecipeDisplays#resolveSlot}: inventory tag
     * members first, then craftable members present in the working copy, then the first
     * sub-craftable member, then a display fallback.
     */
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
}
