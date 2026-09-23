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
 *   <li>{@code recCrossEdgeShared} — items shared across distinct edges of one recipe;</li>
 *   <li>{@code recReverseTargets} — per-recipe reverse-dependency target set.</li>
 * </ul>
 */
public final class GraphBuilder {
    private GraphBuilder() {}

    /** Registry-id comparator shared by the key sorts (comparingInt allocates per call). */
    private static final Comparator<Item> BY_REGISTRY_ID = Comparator.comparingInt(Item::getId);

    public static RecipeGraph build(RecipeIndex index, TagIndex tagIndex) {
        // The old code copied the whole index into recipesByOutput first; the index lists
        // are never mutated downstream, so read them directly.
        int outputCount = index.outputs().size();
        int mapCap = (int) (outputCount / 0.75f) + 16;

        Set<Item> allIngredientItems = new HashSet<>(4096);
        for (Item out : index.outputs()) {
            for (RecipeDisplayEntry entry : index.get(out)) {
                List<SlotDisplay> slots = RecipeDisplays.getSlots(entry);
                if (slots == null) continue;
                for (SlotDisplay slot : slots) {
                    collectItems(slot, allIngredientItems, tagIndex);
                }
            }
        }

        Map<Item, RecipeNode> resolved = new HashMap<>(mapCap);
        Set<Item> baseResources = new HashSet<>(mapCap);

        for (Item item : allIngredientItems) {
            if (index.get(item) == null) {
                resolved.put(item, new BaseResource(item));
                baseResources.add(item);
            }
        }

        Map<Item, Integer> inDegree = new HashMap<>(mapCap);
        Map<Item, List<Set<Item>>> edgeDeps = new HashMap<>(mapCap);
        Map<Item, boolean[]> edgeResolved = new HashMap<>(mapCap);
        Map<Item, Set<Item>> optionToParents = new HashMap<>(mapCap);

        for (Item outputItem : index.outputs()) {
            List<Set<Item>> bestEdges = null;
            int bestUnresolved = Integer.MAX_VALUE;

            for (RecipeDisplayEntry entry : index.get(outputItem)) {
                List<Set<Item>> edges = getConsolidatedEdges(entry, outputItem, tagIndex);
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
                        optionToParents.computeIfAbsent(option, k -> new HashSet<>(4)).add(outputItem);
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

            CraftedItem bestNode = buildBestNode(item, index.get(item), resolved, tagIndex);
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

        for (Item item : index.outputs()) {
            if (!resolved.containsKey(item)) {
                resolved.put(item, new BaseResource(item));
                baseResources.add(item);
                topologicalOrder.add(item);
            }
        }

        Map<Item, List<CraftedItem>> allRecipesMap = new HashMap<>(mapCap);
        for (Item outputItem : index.outputs()) {
            List<CraftedItem> nodes = new ArrayList<>();
            for (RecipeDisplayEntry entry : index.get(outputItem)) {
                CraftedItem node = buildCraftedItem(outputItem, entry, resolved, tagIndex);
                if (node != null) nodes.add(node);
            }
            if (!nodes.isEmpty()) {
                allRecipesMap.put(outputItem, nodes);
            }
        }

        Map<Item, Set<Item>> dependents = new HashMap<>(mapCap);
        Map<Item, Set<Item>> reverseDependencyTargets = new HashMap<>(mapCap);
        for (Map.Entry<Item, List<CraftedItem>> e : allRecipesMap.entrySet()) {
            Item outputItem = e.getKey();
            for (CraftedItem crafted : e.getValue()) {
                for (IngredientEdge edge : crafted.ingredients()) {
                    for (IngredientOption option : edge.options()) {
                        dependents.computeIfAbsent(option.item(), k -> new HashSet<>(4)).add(crafted.item());
                        reverseDependencyTargets.computeIfAbsent(option.item(), k -> new HashSet<>(4)).add(outputItem);
                    }
                }
            }
        }

        GraphFlatData flat = buildFlatData(topologicalOrder, resolved, allRecipesMap, reverseDependencyTargets,
                tagIndex, dependents, index);
        return new RecipeGraph(resolved, allRecipesMap, dependents, topologicalOrder, reverseDependencyTargets, flat);
    }

    private static GraphFlatData buildFlatData(
            List<Item> topo, Map<Item, RecipeNode> resolved, Map<Item, List<CraftedItem>> allRecipesMap,
            Map<Item, Set<Item>> reverseDependencyTargets, TagIndex tagIndex,
            Map<Item, Set<Item>> dependents, RecipeIndex index) {

        int n = topo.size();
        IdentityHashMap<Item, Integer> idMap = new IdentityHashMap<>((int) (n / 0.75f) + 1);
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
        // NOTE: the old int[3]-per-recipe recData list is gone — the ri loop below reads
        // outId/outputCount/gridSize straight off the CraftedItem (same values, no garbage).

        int recipeIdx = 0;
        for (int i = 0; i < n; i++) {
            List<CraftedItem> recipes = allRecipesMap.getOrDefault(idToItem[i], List.of());
            if (recipes.isEmpty()) continue;

            List<Integer> rIndices = new ArrayList<>(recipes.size());
            for (CraftedItem c : recipes) {
                if (rIndices.isEmpty()) primaryRecIdx[i] = recipeIdx;
                rIndices.add(recipeIdx);

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
            recOutId[ri] = idMap.getOrDefault(c.item(), -1);
            recOutCount[ri] = c.outputCount();
            recGridSize[ri] = c.gridSize();
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

        Map<RecipeDisplayId, Integer> dispIdToRecIdx = new HashMap<>((int) (totalRecipes / 0.75f) + 1);
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
        boolean[] recCrossEdgeShared = new boolean[totalRecipes];
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
            recCrossEdgeShared[ri] = hasCrossEdgeSharedOptionFlat(
                    recEdgeStart, recEdgeEnd, edgeOptStart, edgeOptEnd, optItemObj, ri);
        }

        installFlatTagData(tagIndex, idMap, n);

        return new GraphFlatData(
                n, idToItem, idMap, isBaseNode, primaryRecIdx,
                itemRecStart, itemRecEnd, itemRecFlat,
                totalRecipes, recOutId, recOutCount, recGridSize, recDispId,
                dispIdToRecIdx, recSelfConsuming,
                recCycleSuspect, recSharingSuspect, recCrossEdgeShared, recReverseTargets,
                recEdgeStart, recEdgeEnd,
                totalEdges, edgeCnt, edgeOptStart, edgeOptEnd,
                totalOpts, optItemId, optItemObj,
                buildDependentItems(dependents, idMap, n),
                buildTagRecIdx(index, dispIdToRecIdx)
        );
    }

    /**
     * Item-level dependents as flat id arrays (for the incremental dirty worklist): parent
     * output items per flat item id. Order within an array is irrelevant (fixpoint sets).
     */
    private static int[][] buildDependentItems(Map<Item, Set<Item>> dependents,
                                               IdentityHashMap<Item, Integer> idMap, int n) {
        int[][] out = new int[n][];
        for (int i = 0; i < n; i++) out[i] = new int[0];
        for (Map.Entry<Item, Set<Item>> e : dependents.entrySet()) {
            Integer id = idMap.get(e.getKey());
            if (id == null) continue;
            Set<Item> parents = e.getValue();
            int[] arr = new int[parents.size()];
            int q = 0;
            for (Item p : parents) {
                Integer pid = idMap.get(p);
                if (pid != null) arr[q++] = pid;
            }
            out[id] = q == arr.length ? arr : Arrays.copyOf(arr, q);
        }
        return out;
    }

    /**
     * Tag → recipe indices using that tag (for tag-propagation of dirtiness): translated from
     * the per-entry tag lists via the display-id map. Insertion (book) order; union semantics.
     */
    private static Map<TagKey<Item>, int[]> buildTagRecIdx(RecipeIndex index,
                                                           Map<RecipeDisplayId, Integer> dispIdToRecIdx) {
        Map<TagKey<Item>, List<Integer>> acc = new HashMap<>();
        for (Map.Entry<RecipeDisplayId, List<TagKey<Item>>> e : index.entryTags().entrySet()) {
            Integer ri = dispIdToRecIdx.get(e.getKey());
            if (ri == null) continue;
            for (TagKey<Item> tag : e.getValue()) {
                acc.computeIfAbsent(tag, k -> new ArrayList<>()).add(ri);
            }
        }
        Map<TagKey<Item>, int[]> out = new HashMap<>((int) (acc.size() / 0.75f) + 1);
        for (Map.Entry<TagKey<Item>, List<Integer>> e : acc.entrySet()) {
            List<Integer> l = e.getValue();
            int[] a = new int[l.size()];
            for (int i = 0; i < l.size(); i++) a[i] = l.get(i);
            out.put(e.getKey(), a);
        }
        return out;
    }

    /**
     * Builds the id-indexed tag structures (see {@link TagIndex#setFlatTagData}): one
     * pass over the cached registry members per known tag. Registry
     * {@code holder.is(tag)} checks never run on hot paths again after this.
     */
    @SuppressWarnings("unchecked")
    private static void installFlatTagData(TagIndex tagIndex,
                                           IdentityHashMap<Item, Integer> idMap, int n) {
        Set<TagKey<Item>> known = tagIndex.knownTags();
        Map<TagKey<Item>, BitSet> bitsByTag = new HashMap<>((int) (known.size() / 0.75f) + 1);
        List<Set<TagKey<Item>>> fill = new ArrayList<>(n);
        for (int i = 0; i < n; i++) fill.add(null);
        for (TagKey<Item> tag : known) {
            if (tag == null) continue;
            List<Item> members = tagIndex.members(tag);
            if (members == null || members.isEmpty()) continue;
            BitSet bits = new BitSet(n);
            for (Item m : members) {
                Integer id = idMap.get(m);
                if (id == null) continue;
                bits.set(id);
                Set<TagKey<Item>> s = fill.get(id);
                if (s == null) {
                    s = new HashSet<>(4);
                    fill.set(id, s);
                }
                s.add(tag);
            }
            bitsByTag.put(tag, bits);
        }
        Set<TagKey<Item>>[] tagsByFlatId = new Set[n];
        for (int i = 0; i < n; i++) {
            Set<TagKey<Item>> s = fill.get(i);
            tagsByFlatId[i] = s != null ? Collections.unmodifiableSet(s) : Set.of();
        }
        tagIndex.setFlatTagData(idMap, tagsByFlatId, bitsByTag);
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
            // One set per edge instead of an O(opts) inner scan per ingredient probe.
            Set<Item> edgeSet = new HashSet<>((int) (optCount / 0.75f) + 1);
            for (int oi = optStart; oi < optEnd; oi++) edgeSet.add(optItemObj[oi]);
            for (int oi = optStart; oi < optEnd; oi++) {
                int optId = optItemId[oi];
                if (optId < 0) continue;
                int pri = primaryRecIdx[optId];
                if (pri < 0) continue;
                for (int pei = recEdgeStart[pri]; pei < recEdgeEnd[pri]; pei++) {
                    for (int poi = edgeOptStart[pei]; poi < edgeOptEnd[pei]; poi++) {
                        Item ingredient = optItemObj[poi];
                        if (!ingredient.equals(optItemObj[oi]) && edgeSet.contains(ingredient)) return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * Detects the cross-edge option-sharing over-count pattern: an item appearing as an
     * option in two distinct edges of one recipe. Both edges then draw from the same
     * physical pool, so per-edge independent counting (tree DP and directCountFlat)
     * double-counts it. Static per recipe — never recomputed.
     */
    private static boolean hasCrossEdgeSharedOptionFlat(
            int[] recEdgeStart, int[] recEdgeEnd, int[] edgeOptStart, int[] edgeOptEnd,
            Item[] optItemObj, int recIdx) {
        // Items from earlier edges only: within-edge repeats must NOT trigger (the old
        // map distinguished them via prev != ei). No per-recipe HashMap alloc.
        Set<Item> seenInEarlierEdges = null;
        for (int ei = recEdgeStart[recIdx]; ei < recEdgeEnd[recIdx]; ei++) {
            int optStart = edgeOptStart[ei], optEnd = edgeOptEnd[ei];
            if (seenInEarlierEdges != null) {
                for (int oi = optStart; oi < optEnd; oi++) {
                    if (seenInEarlierEdges.contains(optItemObj[oi])) return true;
                }
            }
            if (seenInEarlierEdges == null) {
                seenInEarlierEdges = new HashSet<>((int) ((optEnd - optStart) / 0.75f) + 1);
            }
            for (int oi = optStart; oi < optEnd; oi++) seenInEarlierEdges.add(optItemObj[oi]);
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
        List<SlotDisplay> slots = RecipeDisplays.getSlots(entry);
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

    /** getOrDefault with an eager new BaseResource allocated on every hit — use a plain get. */
    private static RecipeNode nodeFor(Map<Item, RecipeNode> resolved, Item item) {
        RecipeNode node = resolved.get(item);
        return node != null ? node : new BaseResource(item);
    }

    private static List<IngredientOption> buildOptions(
            SlotDisplay slot, Map<Item, RecipeNode> resolved, Item excludeItem, TagIndex tagIndex) {
        List<IngredientOption> options = new ArrayList<>();

        if (slot instanceof SlotDisplay.ItemSlotDisplay d) {
            Item item = d.item().value();
            if (!item.equals(excludeItem)) {
                options.add(new IngredientOption(item, nodeFor(resolved, item)));
            }
        } else if (slot instanceof SlotDisplay.ItemStackSlotDisplay d) {
            Item item = d.stack().item().value();
            if (!item.equals(excludeItem)) {
                options.add(new IngredientOption(item, nodeFor(resolved, item)));
            }
        } else if (slot instanceof SlotDisplay.TagSlotDisplay d) {
            TagKey<Item> tag = RecipeDisplays.getSlotTag(d);
            List<Item> members = tagIndex.members(tag);
            if (members != null) {
                for (Item item : members) {
                    if (item.equals(excludeItem)) continue;
                    options.add(new IngredientOption(item, nodeFor(resolved, item)));
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

    private static List<Set<Item>> getConsolidatedEdges(RecipeDisplayEntry entry, Item outputItem, TagIndex tagIndex) {
        List<SlotDisplay> slots = RecipeDisplays.getSlots(entry);
        if (slots == null) return null;

        Map<List<Item>, Set<Item>> consolidated = new LinkedHashMap<>();
        for (SlotDisplay slot : slots) {
            if (slot instanceof SlotDisplay.Empty) continue;

            // Fast path: single-item slots are the common case — no set/sort needed.
            Item single = singleDirectItem(slot);
            Set<Item> options;
            if (single != null) {
                if (single.equals(outputItem)) return null;
                options = Set.of(single);
            } else {
                options = new LinkedHashSet<>();
                collectItems(slot, options, tagIndex);
                options.remove(outputItem);
                if (options.isEmpty()) return null;
            }

            List<Item> key = itemSetKey(options);
            consolidated.merge(key, options, (a, b) -> a);
        }

        return new ArrayList<>(consolidated.values());
    }

    /** The directly-addressed item of Item/ItemStack slots (through WithRemainder), else null. */
    private static Item singleDirectItem(SlotDisplay slot) {
        if (slot instanceof SlotDisplay.ItemSlotDisplay d) return d.item().value();
        if (slot instanceof SlotDisplay.ItemStackSlotDisplay d) return d.stack().item().value();
        if (slot instanceof SlotDisplay.WithRemainder r) return singleDirectItem(r.input());
        return null;
    }

    private static List<Item> optionsKey(List<IngredientOption> options) {
        if (options.size() == 1) return List.of(options.getFirst().item());
        List<Item> items = new ArrayList<>(options.size());
        for (IngredientOption opt : options) items.add(opt.item());
        items.sort(BY_REGISTRY_ID);
        return items;
    }

    private static List<Item> itemSetKey(Set<Item> items) {
        if (items.size() == 1) return List.of(items.iterator().next());
        List<Item> sorted = new ArrayList<>(items);
        sorted.sort(BY_REGISTRY_ID);
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
