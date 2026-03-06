package com.clientcraftmk4.tree;

import net.minecraft.item.Item;

import java.util.*;

public class CraftCalculator {

    public static Map<Item, Integer> calculateAllCounts(
            RecipeTree tree, Map<Item, Integer> inventory,
            Map<Item, Integer> containerInventory, int gridSize) {

        Map<Item, Integer> combined = combinedInventory(inventory, containerInventory);
        Map<Item, long[]> memo = new HashMap<>();
        Map<Item, Integer> results = new HashMap<>();

        for (Item item : tree.getTopologicalOrder()) {
            RecipeNode node = tree.getNode(item);
            if (node == null) continue;
            computeMemo(node, tree, inventory, combined, gridSize, memo);
        }

        for (Map.Entry<Item, long[]> e : memo.entrySet()) {
            if (e.getValue()[0] > 0) {
                results.put(e.getKey(), clampInt(e.getValue()[0]));
            }
        }

        return results;
    }

    public static int maxCraftable(RecipeNode node, RecipeTree tree, Map<Item, Integer> inventory,
                                   Map<Item, Integer> containerInventory, int gridSize) {
        Map<Item, Integer> combined = combinedInventory(inventory, containerInventory);
        Map<Item, long[]> memo = new HashMap<>();
        return clampInt(computeMemo(node, tree, inventory, combined, gridSize, memo));
    }

    private static long computeMemo(RecipeNode node, RecipeTree tree, Map<Item, Integer> inventory,
                                    Map<Item, Integer> combined, int gridSize,
                                    Map<Item, long[]> memo) {
        long[] cached = memo.get(node.item());
        if (cached != null) return cached[0];

        // Set sentinel to prevent infinite recursion on recipe cycles
        long baseValue = combined.getOrDefault(node.item(), 0);
        memo.put(node.item(), new long[]{baseValue, 0});

        long result;
        boolean containerOnly = false;

        if (node instanceof BaseResource base) {
            result = combined.getOrDefault(base.item(), 0);
            containerOnly = inventory.getOrDefault(base.item(), 0) == 0 && result > 0;

            // Check if this BaseResource has alternative CraftedItem recipes (former cycle items)
            List<CraftedItem> alternatives = tree.getAllRecipes(base.item());
            if (alternatives != null && !alternatives.isEmpty()) {
                for (CraftedItem alt : alternatives) {
                    long altResult = computeForRecipe(alt, tree, inventory, combined, gridSize, memo);
                    if (altResult > result) {
                        result = altResult;
                        containerOnly = false;
                    }
                }
            }
        } else {
            CraftedItem crafted = (CraftedItem) node;
            result = computeForRecipe(crafted, tree, inventory, combined, gridSize, memo);

            // Check alternative recipes — use the best count
            List<CraftedItem> alternatives = tree.getAllRecipes(crafted.item());
            if (alternatives != null && alternatives.size() > 1) {
                for (CraftedItem alt : alternatives) {
                    if (alt == crafted) continue;
                    long altResult = computeForRecipe(alt, tree, inventory, combined, gridSize, memo);
                    result = Math.max(result, altResult);
                }
            }

            // Determine container-only status from best result
            containerOnly = result > 0
                    && inventory.getOrDefault(node.item(), 0) == 0
                    && isRecipeContainerOnly(crafted, memo);

            if (containerOnly) {
                long directOps = calculateDirectOps(crafted, inventory, gridSize, memo);
                if (directOps > 0 || inventory.getOrDefault(crafted.item(), 0) > 0) {
                    containerOnly = false;
                }
            }
        }

        memo.put(node.item(), new long[]{result, containerOnly ? 1 : 0});
        return result;
    }

    private static long computeForRecipe(CraftedItem crafted, RecipeTree tree,
                                         Map<Item, Integer> inventory, Map<Item, Integer> combined,
                                         int gridSize, Map<Item, long[]> memo) {
        if (crafted.gridSize() > gridSize) {
            return combined.getOrDefault(crafted.item(), 0);
        }

        long maxOps = Long.MAX_VALUE;

        for (IngredientEdge edge : crafted.ingredients()) {
            long availableForEdge = 0;

            for (IngredientOption option : edge.options()) {
                long[] optMemo = memo.get(option.item());
                long optAvail;
                if (optMemo != null) {
                    optAvail = optMemo[0];
                } else {
                    RecipeNode optNode = tree.getNode(option.item());
                    if (optNode != null) {
                        optAvail = computeMemo(optNode, tree, inventory, combined, gridSize, memo);
                    } else {
                        optAvail = combined.getOrDefault(option.item(), 0);
                    }
                }
                availableForEdge += optAvail;
            }

            long opsFromEdge = availableForEdge / edge.count();
            maxOps = Math.min(maxOps, opsFromEdge);
        }

        if (maxOps == Long.MAX_VALUE) maxOps = 0;

        long alreadyHave = combined.getOrDefault(crafted.item(), 0);
        return maxOps * crafted.outputCount() + alreadyHave;
    }

    private static boolean isRecipeContainerOnly(CraftedItem crafted, Map<Item, long[]> memo) {
        for (IngredientEdge edge : crafted.ingredients()) {
            boolean edgeContainerOnly = true;
            boolean edgeHasAvailability = false;
            for (IngredientOption option : edge.options()) {
                long[] optMemo = memo.get(option.item());
                if (optMemo != null && optMemo[0] > 0) {
                    edgeHasAvailability = true;
                    if (optMemo[1] == 0) {
                        edgeContainerOnly = false;
                        break;
                    }
                }
            }
            if (edgeContainerOnly && edgeHasAvailability) return true;
        }
        return false;
    }

    private static long calculateDirectOps(CraftedItem crafted, Map<Item, Integer> directInv,
                                           int gridSize, Map<Item, long[]> memo) {
        long maxOps = Long.MAX_VALUE;
        for (IngredientEdge edge : crafted.ingredients()) {
            long available = 0;
            for (IngredientOption option : edge.options()) {
                if (option.node() instanceof BaseResource) {
                    available += directInv.getOrDefault(option.item(), 0);
                } else if (option.node() instanceof CraftedItem sub) {
                    if (sub.gridSize() <= gridSize) {
                        long subDirect = calculateDirectOps(sub, directInv, gridSize, memo);
                        available += subDirect * sub.outputCount() + directInv.getOrDefault(option.item(), 0);
                    } else {
                        available += directInv.getOrDefault(option.item(), 0);
                    }
                }
            }
            maxOps = Math.min(maxOps, available / edge.count());
        }
        return maxOps == Long.MAX_VALUE ? 0 : maxOps;
    }

    public static Set<Item> computeAffectedItems(RecipeTree tree, Set<Item> changedItems) {
        Set<Item> affected = new HashSet<>(changedItems);
        Queue<Item> queue = new ArrayDeque<>(changedItems);

        while (!queue.isEmpty()) {
            Item item = queue.poll();
            Set<Item> deps = tree.getDependents(item);
            for (Item dep : deps) {
                if (affected.add(dep)) {
                    queue.add(dep);
                }
            }
        }

        return affected;
    }

    public static void updateCounts(
            RecipeTree tree, Map<Item, Integer> inventory,
            Map<Item, Integer> containerInventory, int gridSize,
            Set<Item> affectedItems, Map<Item, Integer> existingCounts) {

        Map<Item, Integer> combined = combinedInventory(inventory, containerInventory);

        Map<Item, long[]> memo = new HashMap<>();
        for (Map.Entry<Item, Integer> e : existingCounts.entrySet()) {
            if (!affectedItems.contains(e.getKey())) {
                memo.put(e.getKey(), new long[]{e.getValue(), 0});
            }
        }

        for (Item item : tree.getTopologicalOrder()) {
            if (!affectedItems.contains(item)) continue;
            RecipeNode node = tree.getNode(item);
            if (node == null) continue;
            computeMemo(node, tree, inventory, combined, gridSize, memo);
        }

        for (Item item : affectedItems) {
            long[] m = memo.get(item);
            if (m != null && m[0] > 0) {
                existingCounts.put(item, clampInt(m[0]));
            } else {
                existingCounts.remove(item);
            }
        }
    }

    private static Map<Item, Integer> combinedInventory(Map<Item, Integer> inventory, Map<Item, Integer> containerInventory) {
        Map<Item, Integer> combined = new HashMap<>(inventory);
        if (containerInventory != null) containerInventory.forEach((k, v) -> combined.merge(k, v, Integer::sum));
        return combined;
    }

    private static int clampInt(long val) {
        return (int) Math.min(val, 99999);
    }
}
