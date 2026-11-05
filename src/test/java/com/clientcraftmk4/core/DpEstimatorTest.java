package com.clientcraftmk4.core;

import com.clientcraftmk4.core.RecipeGraph.GraphFlatData;
import com.clientcraftmk4.core.algorithms.DpEstimator;
import com.clientcraftmk4.core.resolver.QtyResolveContext;
import net.minecraft.SharedConstants;
import net.minecraft.client.gui.screens.recipebook.RecipeCollection;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.RecipeBookCategories;
import net.minecraft.world.item.crafting.display.RecipeDisplay;
import net.minecraft.world.item.crafting.display.RecipeDisplayEntry;
import net.minecraft.world.item.crafting.display.RecipeDisplayId;
import net.minecraft.world.item.crafting.display.ShapedCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.ShapelessCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.SlotDisplay;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Additive-source detection in {@link DpEstimator}: a stick can come from planks or from
 * bamboo, so two stocked stick recipes add up, while the estimate only takes the best one.
 *
 * <p>Fake recipe set: stick from 1 oak planks, stick from 1 bamboo, and a ladder from a
 * 3x3 display with 7 stick slots (the outputs are item slots, so every output is 1).
 */
class DpEstimatorTest {

    private static RecipeIndex index;
    private static TagIndex tags;
    private static RecipeGraph graph;
    private static RecipeDisplayEntry stickFromPlanks;
    private static RecipeDisplayEntry stickFromBamboo;
    private static RecipeDisplayEntry ladder;

    @BeforeAll
    static void setUp() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        // A headless test never loads the vanilla datapack, so item holders have no
        // component map bound. The graph build only reads item identity and counts.
        for (Item item : List.of(Items.OAK_PLANKS, Items.BAMBOO, Items.STICK, Items.LADDER)) {
            ((Holder.Reference<Item>) item.builtInRegistryHolder()).bindComponents(DataComponentMap.EMPTY);
        }
        // Built after bootstrap: the display entries address real registry items.
        stickFromPlanks = entry(1,
                new ShapelessCraftingRecipeDisplay(List.of(item(Items.OAK_PLANKS)), item(Items.STICK), empty()));
        stickFromBamboo = entry(2,
                new ShapelessCraftingRecipeDisplay(List.of(item(Items.BAMBOO)), item(Items.STICK), empty()));
        ladder = entry(3,
                new ShapedCraftingRecipeDisplay(3, 3,
                        List.of(stick(), empty(), stick(),
                                stick(), stick(), stick(),
                                stick(), empty(), stick()),
                        item(Items.LADDER), empty()));
        tags = new TagIndex();
        index = RecipeIndex.build(List.of(new RecipeCollection(
                List.of(stickFromPlanks, stickFromBamboo, ladder))), tags);
        graph = GraphBuilder.build(index, tags);
    }

    /**
     * 8 planks + 6 bamboo: two stocked stick sources add up to 14 sticks, two ladders.
     * The planks are a multiple of the 2-per-slot draw, so a qty-2 resolve can use them
     * whole; 7 + 7 needs {@code setSplitDeficits(true)} (see the test below).
     */
    @Test
    void stockedAlternativesAreFlaggedAndEstimateIsLow() {
        Map<Item, Integer> inv = Map.of(Items.OAK_PLANKS, 8, Items.BAMBOO, 6);
        GraphFlatData flat = graph.flat();
        int ladderIdx = recIdx(flat, ladder);

        DpEstimator.Estimate estimate = DpEstimator.estimate(graph, inv, Map.of(), 3, Constants.MAX_REPEATS);
        assertEquals(1, estimate.counts()[ladderIdx], "the estimate takes only the best stick source");
        assertTrue(DpEstimator.dependsOnAdditive(flat, ladderIdx, estimate.additiveItems()));

        QtyResolveContext qty = QtyResolveContext.of(index, tags, graph, 3);
        assertTrue(qty.resolveQty(ladder, WorkMap.from(inv, graph), 2, null, new HashSet<>(), 0, null),
                "the real true count is 2 ladders");
        assertFalse(qty.resolveQty(ladder, WorkMap.from(inv, graph), 3, null, new HashSet<>(), 0, null),
                "14 sticks cannot make 3 ladders");
    }

    /** One stocked source only: the estimate is exact and nothing is flagged. */
    @Test
    void singleStockedSourceIsNotAdditive() {
        Map<Item, Integer> inv = Map.of(Items.OAK_PLANKS, 14);
        GraphFlatData flat = graph.flat();

        DpEstimator.Estimate estimate = DpEstimator.estimate(graph, inv, Map.of(), 3, Constants.MAX_REPEATS);
        assertEquals(2, estimate.counts()[recIdx(flat, ladder)]);
        assertNull(estimate.additiveItems());
    }

    /**
     * 7 sticks already in hand: the planks recipe adds 7 more, the bamboo recipe adds
     * nothing (no bamboo), so only one source contributes and nothing is flagged.
     */
    @Test
    void stockedIntermediateWithOneContributingSourceIsNotAdditive() {
        Map<Item, Integer> inv = Map.of(Items.OAK_PLANKS, 7, Items.STICK, 7);
        GraphFlatData flat = graph.flat();

        DpEstimator.Estimate estimate = DpEstimator.estimate(graph, inv, Map.of(), 3, Constants.MAX_REPEATS);
        assertEquals(2, estimate.counts()[recIdx(flat, ladder)]);
        assertNull(estimate.additiveItems());
    }

    /**
     * 7 planks + 7 bamboo make 14 sticks, enough for 2 ladders. Each stick slot needs 2 sticks,
     * so three slots drain each source to 1, and the last slot's 2 sticks come from no single recipe.
     */
    @Test
    void splitDeficitCombinesStockedSourcesWhenEnabled() {
        Map<Item, Integer> inv = Map.of(Items.OAK_PLANKS, 7, Items.BAMBOO, 7);

        assertFalse(newQty(false).resolveQty(ladder, WorkMap.from(inv, graph), 2, null, new HashSet<>(), 0, null),
                "without split deficits no single recipe covers the last slot");

        WorkMap work = WorkMap.from(inv, graph);
        assertTrue(newQty(true).resolveQty(ladder, work, 2, null, new HashSet<>(), 0, null),
                "the real true count is 2 ladders");
        assertEquals(0, work.get(graph.id(Items.OAK_PLANKS)));
        assertEquals(0, work.get(graph.id(Items.BAMBOO)));

        assertFalse(newQty(true).resolveQty(ladder, WorkMap.from(inv, graph), 3, null, new HashSet<>(), 0, null),
                "14 sticks cannot make 3 ladders");
    }

    private static int recIdx(GraphFlatData flat, RecipeDisplayEntry entry) {
        return flat.dispIdToRecIdx().get(entry.id());
    }

    // --- helpers -------------------------------------------------------------

    private static RecipeDisplayEntry entry(int id, RecipeDisplay display) {
        return new RecipeDisplayEntry(new RecipeDisplayId(id), display, OptionalInt.empty(),
                RecipeBookCategories.CRAFTING_MISC, Optional.empty());
    }

    private static SlotDisplay item(Item item) {
        return new SlotDisplay.ItemSlotDisplay(item.builtInRegistryHolder());
    }

    private static SlotDisplay stick() {
        return item(Items.STICK);
    }

    private static SlotDisplay empty() {
        return SlotDisplay.Empty.INSTANCE;
    }

    private static QtyResolveContext newQty(boolean splitDeficits) {
        QtyResolveContext ctx = QtyResolveContext.of(index, tags, graph, 3);
        ctx.setSplitDeficits(splitDeficits);
        return ctx;
    }
}
