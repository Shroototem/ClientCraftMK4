package com.clientcraftmk4.tree;

import net.minecraft.item.Item;
import net.minecraft.recipe.NetworkRecipeId;
import net.minecraft.recipe.RecipeDisplayEntry;

import java.util.*;

public class RecipeTree {
    private final Map<Item, RecipeNode> primaryNodes;
    private final Map<Item, List<CraftedItem>> allRecipes;
    private final Map<Item, Set<Item>> dependents;
    private final List<Item> topologicalOrder;
    private final Set<Item> baseResources;
    private final Map<NetworkRecipeId, RecipeDisplayEntry> entryById;

    public RecipeTree(
            Map<Item, RecipeNode> primaryNodes,
            Map<Item, List<CraftedItem>> allRecipes,
            Map<Item, Set<Item>> dependents,
            List<Item> topologicalOrder,
            Set<Item> baseResources,
            Map<NetworkRecipeId, RecipeDisplayEntry> entryById
    ) {
        this.primaryNodes = primaryNodes;
        this.allRecipes = allRecipes;
        this.dependents = dependents;
        this.topologicalOrder = topologicalOrder;
        this.baseResources = baseResources;
        this.entryById = entryById;
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

    public Set<Item> getBaseResources() {
        return baseResources;
    }

    public Map<Item, RecipeNode> getPrimaryNodes() {
        return primaryNodes;
    }

    public RecipeDisplayEntry getEntryById(NetworkRecipeId id) {
        return entryById.get(id);
    }
}
