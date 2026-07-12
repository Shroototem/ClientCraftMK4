package com.clientcraftmk4.tree;

import com.clientcraftmk4.RecipeResolver;
import net.minecraft.client.gui.screens.recipebook.RecipeCollection;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.crafting.display.RecipeDisplay;
import net.minecraft.world.item.crafting.display.RecipeDisplayEntry;
import net.minecraft.world.item.crafting.display.ShapedCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.ShapelessCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.SlotDisplay;

import java.util.*;

public class RecipeTreeBuilder {

    public static RecipeTree build(List<RecipeCollection> allCrafting) {
        if (!RecipeResolver.isEnvReady()) return null;

        Map<Item, List<RecipeDisplayEntry>> recipesByOutput = new HashMap<>();

        for (RecipeCollection coll : allCrafting) {
            for (RecipeDisplayEntry entry : coll.getRecipes()) {
                Item out = RecipeResolver.getOutputItem(entry.display());
                if (out != null) {
                    recipesByOutput.computeIfAbsent(out, k -> new ArrayList<>()).add(entry);
                }
            }
        }

        Set<Item> allIngredientItems = new HashSet<>();
        for (List<RecipeDisplayEntry> entries : recipesByOutput.values()) {
            for (RecipeDisplayEntry entry : entries) {
                List<SlotDisplay> slots = RecipeResolver.getSlots(entry.display());
                if (slots == null) continue;
                for (SlotDisplay slot : slots) {
                    collectItems(slot, allIngredientItems);
                }
            }
        }

        Map<Item, RecipeNode> resolved = new HashMap<>();
        Set<Item> baseResources = new HashSet<>();

        for (Item item : allIngredientItems) {
            if (!recipesByOutput.containsKey(item)) {
                resolved.put(item, new BaseResource(item));
                baseResources.add(item);
            }
        }

        Map<Item, Integer> inDegree = new HashMap<>();
        Map<Item, List<Set<Item>>> edgeDeps = new HashMap<>();
        Map<Item, boolean[]> edgeResolved = new HashMap<>();
        Map<Item, Set<Item>> optionToParents = new HashMap<>();

        for (Map.Entry<Item, List<RecipeDisplayEntry>> e : recipesByOutput.entrySet()) {
            Item outputItem = e.getKey();

            List<Set<Item>> bestEdges = null;
            int bestUnresolved = Integer.MAX_VALUE;

            for (RecipeDisplayEntry entry : e.getValue()) {
                List<Set<Item>> edges = getConsolidatedEdges(entry.display(), outputItem);
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

            for (int i = 0; i < bestEdges.size(); i++) {
                if (!flags[i]) {
                    for (Item option : bestEdges.get(i)) {
                        optionToParents.computeIfAbsent(option, k -> new HashSet<>()).add(outputItem);
                    }
                }
            }
        }

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

            CraftedItem bestNode = buildBestNode(item, recipesByOutput.get(item), resolved);
            if (bestNode != null) {
                resolved.put(item, bestNode);
            } else {
                resolved.put(item, new BaseResource(item));
                baseResources.add(item);
            }

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
                        break;
                    }
                }
            }
        }

        for (Item item : recipesByOutput.keySet()) {
            if (!resolved.containsKey(item)) {
                resolved.put(item, new BaseResource(item));
                baseResources.add(item);
                topologicalOrder.add(item);
            }
        }

        Map<Item, List<CraftedItem>> allRecipesMap = new HashMap<>();
        for (Map.Entry<Item, List<RecipeDisplayEntry>> e : recipesByOutput.entrySet()) {
            Item outputItem = e.getKey();
            List<CraftedItem> nodes = new ArrayList<>();
            for (RecipeDisplayEntry entry : e.getValue()) {
                CraftedItem node = buildCraftedItem(outputItem, entry, resolved);
                if (node != null) nodes.add(node);
            }
            if (!nodes.isEmpty()) {
                allRecipesMap.put(outputItem, nodes);
            }
        }

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
            Map<Item, RecipeNode> resolved) {
        CraftedItem best = null;
        for (RecipeDisplayEntry entry : entries) {
            CraftedItem node = buildCraftedItem(outputItem, entry, resolved);
            if (node == null) continue;
            if (best == null || node.depth() < best.depth()) {
                best = node;
            }
        }
        return best;
    }

    private static CraftedItem buildCraftedItem(
            Item outputItem, RecipeDisplayEntry entry,
            Map<Item, RecipeNode> resolved) {
        RecipeDisplay display = entry.display();
        List<SlotDisplay> slots = RecipeResolver.getSlots(display);
        if (slots == null) return null;

        int outputCount = RecipeResolver.getOutputCount(display);
        if (outputCount <= 0) return null;

        int gridSize = getGridSize(display);

        Map<List<Item>, ConsolidatedIngredient> consolidated = new LinkedHashMap<>();
        for (SlotDisplay slot : slots) {
            if (slot instanceof SlotDisplay.Empty) continue;

            List<IngredientOption> options = buildOptions(slot, resolved, outputItem);
            if (options.isEmpty()) return null;

            List<Item> key = optionsKey(options);
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
            SlotDisplay slot, Map<Item, RecipeNode> resolved, Item excludeItem) {
        List<IngredientOption> options = new ArrayList<>();

        if (slot instanceof SlotDisplay.ItemSlotDisplay d) {
            Item item = d.item().value();
            if (!item.equals(excludeItem)) {
                RecipeNode node = resolved.getOrDefault(item, new BaseResource(item));
                options.add(new IngredientOption(item, node));
            }
        } else if (slot instanceof SlotDisplay.ItemStackSlotDisplay d) {
            Item item = d.stack().item().value();
            if (!item.equals(excludeItem)) {
                RecipeNode node = resolved.getOrDefault(item, new BaseResource(item));
                options.add(new IngredientOption(item, node));
            }
        } else if (slot instanceof SlotDisplay.TagSlotDisplay d) {
            TagKey<Item> tag = d.tag();
            for (Item item : RecipeResolver.getOrComputeTagMembers(tag)) {
                if (item.equals(excludeItem)) continue;
                RecipeNode node = resolved.getOrDefault(item, new BaseResource(item));
                options.add(new IngredientOption(item, node));
            }
        } else if (slot instanceof SlotDisplay.Composite d) {
            for (SlotDisplay sub : d.contents()) {
                options.addAll(buildOptions(sub, resolved, excludeItem));
            }
        } else if (slot instanceof SlotDisplay.WithRemainder d) {
            options.addAll(buildOptions(d.input(), resolved, excludeItem));
        }

        return options;
    }

    private static List<Set<Item>> getConsolidatedEdges(RecipeDisplay display, Item outputItem) {
        List<SlotDisplay> slots = RecipeResolver.getSlots(display);
        if (slots == null) return null;

        Map<List<Item>, Set<Item>> consolidated = new LinkedHashMap<>();
        for (SlotDisplay slot : slots) {
            if (slot instanceof SlotDisplay.Empty) continue;

            Set<Item> options = new LinkedHashSet<>();
            collectItems(slot, options);
            options.remove(outputItem);
            if (options.isEmpty()) return null;

            List<Item> key = itemSetKey(options);
            consolidated.merge(key, options, (a, b) -> a);
        }

        return new ArrayList<>(consolidated.values());
    }

    private static List<Item> optionsKey(List<IngredientOption> options) {
        if (options.size() == 1) return List.of(options.getFirst().item());
        List<Item> items = new ArrayList<>(options.size());
        for (IngredientOption opt : options) items.add(opt.item());
        items.sort(Comparator.comparingInt(System::identityHashCode));
        return items;
    }

    private static List<Item> itemSetKey(Set<Item> items) {
        if (items.size() == 1) return List.of(items.iterator().next());
        List<Item> sorted = new ArrayList<>(items);
        sorted.sort(Comparator.comparingInt(System::identityHashCode));
        return sorted;
    }

    public static void collectItems(SlotDisplay slot, Set<Item> items) {
        if (slot instanceof SlotDisplay.ItemSlotDisplay d) {
            items.add(d.item().value());
        } else if (slot instanceof SlotDisplay.ItemStackSlotDisplay d) {
            items.add(d.stack().item().value());
        } else if (slot instanceof SlotDisplay.TagSlotDisplay d) {
            items.addAll(RecipeResolver.getOrComputeTagMembers(d.tag()));
        } else if (slot instanceof SlotDisplay.Composite d) {
            for (SlotDisplay sub : d.contents()) collectItems(sub, items);
        } else if (slot instanceof SlotDisplay.WithRemainder d) {
            collectItems(d.input(), items);
        }
    }

    private static int getGridSize(RecipeDisplay display) {
        if (display instanceof ShapedCraftingRecipeDisplay s) {
            return Math.max(s.width(), s.height());
        }
        if (display instanceof ShapelessCraftingRecipeDisplay s) {
            int count = 0;
            for (SlotDisplay slot : s.ingredients()) {
                if (!(slot instanceof SlotDisplay.Empty)) count++;
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
