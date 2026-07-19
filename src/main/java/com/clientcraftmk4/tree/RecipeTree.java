package com.clientcraftmk4.tree;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.crafting.display.RecipeDisplayId;

import java.util.*;

public class RecipeTree {
    private final Map<Item, RecipeNode> primaryNodes;
    private final Map<Item, List<CraftedItem>> allRecipes;
    private final Map<Item, Set<Item>> dependents;
    private final List<Item> topologicalOrder;
    private final Map<Item, Set<Item>> reverseDependencyTargets;
    private final FlatData flat;

    public RecipeTree(
            Map<Item, RecipeNode> primaryNodes,
            Map<Item, List<CraftedItem>> allRecipes,
            Map<Item, Set<Item>> dependents,
            List<Item> topologicalOrder,
            Map<Item, Set<Item>> reverseDependencyTargets
    ) {
        this(primaryNodes, allRecipes, dependents, topologicalOrder, reverseDependencyTargets, null);
    }

    public RecipeTree(
            Map<Item, RecipeNode> primaryNodes,
            Map<Item, List<CraftedItem>> allRecipes,
            Map<Item, Set<Item>> dependents,
            List<Item> topologicalOrder,
            Map<Item, Set<Item>> reverseDependencyTargets,
            FlatData flat
    ) {
        this.primaryNodes = primaryNodes;
        this.allRecipes = allRecipes;
        this.dependents = dependents;
        this.topologicalOrder = topologicalOrder;
        this.reverseDependencyTargets = reverseDependencyTargets;
        this.flat = flat;
    }

    public RecipeNode getNode(Item item) {
        return primaryNodes.get(item);
    }

    public List<CraftedItem> getAllRecipes(Item item) {
        return allRecipes.getOrDefault(item, List.of());
    }

    public Set<Item> getDependents(Item item) {
        return dependents.getOrDefault(item, Set.of());
    }

    public List<Item> getTopologicalOrder() {
        return topologicalOrder;
    }

    public Set<Item> getReverseDependencyTargets(Item item) {
        return reverseDependencyTargets.getOrDefault(item, Set.of());
    }

    public FlatData flat() { return flat; }

    public record FlatData(
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
        int[] recEdgeStart, int[] recEdgeEnd,
        int totalEdges,
        int[] edgeCnt, int[] edgeOptStart, int[] edgeOptEnd,
        int totalOpts,
        int[] optItemId, Item[] optItemObj
    ) {}
}
