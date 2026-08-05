package com.clientcraftmk4.core;

import com.clientcraftmk4.core.RecipeGraph.GraphFlatData;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.crafting.display.RecipeDisplay;
import net.minecraft.world.item.crafting.display.RecipeDisplayEntry;
import net.minecraft.world.item.crafting.display.RecipeDisplayId;
import net.minecraft.world.item.crafting.display.ShapedCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.ShapelessCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.SlotDisplay;

import java.util.*;

/**
 * Builds the {@link RecipeGraph} from the recipe set (port of MK4's
 * RecipeTreeBuilder, plan §10). Two additions over MK4, both computed once at
 * build time instead of every resolve:
 * <ul>
 *   <li>{@code recCycleSuspect} — MK4's {@code hasCycleFlagsFlat};</li>
 *   <li>{@code recSharingSuspect} — MK4's {@code hasSharingSuspectEdgeFlat};</li>
 *   <li>{@code recReverseTargets} — per-recipe reverse-dependency target set.</li>
 * </ul>
 */
public final class GraphBuilder {
    private GraphBuilder() {}

    public static RecipeGraph build(RecipeIndex index, TagIndex tagIndex) {
        Map<Item, List<RecipeDisplayEntry>> recipesByOutput = new HashMap<>();
        for (Item out : index.outputs()) {
            List<RecipeDisplayEntry> entries = index.get(out);
            recipesByOutput.computeIfAbsent(out, k -> new ArrayList<>()).addAll(entries);
        }

        Set<Item> allIngredientItems = new HashSet<>();
        for (List<RecipeDisplayEntry> entries : recipesByOutput.values()) {
            for (RecipeDisplayEntry entry : entries) {
                List<SlotDisplay> slots = RecipeDisplays.getSlots(entry.display());
                if (slots == null) continue;
                for (SlotDisplay slot : slots) {
                    collectItems(slot, allIngredientItems, tagIndex);
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
                List<Set<Item>> edges = getConsolidatedEdges(entry.display(), outputItem, tagIndex);
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

            CraftedItem bestNode = buildBestNode(item, recipesByOutput.get(item), resolved, tagIndex);
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
                CraftedItem node = buildCraftedItem(outputItem, entry, resolved, tagIndex);
                if (node != null) nodes.add(node);
            }
            if (!nodes.isEmpty()) {
                allRecipesMap.put(outputItem, nodes);
            }
        }

        Map<Item, Set<Item>> dependents = new HashMap<>();
        Map<Item, Set<Item>> reverseDependencyTargets = new HashMap<>();
        for (Map.Entry<Item, List<CraftedItem>> e : allRecipesMap.entrySet()) {
            Item outputItem = e.getKey();
            for (CraftedItem crafted : e.getValue()) {
                for (IngredientEdge edge : crafted.ingredients()) {
                    for (IngredientOption option : edge.options()) {
                        dependents.computeIfAbsent(option.item(), k -> new HashSet<>()).add(crafted.item());
                        reverseDependencyTargets.computeIfAbsent(option.item(), k -> new HashSet<>()).add(outputItem);
                    }
                }
            }
        }

        GraphFlatData flat = buildFlatData(topologicalOrder, resolved, allRecipesMap, reverseDependencyTargets);
        return new RecipeGraph(resolved, allRecipesMap, dependents, topologicalOrder, reverseDependencyTargets, flat);
    }

    private static GraphFlatData buildFlatData(
            List<Item> topo, Map<Item, RecipeNode> resolved, Map<Item, List<CraftedItem>> allRecipesMap,
            Map<Item, Set<Item>> reverseDependencyTargets) {

        int n = topo.size();
        IdentityHashMap<Item, Integer> idMap = new IdentityHashMap<>(n * 2);
        Item[] idToItem = new Item[n];
        for (int i = 0; i < n; i++) {
            idToItem[i] = topo.get(i);
            idMap.put(topo.get(i), i);
        }

        boolean[] isBaseNode = new boolean[n];
        for (int i = 0; i < n; i++) {
            isBaseNode[i] = (resolved.get(idToItem[i]) instanceof BaseResource);
        }

        int[] primaryRecIdx = new int[n];
        java.util.Arrays.fill(primaryRecIdx, -1);

        List<List<Integer>> tmpItemRecipes = new ArrayList<>(n);
        for (int i = 0; i < n; i++) tmpItemRecipes.add(null);

        List<CraftedItem> orderedCrafted = new ArrayList<>();
        List<int[]> recData = new ArrayList<>();

        int recipeIdx = 0;
        for (int i = 0; i < n; i++) {
            List<CraftedItem> recipes = allRecipesMap.getOrDefault(idToItem[i], List.of());
            if (recipes.isEmpty()) continue;

            List<Integer> rIndices = new ArrayList<>(recipes.size());
            for (CraftedItem c : recipes) {
                if (rIndices.isEmpty()) primaryRecIdx[i] = recipeIdx;
                rIndices.add(recipeIdx);

                int outId = idMap.getOrDefault(c.item(), -1);
                recData.add(new int[]{outId, c.outputCount(), c.gridSize()});
                orderedCrafted.add(c);
                recipeIdx++;
            }
            tmpItemRecipes.set(i, rIndices);
        }

        int totalRecipes = recipeIdx;

        int totalEdges = 0;
        int totalOpts = 0;
        for (CraftedItem c : orderedCrafted) {
            for (IngredientEdge edge : c.ingredients()) {
                totalEdges++;
                totalOpts += edge.options().size();
            }
        }

        int[] recOutId = new int[totalRecipes];
        int[] recOutCount = new int[totalRecipes];
        int[] recGridSize = new int[totalRecipes];
        RecipeDisplayId[] recDispId = new RecipeDisplayId[totalRecipes];
        int[] recEdgeStart = new int[totalRecipes];
        int[] recEdgeEnd = new int[totalRecipes];

        int[] edgeCnt = new int[totalEdges];
        int[] edgeOptStart = new int[totalEdges];
        int[] edgeOptEnd = new int[totalEdges];

        int[] optItemId = new int[totalOpts];
        Item[] optItemObj = new Item[totalOpts];

        int ei = 0, oi = 0;
        for (int ri = 0; ri < totalRecipes; ri++) {
            CraftedItem c = orderedCrafted.get(ri);
            int[] rd = recData.get(ri);
            recOutId[ri] = rd[0];
            recOutCount[ri] = rd[1];
            recGridSize[ri] = rd[2];
            recDispId[ri] = c.recipeId();
            recEdgeStart[ri] = ei;
            for (IngredientEdge edge : c.ingredients()) {
                edgeCnt[ei] = edge.count();
                edgeOptStart[ei] = oi;
                for (IngredientOption opt : edge.options()) {
                    optItemId[oi] = idMap.getOrDefault(opt.item(), -1);
                    optItemObj[oi] = opt.item();
                    oi++;
                }
                edgeOptEnd[ei] = oi;
                ei++;
            }
            recEdgeEnd[ri] = ei;
        }

        int totalItemRecs = 0;
        int[] itemRecStart = new int[n];
        int[] itemRecEnd = new int[n];
        for (int i = 0; i < n; i++) {
            List<Integer> r = tmpItemRecipes.get(i);
            if (r == null || r.isEmpty()) continue;
            itemRecStart[i] = totalItemRecs;
            totalItemRecs += r.size();
            itemRecEnd[i] = totalItemRecs;
        }
        int[] itemRecFlat = new int[totalItemRecs];
        int pos = 0;
        for (int i = 0; i < n; i++) {
            List<Integer> r = tmpItemRecipes.get(i);
            if (r == null || r.isEmpty()) continue;
            for (int idx : r) itemRecFlat[pos++] = idx;
        }

        Map<RecipeDisplayId, Integer> dispIdToRecIdx = new HashMap<>(totalRecipes * 2);
        boolean[] recSelfConsuming = new boolean[totalRecipes];
        for (int ri = 0; ri < totalRecipes; ri++) {
            dispIdToRecIdx.put(recDispId[ri], ri);
            int outId = recOutId[ri];
            if (outId >= 0) {
                for (int ei2 = recEdgeStart[ri]; ei2 < recEdgeEnd[ri]; ei2++) {
                    for (int oi2 = edgeOptStart[ei2]; oi2 < edgeOptEnd[ei2]; oi2++) {
                        if (optItemId[oi2] == outId) {
                            recSelfConsuming[ri] = true;
                            break;
                        }
                    }
                    if (recSelfConsuming[ri]) break;
                }
            }
        }

        // --- Static DP-exactness flags (plan §6.3) ---
        @SuppressWarnings("unchecked")
        Set<Item>[] recReverseTargets = new Set[totalRecipes];
        boolean[] recCycleSuspect = new boolean[totalRecipes];
        boolean[] recSharingSuspect = new boolean[totalRecipes];
        for (int ri = 0; ri < totalRecipes; ri++) {
            Item outItem = recOutId[ri] >= 0 ? idToItem[recOutId[ri]] : null;
            Set<Item> targets = outItem != null
                    ? reverseDependencyTargets.getOrDefault(outItem, Set.of())
                    : Set.of();
            recReverseTargets[ri] = targets;
            if (targets.isEmpty()) continue;
            outer: for (int ei2 = recEdgeStart[ri]; ei2 < recEdgeEnd[ri]; ei2++) {
                for (int oi2 = edgeOptStart[ei2]; oi2 < edgeOptEnd[ei2]; oi2++) {
                    if (targets.contains(optItemObj[oi2])) {
                        recCycleSuspect[ri] = true;
                        break outer;
                    }
                }
            }
        }
        for (int ri = 0; ri < totalRecipes; ri++) {
            recSharingSuspect[ri] = hasSharingSuspectEdgeFlat(
                    recEdgeStart, recEdgeEnd, edgeOptStart, edgeOptEnd, optItemId, optItemObj, primaryRecIdx, ri);
        }

        return new GraphFlatData(
                n, idToItem, idMap, isBaseNode, primaryRecIdx,
                itemRecStart, itemRecEnd, itemRecFlat,
                totalRecipes, recOutId, recOutCount, recGridSize, recDispId,
                dispIdToRecIdx, recSelfConsuming,
                recCycleSuspect, recSharingSuspect, recReverseTargets,
                recEdgeStart, recEdgeEnd,
                totalEdges, edgeCnt, edgeOptStart, edgeOptEnd,
                totalOpts, optItemId, optItemObj
        );
    }

    /**
     * Detects the intra-edge option-sharing over-count pattern (MK4 issue #5): an edge
     * with at least one non-base option whose primary recipe's ingredients include
     * another option of the same edge (e.g. oak_log + oak_wood-from-oak_log ⇒ "7 logs"
     * ⇒ 28 planks). Static per recipe — never recomputed.
     */
    private static boolean hasSharingSuspectEdgeFlat(
            int[] recEdgeStart, int[] recEdgeEnd, int[] edgeOptStart, int[] edgeOptEnd,
            int[] optItemId, Item[] optItemObj, int[] primaryRecIdx, int recIdx) {
        for (int ei = recEdgeStart[recIdx]; ei < recEdgeEnd[recIdx]; ei++) {
            int optStart = edgeOptStart[ei], optEnd = edgeOptEnd[ei];
            int optCount = optEnd - optStart;
            if (optCount < 2) continue;
            for (int oi = optStart; oi < optEnd; oi++) {
                int optId = optItemId[oi];
                if (optId < 0) continue;
                int pri = primaryRecIdx[optId];
                if (pri < 0) continue;
                for (int pei = recEdgeStart[pri]; pei < recEdgeEnd[pri]; pei++) {
                    for (int poi = edgeOptStart[pei]; poi < edgeOptEnd[pei]; poi++) {
                        Item ingredient = optItemObj[poi];
                        if (ingredient.equals(optItemObj[oi])) continue;
                        for (int oi2 = optStart; oi2 < optEnd; oi2++) {
                            if (oi2 != oi && optItemObj[oi2].equals(ingredient)) return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    private static CraftedItem buildBestNode(
            Item outputItem, List<RecipeDisplayEntry> entries,
            Map<Item, RecipeNode> resolved, TagIndex tagIndex) {
        CraftedItem best = null;
        for (RecipeDisplayEntry entry : entries) {
            CraftedItem node = buildCraftedItem(outputItem, entry, resolved, tagIndex);
            if (node == null) continue;
            if (best == null || node.depth() < best.depth()) {
                best = node;
            }
        }
        return best;
    }

    private static CraftedItem buildCraftedItem(
            Item outputItem, RecipeDisplayEntry entry,
            Map<Item, RecipeNode> resolved, TagIndex tagIndex) {
        RecipeDisplay display = entry.display();
        List<SlotDisplay> slots = RecipeDisplays.getSlots(display);
        if (slots == null) return null;

        int outputCount = RecipeDisplays.getOutputCount(display, tagIndex);
        if (outputCount <= 0) return null;

        int gridSize = getGridSize(display);

        Map<List<Item>, ConsolidatedIngredient> consolidated = new LinkedHashMap<>();
        for (SlotDisplay slot : slots) {
            if (slot instanceof SlotDisplay.Empty) continue;

            List<IngredientOption> options = buildOptions(slot, resolved, outputItem, tagIndex);
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
            SlotDisplay slot, Map<Item, RecipeNode> resolved, Item excludeItem, TagIndex tagIndex) {
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
            TagKey<Item> tag = RecipeDisplays.getSlotTag(d);
            List<Item> members = tagIndex.members(tag);
            if (members != null) {
                for (Item item : members) {
                    if (item.equals(excludeItem)) continue;
                    RecipeNode node = resolved.getOrDefault(item, new BaseResource(item));
                    options.add(new IngredientOption(item, node));
                }
            }
        } else if (slot instanceof SlotDisplay.Composite d) {
            for (SlotDisplay sub : d.contents()) {
                options.addAll(buildOptions(sub, resolved, excludeItem, tagIndex));
            }
        } else if (slot instanceof SlotDisplay.WithRemainder d) {
            options.addAll(buildOptions(d.input(), resolved, excludeItem, tagIndex));
        }

        return options;
    }

    private static List<Set<Item>> getConsolidatedEdges(RecipeDisplay display, Item outputItem, TagIndex tagIndex) {
        List<SlotDisplay> slots = RecipeDisplays.getSlots(display);
        if (slots == null) return null;

        Map<List<Item>, Set<Item>> consolidated = new LinkedHashMap<>();
        for (SlotDisplay slot : slots) {
            if (slot instanceof SlotDisplay.Empty) continue;

            Set<Item> options = new LinkedHashSet<>();
            collectItems(slot, options, tagIndex);
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

    public static void collectItems(SlotDisplay slot, Set<Item> items, TagIndex tagIndex) {
        if (slot instanceof SlotDisplay.ItemSlotDisplay d) {
            items.add(d.item().value());
        } else if (slot instanceof SlotDisplay.ItemStackSlotDisplay d) {
            items.add(d.stack().item().value());
        } else if (slot instanceof SlotDisplay.TagSlotDisplay d) {
            List<Item> members = tagIndex.members(RecipeDisplays.getSlotTag(d));
            if (members != null) items.addAll(members);
        } else if (slot instanceof SlotDisplay.Composite d) {
            for (SlotDisplay sub : d.contents()) collectItems(sub, items, tagIndex);
        } else if (slot instanceof SlotDisplay.WithRemainder d) {
            collectItems(d.input(), items, tagIndex);
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
