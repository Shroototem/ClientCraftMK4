package com.clientcraftmk4.tree;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.crafting.display.RecipeDisplayId;

import java.util.*;

public class CraftCalculator {

    private record MemoEntry(long count, boolean containerOnly) {}

    /**
     * Computes per-recipe craftable counts for all recipes in the tree.
     * Uses the memoized topological approach: builds a memo of per-item max counts,
     * then derives per-recipe counts from it. Sub-item lookups are cached so each
     * per-recipe call is O(ingredient edges).
     */
    public static Map<RecipeDisplayId, Integer> calculatePerRecipeCounts(
            RecipeTree tree,
            Map<Item, Integer> inventory,
            Map<Item, Integer> containerInventory,
            int gridSize,
            int maxOutput) {
        Map<Item, Integer> combined = combinedInventory(inventory, containerInventory);
        Map<Item, MemoEntry> memo = new HashMap<>();

        for (Item item : tree.getTopologicalOrder()) {
            RecipeNode node = tree.getNode(item);
            if (node != null) {
                computeMemo(node, tree, inventory, combined, gridSize, memo);
            }
        }

        Map<RecipeDisplayId, Integer> results = new HashMap<>();
        for (Item item : tree.getTopologicalOrder()) {
            List<CraftedItem> recipes = tree.getAllRecipes(item);
            if (recipes.isEmpty()) continue;
            long alreadyHave = combined.getOrDefault(item, 0);

            if (recipes.size() > 1) {
                MemoEntry itemMemo = memo.get(item);
                if (itemMemo != null) {
                    long maxNewItems = Math.max(0, itemMemo.count - alreadyHave);
                    if (maxNewItems > 0) {
                        long base = maxNewItems / recipes.size();
                        int rem = (int) (maxNewItems % recipes.size());
                        for (int i = 0; i < recipes.size(); i++) {
                            long share = base + (i < rem ? 1 : 0);
                            results.put(recipes.get(i).recipeId(),
                                    (int) Math.min(share, maxOutput));
                        }
                        continue;
                    }
                }
            }

            for (CraftedItem crafted : recipes) {
                Set<Item> baseOnlyItems = computeCycleIngredients(crafted, tree);
                long total = computeForRecipe(crafted, tree, inventory, combined, gridSize, memo, baseOnlyItems);
                long newItems = total - alreadyHave;
                if (newItems > 0) {
                    results.put(crafted.recipeId(), (int) Math.min(newItems, maxOutput));
                }
            }
        }
        return results;
    }

    private static long computeMemo(RecipeNode node, RecipeTree tree, Map<Item, Integer> inventory,
                                    Map<Item, Integer> combined, int gridSize,
                                    Map<Item, MemoEntry> memo) {
        MemoEntry cached = memo.get(node.item());
        if (cached != null) return cached.count;

        long baseValue = combined.getOrDefault(node.item(), 0);
        memo.put(node.item(), new MemoEntry(baseValue, false));

        long result;
        boolean containerOnly = false;

        if (node instanceof BaseResource base) {
            result = combined.getOrDefault(base.item(), 0);
            containerOnly = inventory.getOrDefault(base.item(), 0) == 0 && result > 0;

            List<CraftedItem> alternatives = tree.getAllRecipes(base.item());
            if (alternatives != null && !alternatives.isEmpty()) {
                for (CraftedItem alt : alternatives) {
                    long altResult = computeForRecipeBaseOnly(alt, combined, gridSize);
                    if (altResult > result) {
                        result = altResult;
                        containerOnly = false;
                    }
                }
            }
        } else {
            CraftedItem crafted = (CraftedItem) node;
            result = computeForRecipe(crafted, tree, inventory, combined, gridSize, memo);

            List<CraftedItem> alternatives = tree.getAllRecipes(crafted.item());
            if (alternatives != null && alternatives.size() > 1) {
                for (CraftedItem alt : alternatives) {
                    if (alt == crafted) continue;
                    long altResult = computeForRecipe(alt, tree, inventory, combined, gridSize, memo);
                    result = Math.max(result, altResult);
                }
            }

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

        memo.put(node.item(), new MemoEntry(result, containerOnly));
        return result;
    }

    private static long computeForRecipe(CraftedItem crafted, RecipeTree tree,
                                          Map<Item, Integer> inventory, Map<Item, Integer> combined,
                                          int gridSize, Map<Item, MemoEntry> memo) {
        return computeForRecipe(crafted, tree, inventory, combined, gridSize, memo, null);
    }

    private static long computeForRecipe(CraftedItem crafted, RecipeTree tree,
                                          Map<Item, Integer> inventory, Map<Item, Integer> combined,
                                          int gridSize, Map<Item, MemoEntry> memo,
                                          Set<Item> baseOnlyItems) {
        if (crafted.gridSize() > gridSize) {
            return combined.getOrDefault(crafted.item(), 0);
        }

        long maxOps = Long.MAX_VALUE;

        for (IngredientEdge edge : crafted.ingredients()) {
            long availableForEdge = 0;

            for (IngredientOption option : edge.options()) {
                if (baseOnlyItems != null && baseOnlyItems.contains(option.item())) {
                    availableForEdge += combined.getOrDefault(option.item(), 0);
                    continue;
                }
                MemoEntry optMemo = memo.get(option.item());
                long optAvail;
                if (optMemo != null) {
                    optAvail = optMemo.count;
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

    private static long computeForRecipeBaseOnly(CraftedItem crafted,
                                                  Map<Item, Integer> combined,
                                                  int gridSize) {
        if (crafted.gridSize() > gridSize) {
            return combined.getOrDefault(crafted.item(), 0);
        }

        long maxOps = Long.MAX_VALUE;

        for (IngredientEdge edge : crafted.ingredients()) {
            long availableForEdge = 0;
            for (IngredientOption option : edge.options()) {
                availableForEdge += combined.getOrDefault(option.item(), 0);
            }
            long opsFromEdge = availableForEdge / edge.count();
            maxOps = Math.min(maxOps, opsFromEdge);
        }

        if (maxOps == Long.MAX_VALUE) maxOps = 0;

        long alreadyHave = combined.getOrDefault(crafted.item(), 0);
        return maxOps * crafted.outputCount() + alreadyHave;
    }

    private static Set<Item> computeCycleIngredients(CraftedItem crafted, RecipeTree tree) {
        Set<Item> cycles = new HashSet<>();
        for (IngredientEdge edge : crafted.ingredients()) {
            for (IngredientOption option : edge.options()) {
                if (hasReverseConversion(option.item(), crafted.item(), tree)) {
                    cycles.add(option.item());
                }
            }
        }
        return cycles;
    }

    private static boolean hasReverseConversion(Item ingredient, Item output, RecipeTree tree) {
        List<CraftedItem> altRecipes = tree.getAllRecipes(ingredient);
        for (CraftedItem alt : altRecipes) {
            for (IngredientEdge edge : alt.ingredients()) {
                for (IngredientOption option : edge.options()) {
                    if (option.item() == output) return true;
                }
            }
        }
        return false;
    }

    private static boolean isRecipeContainerOnly(CraftedItem crafted, Map<Item, MemoEntry> memo) {
        for (IngredientEdge edge : crafted.ingredients()) {
            boolean edgeContainerOnly = true;
            boolean edgeHasAvailability = false;
            for (IngredientOption option : edge.options()) {
                MemoEntry optMemo = memo.get(option.item());
                if (optMemo != null && optMemo.count > 0) {
                    edgeHasAvailability = true;
                    if (!optMemo.containerOnly) {
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
                                           int gridSize, Map<Item, MemoEntry> memo) {
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

    private static Map<Item, Integer> combinedInventory(Map<Item, Integer> inventory, Map<Item, Integer> containerInventory) {
        Map<Item, Integer> combined = new HashMap<>(inventory);
        if (containerInventory != null) containerInventory.forEach((k, v) -> combined.merge(k, v, Integer::sum));
        return combined;
    }
}
