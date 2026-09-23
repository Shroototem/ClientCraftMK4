package com.clientcraftmk4.core.algorithms;

import com.clientcraftmk4.core.CraftedItem;
import com.clientcraftmk4.core.RecipeGraph.GraphFlatData;
import com.clientcraftmk4.core.IngredientEdge;
import com.clientcraftmk4.core.IngredientOption;
import com.clientcraftmk4.core.RecipeDisplays;
import com.clientcraftmk4.core.RecipeGraph;
import com.clientcraftmk4.core.TagIndex;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.crafting.display.RecipeDisplayEntry;
import net.minecraft.world.item.crafting.display.SlotDisplay;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Fixed-point reachability over the graph's topological order (port of MK4's
 * computeReachableFromTree). Iterates only over the not-yet-reachable items,
 * shrinking the work list as items resolve, because the topo order is based on
 * primary recipe edges only.
 */
public final class Reachability {
    private Reachability() {}

    public static Set<Item> compute(RecipeGraph graph, Map<Item, Integer> inventory, int gridSize) {
        if (graph == null) return new HashSet<>(inventory.keySet());
        Set<Item> reachable = new HashSet<>((int) (inventory.size() / 0.75f) + 16);
        reachable.addAll(inventory.keySet());
        // Worklist over dependents: only parents of newly-reachable items are rechecked.
        // The old fixed-point loop rescanned the whole pending list per iteration with
        // ArrayList.it.remove() memmoves (O(n^2)); this is O(recipes + edges).
        ArrayDeque<Item> queue = new ArrayDeque<>(reachable.size());
        for (Item item : reachable) queue.add(item);
        // Seed: zero-ingredient recipes are trivially reachable even with an empty inventory
        // (the old fixpoint scan caught these via its unconditional first pass over topoOrder).
        for (Item item : graph.topoOrder()) {
            if (reachable.contains(item)) continue;
            for (CraftedItem recipe : graph.recipesOf(item)) {
                if (recipe.gridSize() > gridSize) continue;
                if (recipe.ingredients().isEmpty()) {
                    reachable.add(item);
                    queue.add(item);
                    break;
                }
            }
        }
        while (!queue.isEmpty()) {
            Item newly = queue.poll();
            for (Item parent : graph.dependentsOf(newly)) {
                if (reachable.contains(parent)) continue;
                for (CraftedItem recipe : graph.recipesOf(parent)) {
                    if (recipe.gridSize() > gridSize) continue;
                    if (allTreeEdgesReachable(recipe, reachable)) {
                        reachable.add(parent);
                        queue.add(parent);
                        break;
                    }
                }
            }
        }
        return reachable;
    }

    private static boolean allTreeEdgesReachable(CraftedItem recipe, Set<Item> reachable) {
        for (IngredientEdge edge : recipe.ingredients()) {
            boolean any = false;
            for (IngredientOption option : edge.options()) {
                if (reachable.contains(option.item())) { any = true; break; }
            }
            if (!any) return false;
        }
        return true;
    }

    /**
     * True if every edge of the flat recipe has at least one reachable option. Reachability
     * is a flat-id bit test — no hashing. {@code overflow} covers non-graph options (see
     * CountEngine: practically none, but bit-identical when they occur).
     */
    public static boolean allEdgesReachableFlat(GraphFlatData f, int recIdx, boolean[] reachable,
                                                Set<Item> overflow) {
        for (int ei = f.recEdgeStart()[recIdx]; ei < f.recEdgeEnd()[recIdx]; ei++) {
            boolean any = false;
            for (int oi = f.edgeOptStart()[ei]; oi < f.edgeOptEnd()[ei]; oi++) {
                int oid = f.optItemId()[oi];
                if (oid >= 0
                        ? (oid < reachable.length && reachable[oid])
                        : overflow.contains(f.optItemObj()[oi])) {
                    any = true;
                    break;
                }
            }
            if (!any) return false;
        }
        return true;
    }

    /** True if every non-empty slot has at least one option in the reachable set. */
    public static boolean allSlotsReachable(RecipeDisplayEntry entry, Set<Item> reachable, TagIndex tags) {
        List<SlotDisplay> slots = RecipeDisplays.getSlots(entry);
        if (slots == null) return false;
        for (SlotDisplay slot : slots) {
            if (slot instanceof SlotDisplay.Empty) continue;
            if (!slotReachable(slot, reachable, tags)) return false;
        }
        return true;
    }

    private static boolean slotReachable(SlotDisplay slot, Set<Item> reachable, TagIndex tags) {
        if (slot instanceof SlotDisplay.ItemSlotDisplay d) return reachable.contains(d.item().value());
        if (slot instanceof SlotDisplay.ItemStackSlotDisplay d) return reachable.contains(d.stack().item().value());
        if (slot instanceof SlotDisplay.TagSlotDisplay d) {
            List<Item> members = tags.members(RecipeDisplays.getSlotTag(d));
            if (members != null) {
                for (Item m : members) {
                    if (reachable.contains(m)) return true;
                }
            }
            return false;
        }
        if (slot instanceof SlotDisplay.Composite d) {
            for (SlotDisplay sub : d.contents()) {
                if (slotReachable(sub, reachable, tags)) return true;
            }
            return false;
        }
        if (slot instanceof SlotDisplay.WithRemainder d) return slotReachable(d.input(), reachable, tags);
        return false;
    }
}
