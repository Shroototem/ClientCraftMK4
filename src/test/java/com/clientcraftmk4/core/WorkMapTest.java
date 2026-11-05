package com.clientcraftmk4.core;

import net.minecraft.SharedConstants;
import net.minecraft.client.gui.screens.recipebook.RecipeCollection;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.RecipeBookCategories;
import net.minecraft.world.item.crafting.display.RecipeDisplayEntry;
import net.minecraft.world.item.crafting.display.RecipeDisplayId;
import net.minecraft.world.item.crafting.display.ShapelessCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.SlotDisplay;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Journal/rollback mechanics of {@link WorkMap}. Most tests use arbitrary graph slots;
 * the {@code restoreBase} tests fill from a real two-ingredient recipe graph, so the
 * flat ids come from the same fill path the resolver uses.
 */
class WorkMapTest {

    private static RecipeGraph graph;

    @BeforeAll
    static void setUp() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        // A headless test never loads the vanilla datapack, so item holders have no
        // component map bound; the graph build only reads item identity and counts.
        for (Item item : List.of(Items.OAK_PLANKS, Items.BIRCH_PLANKS, Items.STICK)) {
            ((Holder.Reference<Item>) item.builtInRegistryHolder()).bindComponents(DataComponentMap.EMPTY);
        }
        // oak + birch -> stick: the graph holds both planks (base) and the stick (output).
        RecipeDisplayEntry stick = new RecipeDisplayEntry(new RecipeDisplayId(1),
                new ShapelessCraftingRecipeDisplay(
                        List.of(item(Items.OAK_PLANKS), item(Items.BIRCH_PLANKS)),
                        item(Items.STICK), SlotDisplay.Empty.INSTANCE),
                OptionalInt.empty(), RecipeBookCategories.CRAFTING_MISC, Optional.empty());
        TagIndex tags = new TagIndex();
        RecipeIndex index = RecipeIndex.build(List.of(new RecipeCollection(List.of(stick))), tags);
        graph = GraphBuilder.build(index, tags);
    }

    @Test
    void addConsumeProduceTrackCounts() {
        WorkMap wm = new WorkMap(16);
        wm.add(5, 3);
        assertEquals(3, wm.get(5));
        wm.consume(5, 2);
        assertEquals(1, wm.get(5));
        wm.produce(5, 4);
        assertEquals(5, wm.get(5));
    }

    @Test
    void zeroDeltaDoesNotJournalOrChangeCounts() {
        WorkMap wm = new WorkMap(16);
        int mark = wm.mark();
        wm.add(1, 0);
        wm.consume(2, 0);
        wm.produce(3, 0);
        assertEquals(mark, wm.mark());
        assertEquals(0, wm.get(1));
    }

    @Test
    void rollbackRestoresExactCounts() {
        WorkMap wm = new WorkMap(16);
        wm.add(1, 10);
        wm.add(2, 20);
        int mark = wm.mark();
        wm.consume(1, 7);
        wm.consume(2, 25);
        wm.produce(3, 9);
        wm.rollbackTo(mark);
        assertEquals(10, wm.get(1));
        assertEquals(20, wm.get(2));
        assertEquals(0, wm.get(3));
    }

    @Test
    void nestedMarksRollbackInReverseOrder() {
        WorkMap wm = new WorkMap(16);
        wm.add(1, 100);
        int outer = wm.mark();
        wm.consume(1, 30);
        int inner = wm.mark();
        wm.consume(1, 45);
        wm.rollbackTo(inner);
        assertEquals(70, wm.get(1));
        wm.rollbackTo(outer);
        assertEquals(100, wm.get(1));
    }

    @Test
    void overConsumeGoesNegativeAndRollbackRecovers() {
        WorkMap wm = new WorkMap(16);
        wm.add(4, 2);
        int mark = wm.mark();
        wm.consume(4, 5);
        assertEquals(-3, wm.get(4));
        wm.rollbackTo(mark);
        assertEquals(2, wm.get(4));
    }

    @Test
    void presentListTracksEverPositiveIdsInInsertionOrder() {
        WorkMap wm = new WorkMap(16);
        wm.add(7, 1);
        wm.add(3, 2);
        wm.add(7, 1);
        assertEquals(2, wm.presentSize());
        assertEquals(7, wm.presentIdAt(0));
        assertEquals(3, wm.presentIdAt(1));

        int mark = wm.mark();
        wm.produce(9, 5);
        assertEquals(3, wm.presentSize());
        assertEquals(9, wm.presentIdAt(2));
        wm.rollbackTo(mark);
        assertEquals(0, wm.get(9));
        assertEquals(3, wm.presentSize(), "present list keeps ids that ever held a positive count");
    }

    @Test
    void undoLogGrowsBeyondInitialCapacity() {
        WorkMap wm = new WorkMap(16);
        for (int i = 0; i < 500; i++) {
            wm.add(i % 16, 1);
            wm.consume(i % 16, 1);
        }
        for (int id = 0; id < 16; id++) {
            assertEquals(0, wm.get(id), "id " + id + " must return to zero");
        }
        assertTrue(wm.presentSize() > 0);
    }

    @Test
    void outOfRangeAndZeroIdsAreIgnored() {
        WorkMap wm = new WorkMap(16);
        int mark = wm.mark();
        wm.add(-1, 5);
        wm.add(16, 5);
        assertEquals(mark, wm.mark());
        assertEquals(0, wm.get(-1));
        assertEquals(0, wm.get(16));
    }

    @Test
    void capacityReportsConstructorValue() {
        assertEquals(64, new WorkMap(64).capacity());
        assertEquals(16, new WorkMap(1).capacity(), "capacity is floored at 16");
    }

    @Test
    void ensureCapacityGrowsInsteadOfDroppingState() {
        WorkMap wm = new WorkMap(16);
        wm.add(5, 3);
        wm.ensureCapacity(1112);
        assertTrue(wm.capacity() >= 1112);
        assertEquals(3, wm.get(5), "existing counts must survive growth");
        // High ids usable after growth (old resetTo silently no-op'd when undersized).
        wm.add(1000, 7);
        assertEquals(7, wm.get(1000));
        int mark = wm.mark();
        wm.consume(1000, 2);
        assertEquals(5, wm.get(1000));
        wm.rollbackTo(mark);
        assertEquals(7, wm.get(1000));
    }

    @Test
    void presentListSurvivesGrowth() {
        WorkMap wm = new WorkMap(16);
        wm.add(3, 1);
        wm.ensureCapacity(512);
        wm.add(400, 2);
        assertEquals(1, wm.get(3));
        assertEquals(2, wm.get(400));
        assertTrue(wm.presentSize() >= 2);
    }

    @Test
    void restoreBaseMatchesFreshFillAndIsReusable() {
        Map<Item, Integer> inventory = Map.of(Items.OAK_PLANKS, 3);
        WorkMap fresh = WorkMap.from(inventory, graph);
        WorkMap wm = WorkMap.from(inventory, graph);
        for (int round = 0; round < 2; round++) {
            wm.consume(id(Items.OAK_PLANKS), 2);
            // Birch and stick were not in the inventory at the fill, so they are appended.
            wm.produce(id(Items.BIRCH_PLANKS), 5);
            wm.produce(id(Items.STICK), 4);
            assertEquals(1, wm.get(id(Items.OAK_PLANKS)));
            assertEquals(3, wm.presentSize());

            wm.restoreBase();
            assertSameState(fresh, wm);
        }
    }

    @Test
    void restoreBaseRewindsJournalToStart() {
        WorkMap wm = WorkMap.from(Map.of(Items.OAK_PLANKS, 3), graph);
        wm.consume(id(Items.OAK_PLANKS), 1);
        wm.produce(id(Items.STICK), 2);
        assertTrue(wm.mark() > 0);

        wm.restoreBase();
        assertEquals(0, wm.mark());
    }

    // --- helpers -------------------------------------------------------------

    private static int id(Item item) {
        return graph.id(item);
    }

    private static void assertSameState(WorkMap expected, WorkMap actual) {
        assertEquals(expected.presentSize(), actual.presentSize(), "present size");
        for (int i = 0; i < expected.presentSize(); i++) {
            assertEquals(expected.presentIdAt(i), actual.presentIdAt(i), "present id at " + i);
        }
        for (int id = 0; id < graph.flat().n(); id++) {
            assertEquals(expected.get(id), actual.get(id), "count of id " + id);
        }
    }

    private static SlotDisplay item(Item item) {
        return new SlotDisplay.ItemSlotDisplay(item.builtInRegistryHolder());
    }
}
