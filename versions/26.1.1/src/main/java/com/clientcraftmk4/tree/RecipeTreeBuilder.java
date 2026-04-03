package com.clientcraftmk4.tree;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.recipebook.RecipeCollection;
import net.minecraft.core.registries.Registries;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.display.RecipeDisplay;
import net.minecraft.world.item.crafting.display.RecipeDisplayEntry;
import net.minecraft.world.item.crafting.display.ShapedCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.ShapelessCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.SlotDisplay;

import java.util.*;

public class RecipeTreeBuilder {

    public static RecipeTree build(List<RecipeCollection> allCrafting) {
        Minecraft client = Minecraft.getInstance();
        if (client.level == null) return null;

        // Phase 1: Index all recipes by output item
        Map<Item, List<RecipeDisplayEntry>> recipesByOutput = new HashMap<>();

        for (RecipeCollection coll : allCrafting) {
            for (RecipeDisplayEntry entry : coll.getRecipes()) {
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
            Map<Item, RecipeNode> resolved, Minecraft client) {
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
            Map<Item, RecipeNode> resolved, Minecraft client) {
        RecipeDisplay display = entry.display();
        List<SlotDisplay> slots = getSlots(display);
        if (slots == null) return null;

        int outputCount = getOutputCount(display);
        if (outputCount <= 0) return null;

        int gridSize = getGridSize(display);

        // Consolidate ingredients, filtering output item from tag options
        Map<String, ConsolidatedIngredient> consolidated = new LinkedHashMap<>();
        for (SlotDisplay slot : slots) {
            if (slot instanceof SlotDisplay.Empty) continue;

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
            SlotDisplay slot, Map<Item, RecipeNode> resolved, Minecraft client, Item excludeItem) {
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
            var reg = client.level.registryAccess().lookupOrThrow(Registries.ITEM);
            var entriesOpt = reg.get(tag);
            if (entriesOpt.isPresent()) {
                for (var entry : entriesOpt.get()) {
                    Item item = entry.value();
                    if (item.equals(excludeItem)) continue; // Filter out the output item
                    RecipeNode node = resolved.getOrDefault(item, new BaseResource(item));
                    options.add(new IngredientOption(item, node));
                }
            }
        } else if (slot instanceof SlotDisplay.Composite d) {
            for (SlotDisplay sub : d.contents()) {
                options.addAll(buildOptions(sub, resolved, client, excludeItem));
            }
        } else if (slot instanceof SlotDisplay.WithRemainder d) {
            options.addAll(buildOptions(d.input(), resolved, client, excludeItem));
        }

        return options;
    }

    private static List<Set<Item>> getConsolidatedEdges(RecipeDisplay display, Item outputItem, Minecraft client) {
        List<SlotDisplay> slots = getSlots(display);
        if (slots == null) return null;

        Map<String, Set<Item>> consolidated = new LinkedHashMap<>();
        for (SlotDisplay slot : slots) {
            if (slot instanceof SlotDisplay.Empty) continue;

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

    public static boolean isSelfConsuming(RecipeDisplayEntry entry, Item outputItem) {
        Minecraft client = Minecraft.getInstance();
        if (client.level == null) return true;

        List<SlotDisplay> slots = getSlots(entry.display());
        if (slots == null) return true;
        for (SlotDisplay slot : slots) {
            if (slot instanceof SlotDisplay.Empty) continue;
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

    public static void collectItems(SlotDisplay slot, Set<Item> items, Minecraft client) {
        if (slot instanceof SlotDisplay.ItemSlotDisplay d) {
            items.add(d.item().value());
        } else if (slot instanceof SlotDisplay.ItemStackSlotDisplay d) {
            items.add(d.stack().item().value());
        } else if (slot instanceof SlotDisplay.TagSlotDisplay d) {
            var reg = client.level.registryAccess().lookupOrThrow(Registries.ITEM);
            var entriesOpt = reg.get(d.tag());
            if (entriesOpt.isPresent()) {
                for (var entry : entriesOpt.get()) items.add(entry.value());
            }
        } else if (slot instanceof SlotDisplay.Composite d) {
            for (SlotDisplay sub : d.contents()) collectItems(sub, items, client);
        } else if (slot instanceof SlotDisplay.WithRemainder d) {
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
                if (!(slot instanceof SlotDisplay.Empty)) count++;
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
        if (display instanceof SlotDisplay.ItemStackSlotDisplay d)
            return new ItemStack(d.stack().item().value(), d.stack().count());
        if (display instanceof SlotDisplay.Composite d) {
            for (SlotDisplay sub : d.contents()) {
                ItemStack r = resolveOutputSlot(sub);
                if (!r.isEmpty()) return r;
            }
        }
        if (display instanceof SlotDisplay.WithRemainder d) return resolveOutputSlot(d.input());
        return ItemStack.EMPTY;
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
