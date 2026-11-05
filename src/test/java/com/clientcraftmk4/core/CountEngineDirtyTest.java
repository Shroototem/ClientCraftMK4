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
import net.minecraft.world.item.crafting.display.ShapedCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.SlotDisplay;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.BitSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link CountEngine#computeDirty} must treat container changes like inventory changes:
 * a container that empties dirties the recipes that used its items, even when container
 * search is off (the old diff was skipped then, leaving stale container-craftable flags).
 */
class CountEngineDirtyTest {

    private static RecipeGraph graph;
    private static TagIndex tags;
    private static RecipeDisplayEntry chest;

    @BeforeAll
    static void setUp() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        // A headless test never loads the vanilla datapack, so item holders have no
        // component map bound. The graph build only reads item identity and counts.
        for (Item item : List.of(Items.OAK_PLANKS, Items.STICK, Items.CHEST)) {
            ((Holder.Reference<Item>) item.builtInRegistryHolder()).bindComponents(DataComponentMap.EMPTY);
        }
        // Chest from 8 oak planks, as in the vanilla shape (centre slot empty).
        SlotDisplay planks = new SlotDisplay.ItemSlotDisplay(Items.OAK_PLANKS.builtInRegistryHolder());
        SlotDisplay empty = SlotDisplay.Empty.INSTANCE;
        chest = new RecipeDisplayEntry(new RecipeDisplayId(1),
                new ShapedCraftingRecipeDisplay(3, 3,
                        List.of(planks, planks, planks,
                                planks, empty, planks,
                                planks, planks, planks),
                        new SlotDisplay.ItemSlotDisplay(Items.CHEST.builtInRegistryHolder()), empty),
                OptionalInt.empty(), RecipeBookCategories.CRAFTING_MISC, Optional.empty());
        tags = new TagIndex();
        RecipeIndex index = RecipeIndex.build(List.of(new RecipeCollection(List.of(chest))), tags);
        graph = GraphBuilder.build(index, tags);
    }

    @Test
    void emptiedContainerDirtiesItsRecipes() {
        InventorySnapshot prev = new InventorySnapshot(Map.of(Items.STICK, 1), Map.of(Items.OAK_PLANKS, 8), 1);
        InventorySnapshot next = new InventorySnapshot(Map.of(Items.STICK, 1), Map.of(), 2);

        BitSet dirty = CountEngine.computeDirty(graph.flat(), tags, prev, next, Set.of());
        int chestIdx = graph.flat().dispIdToRecIdx().get(chest.id());
        assertTrue(dirty.get(chestIdx), "the chest recipe used the container's oak planks");
    }

    @Test
    void identicalSnapshotsAreClean() {
        InventorySnapshot prev = new InventorySnapshot(Map.of(Items.STICK, 1), Map.of(Items.OAK_PLANKS, 8), 1);
        InventorySnapshot same = new InventorySnapshot(Map.of(Items.STICK, 1), Map.of(Items.OAK_PLANKS, 8), 2);

        assertTrue(CountEngine.computeDirty(graph.flat(), tags, prev, same, Set.of()).isEmpty());
    }
}
