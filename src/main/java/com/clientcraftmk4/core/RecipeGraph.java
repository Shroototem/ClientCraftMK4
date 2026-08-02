package com.clientcraftmk4.core;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.crafting.display.RecipeDisplayId;

import java.util.*;

/**
 * The recipe DAG: every item that can be crafted, its recipes, and the
 * dependency relationships, packed into cache-friendly flat arrays
 * (plan §10). The object graph ({@link RecipeNode}s) is only used during
 * building and by the reachability pass; the hot counting loop works on
 * {@link GraphFlatData} exclusively.
 */
public record RecipeGraph(
        Map<Item, RecipeNode> primaryNodes,
        Map<Item, List<CraftedItem>> allRecipes,
        Map<Item, Set<Item>> dependents,
        List<Item> topologicalOrder,
        Map<Item, Set<Item>> reverseDependencyTargets,
        GraphFlatData flat
) {
    public RecipeNode node(Item item) {
        return primaryNodes.get(item);
    }

    public List<CraftedItem> recipesOf(Item item) {
        return allRecipes.getOrDefault(item, List.of());
    }

    public Set<Item> dependentsOf(Item item) {
        return dependents.getOrDefault(item, Set.of());
    }

    public List<Item> topoOrder() {
        return topologicalOrder;
    }

    public Set<Item> reverseTargets(Item item) {
        return reverseDependencyTargets.getOrDefault(item, Set.of());
    }

    public int id(Item item) {
        return flat.idMap().getOrDefault(item, -1);
    }

    /**
     * All per-recipe / per-edge / per-option data as primitive arrays.
     * {@code recCycleSuspect} and {@code recSharingSuspect} are the DP-exactness
     * gates, computed once at graph build time instead of per resolve
     * (plan §6.3); {@code recReverseTargets} pre-computes the per-recipe
     * reverse-dependency target set.
     */
    public record GraphFlatData(
            int n,
            Item[] idToItem,
            IdentityHashMap<Item, Integer> idMap,
            boolean[] isBaseNode,
            int[] primaryRecIdx,
            int[] itemRecStart, int[] itemRecEnd, int[] itemRecFlat,
            int totalRecipes,
            int[] recOutId, int[] recOutCount, int[] recGridSize,
            RecipeDisplayId[] recDispId,
            Map<RecipeDisplayId, Integer> dispIdToRecIdx,
            boolean[] recSelfConsuming,
            boolean[] recCycleSuspect,
            boolean[] recSharingSuspect,
            Set<Item>[] recReverseTargets,
            int[] recEdgeStart, int[] recEdgeEnd,
            int totalEdges,
            int[] edgeCnt, int[] edgeOptStart, int[] edgeOptEnd,
            int totalOpts,
            int[] optItemId, Item[] optItemObj
    ) {}
}
