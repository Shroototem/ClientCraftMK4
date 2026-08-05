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

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Fixed-point reachability over the graph's topological order (port of MK4's
 * computeReachableFromTree). Iterates until no new items are added because the
 * topo order is based on primary recipe edges only.
 */
public final class Reachability {
    private Reachability() {}

    public static Set<Item> compute(RecipeGraph graph, Map<Item, Integer> inventory, int gridSize) {
        if (graph == null) return new HashSet<>(inventory.keySet());
        Set<Item> reachable = new HashSet<>(inventory.keySet());
        boolean changed = true;
        while (changed) {
            changed = false;
            for (Item item : graph.topoOrder()) {
                if (reachable.contains(item)) continue;
                List<CraftedItem> recipes = graph.recipesOf(item);
                for (CraftedItem recipe : recipes) {
                    if (recipe.gridSize() > gridSize) continue;
                    if (allTreeEdgesReachable(recipe, reachable)) {
                        reachable.add(item);
                        changed = true;
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

    /** True if every edge of the flat recipe has at least one option in the reachable set. */
    public static boolean allEdgesReachableFlat(GraphFlatData f, int recIdx, Set<Item> reachable) {
        for (int ei = f.recEdgeStart()[recIdx]; ei < f.recEdgeEnd()[recIdx]; ei++) {
            boolean any = false;
            for (int oi = f.edgeOptStart()[ei]; oi < f.edgeOptEnd()[ei]; oi++) {
                if (reachable.contains(f.optItemObj()[oi])) { any = true; break; }
            }
            if (!any) return false;
        }
        return true;
    }

    /** True if every non-empty slot has at least one option in the reachable set. */
    public static boolean allSlotsReachable(RecipeDisplayEntry entry, Set<Item> reachable, TagIndex tags) {
        List<SlotDisplay> slots = RecipeDisplays.getSlots(entry.display());
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
