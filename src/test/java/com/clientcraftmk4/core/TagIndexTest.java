package com.clientcraftmk4.core;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Publication rules of {@link TagIndex#ensureFresh}: key-set guarded, newest generation wins. */
class TagIndexTest {

    @BeforeAll
    static void setUp() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void emptySnapshotKeepsCurrentTags() {
        TagIndex tags = new TagIndex();
        RecipeIndex index = RecipeIndex.build(List.of(), tags);
        TagIndex.InventoryTags before = tags.currentTags();
        tags.ensureFresh(InventorySnapshot.EMPTY, index);
        assertSame(before, tags.currentTags());
    }

    @Test
    void newItemKeysPublishNewTags() {
        TagIndex tags = new TagIndex();
        RecipeIndex index = RecipeIndex.build(List.of(), tags);
        TagIndex.InventoryTags before = tags.currentTags();
        tags.ensureFresh(snapshot(1, Map.of(Items.OAK_PLANKS, 3)), index);
        assertNotSame(before, tags.currentTags());
    }

    @Test
    void sameKeySetAtNewerGenerationPublishesNothing() {
        TagIndex tags = new TagIndex();
        RecipeIndex index = RecipeIndex.build(List.of(), tags);
        tags.ensureFresh(snapshot(1, Map.of(Items.OAK_PLANKS, 3)), index);
        TagIndex.InventoryTags published = tags.currentTags();
        tags.ensureFresh(snapshot(2, Map.of(Items.OAK_PLANKS, 7)), index);
        assertSame(published, tags.currentTags());
    }

    @Test
    void olderSnapshotWithDifferentKeysDoesNotReplaceNewerData() {
        TagIndex tags = new TagIndex();
        RecipeIndex index = RecipeIndex.build(List.of(), tags);
        tags.ensureFresh(snapshot(5, Map.of(Items.OAK_PLANKS, 3)), index);
        TagIndex.InventoryTags published = tags.currentTags();
        tags.ensureFresh(snapshot(3, Map.of(Items.BIRCH_PLANKS, 1)), index);
        assertSame(published, tags.currentTags());
    }

    @Test
    void craftableChangedSinceCurrentIsEmpty() {
        TagIndex tags = new TagIndex();
        RecipeIndex index = RecipeIndex.build(List.of(), tags);
        tags.ensureFresh(snapshot(1, Map.of(Items.OAK_PLANKS, 3)), index);
        assertTrue(tags.craftableChangedSince(tags.currentTags()).isEmpty());
    }

    @Test
    void nullTagLookupsReturnNullBeforeAndAfterRefresh() {
        TagIndex tags = new TagIndex();
        RecipeIndex index = RecipeIndex.build(List.of(), tags);
        assertNull(tags.inventoryTagMembers(null));
        assertNull(tags.craftableMembers(null));
        tags.ensureFresh(snapshot(1, Map.of(Items.OAK_PLANKS, 3)), index);
        assertNull(tags.inventoryTagMembers(null));
        assertNull(tags.craftableMembers(null));
    }

    private static InventorySnapshot snapshot(long generation, Map<Item, Integer> inventory) {
        return new InventorySnapshot(inventory, Map.of(), generation);
    }
}
