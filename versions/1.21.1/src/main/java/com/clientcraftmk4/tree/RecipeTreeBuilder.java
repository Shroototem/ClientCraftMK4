package com.clientcraftmk4.tree;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.recipebook.RecipeResultCollection;
import net.minecraft.client.recipebook.RecipeBookGroup;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.recipe.*;
import net.minecraft.registry.DynamicRegistryManager;
import net.minecraft.util.Identifier;
import net.minecraft.util.collection.DefaultedList;

import java.util.*;

public class RecipeTreeBuilder {

    public static RecipeTree build(List<RecipeResultCollection> allCrafting, DynamicRegistryManager registryManager) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null) return null;

        // Phase 1: Index all recipes by output item
        Map<Item, List<RecipeEntry<?>>> recipesByOutput = new HashMap<>();
        Map<Identifier, RecipeEntry<?>> entryById = new HashMap<>();

        for (RecipeResultCollection coll : allCrafting) {
            for (RecipeEntry<?> entry : coll.getAllRecipes()) {
                entryById.put(entry.id(), entry);
                Item out = getOutputItem(entry, registryManager);
                if (out != null) {
                    recipesByOutput.computeIfAbsent(out, k -> new ArrayList<>()).add(entry);
                }
            }
        }

        // Phase 2: Collect all ingredient items to identify base resources
        Set<Item> allIngredientItems = new HashSet<>();
        for (List<RecipeEntry<?>> entries : recipesByOutput.values()) {
            for (RecipeEntry<?> entry : entries) {
                DefaultedList<Ingredient> ingredients = getIngredients(entry);
                if (ingredients == null) continue;
                for (Ingredient ingredient : ingredients) {
                    if (ingredient.isEmpty()) continue;
                    for (ItemStack stack : ingredient.getMatchingStacks()) {
                        allIngredientItems.add(stack.getItem());
                    }
                }
            }
        }

        // Phase 3: Edge-based dependency tracking + BFS topological sort (Kahn's)
        Map<Item, RecipeNode> resolved = new HashMap<>();
        Set<Item> baseResources = new HashSet<>();

        // Base resources = items needed as ingredients but not craftable
        for (Item item : allIngredientItems) {
            if (!recipesByOutput.containsKey(item)) {
                resolved.put(item, new BaseResource(item));
                baseResources.add(item);
            }
        }

        // Edge-based dependency tracking:
        // Each ingredient edge (consolidated group of options) is resolved when ANY option is resolved.
        // inDegree counts unresolved EDGES, not individual items.
        Map<Item, Integer> inDegree = new HashMap<>();
        Map<Item, List<Set<Item>>> edgeDeps = new HashMap<>();
        Map<Item, boolean[]> edgeResolved = new HashMap<>();
        // Reverse index: option item -> parents that have this item in an unresolved edge
        Map<Item, Set<Item>> optionToParents = new HashMap<>();

        for (Map.Entry<Item, List<RecipeEntry<?>>> e : recipesByOutput.entrySet()) {
            Item outputItem = e.getKey();

            // Find the recipe with the fewest unresolved edges
            List<Set<Item>> bestEdges = null;
            int bestUnresolved = Integer.MAX_VALUE;

            for (RecipeEntry<?> entry : e.getValue()) {
                List<Set<Item>> edges = getConsolidatedEdges(entry, outputItem);
                if (edges == null) continue;

                int unresolved = 0;
                for (Set<Item> edge : edges) {
                    boolean hasResolved = false;
                    for (Item option : edge) {
                        if (resolved.containsKey(option)) { hasResolved = true; break; }
                    }
                    if (!hasResolved && !edge.isEmpty()) unresolved++;
                }

                if (bestEdges == null || unresolved < bestUnresolved) {
                    bestUnresolved = unresolved;
                    bestEdges = edges;
                }
            }

            if (bestEdges == null) bestEdges = List.of();

            // Compute resolved flags and inDegree
            boolean[] flags = new boolean[bestEdges.size()];
            int unresolvedCount = 0;
            for (int i = 0; i < bestEdges.size(); i++) {
                Set<Item> edge = bestEdges.get(i);
                boolean hasResolved = false;
                for (Item option : edge) {
                    if (resolved.containsKey(option)) { hasResolved = true; break; }
                }
                if (hasResolved || edge.isEmpty()) {
                    flags[i] = true;
                } else {
                    unresolvedCount++;
                }
            }

            edgeDeps.put(outputItem, bestEdges);
            edgeResolved.put(outputItem, flags);
            inDegree.put(outputItem, unresolvedCount);

            // Build reverse index
            for (int i = 0; i < bestEdges.size(); i++) {
                if (!flags[i]) {
                    for (Item option : bestEdges.get(i)) {
                        optionToParents.computeIfAbsent(option, k -> new HashSet<>()).add(outputItem);
                    }
                }
            }
        }

        // BFS Kahn's algorithm
        Queue<Item> queue = new ArrayDeque<>();
        List<Item> topologicalOrder = new ArrayList<>();

        for (Item base : baseResources) {
            topologicalOrder.add(base);
        }

        for (Map.Entry<Item, Integer> e : inDegree.entrySet()) {
            if (e.getValue() == 0) {
                queue.add(e.getKey());
            }
        }

        while (!queue.isEmpty()) {
            Item item = queue.poll();
            if (resolved.containsKey(item)) continue;
            topologicalOrder.add(item);

            CraftedItem bestNode = buildBestNode(item, recipesByOutput.get(item), resolved, registryManager);
            if (bestNode != null) {
                resolved.put(item, bestNode);
            } else {
                resolved.put(item, new BaseResource(item));
                baseResources.add(item);
            }

            // Update parents via reverse index
            Set<Item> parents = optionToParents.getOrDefault(item, Set.of());
            for (Item parent : parents) {
                if (resolved.containsKey(parent)) continue;
                List<Set<Item>> parentEdges = edgeDeps.get(parent);
                boolean[] parentFlags = edgeResolved.get(parent);
                if (parentEdges == null || parentFlags == null) continue;

                for (int i = 0; i < parentEdges.size(); i++) {
                    if (!parentFlags[i] && parentEdges.get(i).contains(item)) {
                        parentFlags[i] = true;
                        int newDeg = inDegree.get(parent) - 1;
                        inDegree.put(parent, newDeg);
                        if (newDeg <= 0) {
                            queue.add(parent);
                        }
                        break; // Each resolved item satisfies at most one edge per parent (consolidated)
                    }
                }
            }
        }

        // Phase 4: Handle cycles — items still unresolved become BaseResource
        for (Item item : recipesByOutput.keySet()) {
            if (!resolved.containsKey(item)) {
                resolved.put(item, new BaseResource(item));
                baseResources.add(item);
                topologicalOrder.add(item);
            }
        }

        // Phase 5: Build allRecipes map (with fully resolved nodes)
        Map<Item, List<CraftedItem>> allRecipesMap = new HashMap<>();
        for (Map.Entry<Item, List<RecipeEntry<?>>> e : recipesByOutput.entrySet()) {
            Item outputItem = e.getKey();
            List<CraftedItem> nodes = new ArrayList<>();
            for (RecipeEntry<?> entry : e.getValue()) {
                CraftedItem node = buildCraftedItem(outputItem, entry, resolved, registryManager);
                if (node != null) nodes.add(node);
            }
            if (!nodes.isEmpty()) {
                allRecipesMap.put(outputItem, nodes);
            }
        }

        // Phase 6: Build reverse dependency index (from allRecipes for completeness)
        Map<Item, Set<Item>> dependents = new HashMap<>();
        for (Map.Entry<Item, List<CraftedItem>> e : allRecipesMap.entrySet()) {
            for (CraftedItem crafted : e.getValue()) {
                for (IngredientEdge edge : crafted.ingredients()) {
                    for (IngredientOption option : edge.options()) {
                        dependents.computeIfAbsent(option.item(), k -> new HashSet<>()).add(crafted.item());
                    }
                }
            }
        }

        return new RecipeTree(resolved, allRecipesMap, dependents, topologicalOrder, baseResources, entryById);
    }

    private static CraftedItem buildBestNode(
            Item outputItem, List<RecipeEntry<?>> entries,
            Map<Item, RecipeNode> resolved, DynamicRegistryManager registryManager) {
        CraftedItem best = null;
        for (RecipeEntry<?> entry : entries) {
            CraftedItem node = buildCraftedItem(outputItem, entry, resolved, registryManager);
            if (node == null) continue;
            if (best == null || node.depth() < best.depth()) {
                best = node;
            }
        }
        return best;
    }

    private static CraftedItem buildCraftedItem(
            Item outputItem, RecipeEntry<?> entry,
            Map<Item, RecipeNode> resolved, DynamicRegistryManager registryManager) {
        DefaultedList<Ingredient> ingredients = getIngredients(entry);
        if (ingredients == null) return null;

        int outputCount = getOutputCount(entry, registryManager);
        if (outputCount <= 0) return null;

        int gridSize = getGridSize(entry);

        // Consolidate ingredients, filtering output item from tag options
        Map<String, ConsolidatedIngredient> consolidated = new LinkedHashMap<>();
        for (Ingredient ingredient : ingredients) {
            if (ingredient.isEmpty()) continue;

            List<IngredientOption> options = buildOptions(ingredient, resolved, outputItem);
            if (options.isEmpty()) return null; // Truly self-consuming: no non-self option exists

            String key = optionsKey(options);
            ConsolidatedIngredient existing = consolidated.get(key);
            if (existing != null) {
                existing.count++;
            } else {
                consolidated.put(key, new ConsolidatedIngredient(options, 1));
            }
        }

        List<IngredientEdge> edges = new ArrayList<>();
        int maxDepth = 0;
        for (ConsolidatedIngredient ci : consolidated.values()) {
            edges.add(new IngredientEdge(ci.count, ci.options));
            for (IngredientOption opt : ci.options) {
                if (opt.node() instanceof CraftedItem c) {
                    maxDepth = Math.max(maxDepth, c.depth());
                }
            }
        }

        return new CraftedItem(outputItem, outputCount, edges, entry, entry.id(), gridSize, maxDepth + 1);
    }

    private static List<IngredientOption> buildOptions(Ingredient ingredient, Map<Item, RecipeNode> resolved, Item excludeItem) {
        List<IngredientOption> options = new ArrayList<>();
        Set<Item> seen = new HashSet<>();

        for (ItemStack stack : ingredient.getMatchingStacks()) {
            Item item = stack.getItem();
            if (item.equals(excludeItem)) continue; // Filter out the output item
            if (!seen.add(item)) continue;
            RecipeNode node = resolved.getOrDefault(item, new BaseResource(item));
            options.add(new IngredientOption(item, node));
        }

        return options;
    }

    /**
     * Returns consolidated ingredient edges as sets of option items, excluding the output item.
     * Used for dependency tracking in Phase 3.
     */
    private static List<Set<Item>> getConsolidatedEdges(RecipeEntry<?> entry, Item outputItem) {
        DefaultedList<Ingredient> ingredients = getIngredients(entry);
        if (ingredients == null) return null;

        Map<String, Set<Item>> consolidated = new LinkedHashMap<>();
        for (Ingredient ingredient : ingredients) {
            if (ingredient.isEmpty()) continue;

            Set<Item> options = new LinkedHashSet<>();
            for (ItemStack stack : ingredient.getMatchingStacks()) {
                Item item = stack.getItem();
                if (!item.equals(outputItem)) options.add(item);
            }
            if (options.isEmpty()) return null; // Truly self-consuming

            List<String> sortedNames = new ArrayList<>();
            for (Item item : options) sortedNames.add(item.toString());
            Collections.sort(sortedNames);
            String key = String.join(",", sortedNames);

            consolidated.merge(key, options, (a, b) -> a); // Keep first (same options)
        }

        return new ArrayList<>(consolidated.values());
    }

    /**
     * Returns true if a recipe REQUIRES the output item as an ingredient (no alternative for some slot).
     */
    public static boolean isSelfConsuming(RecipeEntry<?> entry, Item outputItem) {
        DefaultedList<Ingredient> ingredients = getIngredients(entry);
        if (ingredients == null) return true;
        for (Ingredient ingredient : ingredients) {
            if (ingredient.isEmpty()) continue;
            boolean hasNonSelf = false;
            for (ItemStack stack : ingredient.getMatchingStacks()) {
                if (!stack.getItem().equals(outputItem)) { hasNonSelf = true; break; }
            }
            if (!hasNonSelf) return true;
        }
        return false;
    }

    private static String optionsKey(List<IngredientOption> options) {
        if (options.size() == 1) return options.getFirst().item().toString();
        List<String> keys = new ArrayList<>();
        for (IngredientOption opt : options) {
            keys.add(opt.item().toString());
        }
        Collections.sort(keys);
        return String.join(",", keys);
    }

    public static DefaultedList<Ingredient> getIngredients(RecipeEntry<?> entry) {
        Recipe<?> recipe = entry.value();
        if (recipe instanceof ShapedRecipe s) return s.getIngredients();
        if (recipe instanceof ShapelessRecipe s) return s.getIngredients();
        return null;
    }

    public static boolean fitsInGrid(RecipeEntry<?> entry, int gridSize) {
        Recipe<?> recipe = entry.value();
        if (recipe instanceof ShapedRecipe s) {
            return s.getWidth() <= gridSize && s.getHeight() <= gridSize;
        } else if (recipe instanceof ShapelessRecipe s) {
            int count = 0;
            for (Ingredient ingredient : s.getIngredients()) {
                if (!ingredient.isEmpty()) count++;
            }
            return count <= gridSize * gridSize;
        }
        return false;
    }

    public static Item getOutputItem(RecipeEntry<?> entry, DynamicRegistryManager registryManager) {
        ItemStack out = entry.value().getResult(registryManager);
        return out.isEmpty() ? null : out.getItem();
    }

    public static int getOutputCount(RecipeEntry<?> entry, DynamicRegistryManager registryManager) {
        ItemStack out = entry.value().getResult(registryManager);
        return out.isEmpty() ? 0 : out.getCount();
    }

    private static int getGridSize(RecipeEntry<?> entry) {
        Recipe<?> recipe = entry.value();
        if (recipe instanceof ShapedRecipe s) {
            return Math.max(s.getWidth(), s.getHeight());
        }
        if (recipe instanceof ShapelessRecipe s) {
            int count = 0;
            for (Ingredient ingredient : s.getIngredients()) {
                if (!ingredient.isEmpty()) count++;
            }
            return count <= 4 ? 2 : 3;
        }
        return 3;
    }

    private static class ConsolidatedIngredient {
        final List<IngredientOption> options;
        int count;

        ConsolidatedIngredient(List<IngredientOption> options, int count) {
            this.options = options;
            this.count = count;
        }
    }
}
