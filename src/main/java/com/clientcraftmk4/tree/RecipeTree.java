package com.clientcraftmk4.tree;

import net.minecraft.world.item.Item;

import java.util.*;

public class RecipeTree {
    private final Map<Item, RecipeNode> primaryNodes;
    private final Map<Item, List<CraftedItem>> allRecipes;
    private final Map<Item, Set<Item>> dependents;
    private final List<Item> topologicalOrder;

    public RecipeTree(
            Map<Item, RecipeNode> primaryNodes,
            Map<Item, List<CraftedItem>> allRecipes,
            Map<Item, Set<Item>> dependents,
            List<Item> topologicalOrder
    ) {
        this.primaryNodes = primaryNodes;
        this.allRecipes = allRecipes;
        this.dependents = dependents;
        this.topologicalOrder = topologicalOrder;
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
}
