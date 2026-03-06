package com.clientcraftmk4.tree;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.recipebook.RecipeResultCollection;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.recipe.RecipeDisplayEntry;
import net.minecraft.recipe.display.RecipeDisplay;
import net.minecraft.recipe.display.ShapedCraftingRecipeDisplay;
import net.minecraft.recipe.display.ShapelessCraftingRecipeDisplay;
import net.minecraft.recipe.display.SlotDisplay;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.tag.TagKey;

import java.util.*;

public class RecipeTreeBuilder {

    public static RecipeTree build(List<RecipeResultCollection> allCrafting) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null) return null;

        // Phase 1: Index all recipes by output item
        Map<Item, List<RecipeDisplayEntry>> recipesByOutput = new HashMap<>();

        for (RecipeResultCollection coll : allCrafting) {
            for (RecipeDisplayEntry entry : coll.getAllRecipes()) {
                Item out = getOutputItem(entry.display());
                if (out != null) {
                    recipesByOutput.computeIfAbsent(out, k -> new ArrayList<>()).add(entry);
                }
            }
        }

        // Phase 2: Collect all ingredient items to identify base resources
        Set<Item> allIngredientItems = new HashSet<>();
        for (List<RecipeDisplayEntry> entries : recipesByOutput.values()) {
            for (RecipeDisplayEntry entry : entries) {
                List<SlotDisplay> slots = getSlots(entry.display());
                if (slots == null) continue;
                for (SlotDisplay slot : slots) {
                    collectItems(slot, allIngredientItems, client);
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

        for (Map.Entry<Item, List<RecipeDisplayEntry>> e : recipesByOutput.entrySet()) {
            Item outputItem = e.getKey();

            // Find the recipe with the fewest unresolved edges
            List<Set<Item>> bestEdges = null;
            int bestUnresolved = Integer.MAX_VALUE;

            for (RecipeDisplayEntry entry : e.getValue()) {
                List<Set<Item>> edges = getConsolidatedEdges(entry.display(), outputItem, client);
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

            CraftedItem bestNode = buildBestNode(item, recipesByOutput.get(item), resolved, client);
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
        for (Map.Entry<Item, List<RecipeDisplayEntry>> e : recipesByOutput.entrySet()) {
            Item outputItem = e.getKey();
            List<CraftedItem> nodes = new ArrayList<>();
            for (RecipeDisplayEntry entry : e.getValue()) {
                CraftedItem node = buildCraftedItem(outputItem, entry, resolved, client);
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

        return new RecipeTree(resolved, allRecipesMap, dependents, topologicalOrder);
    }

    private static CraftedItem buildBestNode(
            Item outputItem, List<RecipeDisplayEntry> entries,
            Map<Item, RecipeNode> resolved, MinecraftClient client) {
        CraftedItem best = null;
        for (RecipeDisplayEntry entry : entries) {
            CraftedItem node = buildCraftedItem(outputItem, entry, resolved, client);
            if (node == null) continue;
            if (best == null || node.depth() < best.depth()) {
                best = node;
            }
        }
        return best;
    }

    private static CraftedItem buildCraftedItem(
            Item outputItem, RecipeDisplayEntry entry,
            Map<Item, RecipeNode> resolved, MinecraftClient client) {
        RecipeDisplay display = entry.display();
        List<SlotDisplay> slots = getSlots(display);
        if (slots == null) return null;

        int outputCount = getOutputCount(display);
        if (outputCount <= 0) return null;

        int gridSize = getGridSize(display);

        // Consolidate ingredients, filtering output item from tag options
        Map<String, ConsolidatedIngredient> consolidated = new LinkedHashMap<>();
        for (SlotDisplay slot : slots) {
            if (slot instanceof SlotDisplay.EmptySlotDisplay) continue;

            List<IngredientOption> options = buildOptions(slot, resolved, client, outputItem);
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

    private static List<IngredientOption> buildOptions(
            SlotDisplay slot, Map<Item, RecipeNode> resolved, MinecraftClient client, Item excludeItem) {
        List<IngredientOption> options = new ArrayList<>();

        if (slot instanceof SlotDisplay.ItemSlotDisplay d) {
            Item item = d.item().value();
            if (!item.equals(excludeItem)) {
                RecipeNode node = resolved.getOrDefault(item, new BaseResource(item));
                options.add(new IngredientOption(item, node));
            }
        } else if (slot instanceof SlotDisplay.StackSlotDisplay d) {
            Item item = d.stack().getItem();
            if (!item.equals(excludeItem)) {
                RecipeNode node = resolved.getOrDefault(item, new BaseResource(item));
                options.add(new IngredientOption(item, node));
            }
        } else if (slot instanceof SlotDisplay.TagSlotDisplay d) {
            TagKey<Item> tag = d.tag();
            var regOpt = client.world.getRegistryManager().getOptional(RegistryKeys.ITEM);
            if (regOpt.isPresent()) {
                var entriesOpt = regOpt.get().getOptional(tag);
                if (entriesOpt.isPresent()) {
                    for (var entry : entriesOpt.get()) {
                        Item item = entry.value();
                        if (item.equals(excludeItem)) continue; // Filter out the output item
                        RecipeNode node = resolved.getOrDefault(item, new BaseResource(item));
                        options.add(new IngredientOption(item, node));
                    }
                }
            }
        } else if (slot instanceof SlotDisplay.CompositeSlotDisplay d) {
            for (SlotDisplay sub : d.contents()) {
                options.addAll(buildOptions(sub, resolved, client, excludeItem));
            }
        } else if (slot instanceof SlotDisplay.WithRemainderSlotDisplay d) {
            options.addAll(buildOptions(d.input(), resolved, client, excludeItem));
        }

        return options;
    }

    /**
     * Returns consolidated ingredient edges as sets of option items, excluding the output item.
     * Used for dependency tracking in Phase 3.
     */
    private static List<Set<Item>> getConsolidatedEdges(RecipeDisplay display, Item outputItem, MinecraftClient client) {
        List<SlotDisplay> slots = getSlots(display);
        if (slots == null) return null;

        Map<String, Set<Item>> consolidated = new LinkedHashMap<>();
        for (SlotDisplay slot : slots) {
            if (slot instanceof SlotDisplay.EmptySlotDisplay) continue;

            Set<Item> options = new LinkedHashSet<>();
            collectItems(slot, options, client);
            options.remove(outputItem);
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
    public static boolean isSelfConsuming(RecipeDisplayEntry entry, Item outputItem) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null) return true;

        List<SlotDisplay> slots = getSlots(entry.display());
        if (slots == null) return true;
        for (SlotDisplay slot : slots) {
            if (slot instanceof SlotDisplay.EmptySlotDisplay) continue;
            Set<Item> items = new HashSet<>();
            collectItems(slot, items, client);
            items.remove(outputItem);
            if (items.isEmpty()) return true; // This slot has no non-self option
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

    public static void collectItems(SlotDisplay slot, Set<Item> items, MinecraftClient client) {
        if (slot instanceof SlotDisplay.ItemSlotDisplay d) {
            items.add(d.item().value());
        } else if (slot instanceof SlotDisplay.StackSlotDisplay d) {
            items.add(d.stack().getItem());
        } else if (slot instanceof SlotDisplay.TagSlotDisplay d) {
            var regOpt = client.world.getRegistryManager().getOptional(RegistryKeys.ITEM);
            if (regOpt.isPresent()) {
                var entriesOpt = regOpt.get().getOptional(d.tag());
                if (entriesOpt.isPresent()) {
                    for (var entry : entriesOpt.get()) items.add(entry.value());
                }
            }
        } else if (slot instanceof SlotDisplay.CompositeSlotDisplay d) {
            for (SlotDisplay sub : d.contents()) collectItems(sub, items, client);
        } else if (slot instanceof SlotDisplay.WithRemainderSlotDisplay d) {
            collectItems(d.input(), items, client);
        }
    }

    public static List<SlotDisplay> getSlots(RecipeDisplay display) {
        if (display instanceof ShapedCraftingRecipeDisplay s) return s.ingredients();
        if (display instanceof ShapelessCraftingRecipeDisplay s) return s.ingredients();
        return null;
    }

    public static boolean fitsInGrid(RecipeDisplay display, int gridSize) {
        if (display instanceof ShapedCraftingRecipeDisplay s) {
            return s.width() <= gridSize && s.height() <= gridSize;
        } else if (display instanceof ShapelessCraftingRecipeDisplay s) {
            List<SlotDisplay> ingredients = s.ingredients();
            int count = 0;
            for (SlotDisplay slot : ingredients) {
                if (!(slot instanceof SlotDisplay.EmptySlotDisplay)) count++;
            }
            return count <= gridSize * gridSize;
        }
        return false;
    }

    public static Item getOutputItem(RecipeDisplay display) {
        ItemStack out = resolveOutputSlot(display.result());
        return out.isEmpty() ? null : out.getItem();
    }

    public static int getOutputCount(RecipeDisplay display) {
        ItemStack out = resolveOutputSlot(display.result());
        return out.isEmpty() ? 0 : out.getCount();
    }

    public static ItemStack resolveOutputSlot(SlotDisplay display) {
        if (display instanceof SlotDisplay.ItemSlotDisplay d) return new ItemStack(d.item().value());
        if (display instanceof SlotDisplay.StackSlotDisplay d)
            return new ItemStack(d.stack().getItem(), d.stack().getCount());
        if (display instanceof SlotDisplay.CompositeSlotDisplay d) {
            for (SlotDisplay sub : d.contents()) {
                ItemStack r = resolveOutputSlot(sub);
                if (!r.isEmpty()) return r;
            }
        }
        if (display instanceof SlotDisplay.WithRemainderSlotDisplay d) return resolveOutputSlot(d.input());
        return ItemStack.EMPTY;
    }

    private static int getGridSize(RecipeDisplay display) {
        if (display instanceof ShapedCraftingRecipeDisplay s) {
            return Math.max(s.width(), s.height());
        }
        if (display instanceof ShapelessCraftingRecipeDisplay s) {
            int count = 0;
            for (SlotDisplay slot : s.ingredients()) {
                if (!(slot instanceof SlotDisplay.EmptySlotDisplay)) count++;
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
