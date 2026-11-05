package com.clientcraftmk4.core;

import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.display.RecipeDisplay;
import net.minecraft.world.item.crafting.display.ShapedCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.ShapelessCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.SlotDisplay;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Vanilla wraps some crafting slots: tipped arrows use WithAnyPotion, armor dyeing uses
 * OnlyWithComponent for its ingredient and DyedSlotDemo for its result. Ingredient wrappers
 * must unwrap so the walkers only see plain item slots; a WithAnyPotion result must resolve,
 * and a DyedSlotDemo result must stay empty.
 */
class RecipeDisplaysNormalizeTest {

    private static TagIndex tags;

    @BeforeAll
    static void setUp() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        // Same headless bootstrap as VariantAllocationTest: ItemStack(Holder) needs bound components.
        for (Item item : List.of(Items.OAK_PLANKS, Items.STICK, Items.TIPPED_ARROW,
                Items.LAPIS_LAZULI, Items.LEATHER_CHESTPLATE)) {
            ((Holder.Reference<Item>) item.builtInRegistryHolder()).bindComponents(DataComponentMap.EMPTY);
        }
        tags = new TagIndex();
    }

    @Test
    void potionWrapperIsUnwrapped() {
        SlotDisplay planks = item(Items.OAK_PLANKS);
        assertEquals(planks, RecipeDisplays.normalize(new SlotDisplay.WithAnyPotion(planks)));
    }

    @Test
    void componentWrapperIsUnwrapped() {
        SlotDisplay stick = item(Items.STICK);
        assertEquals(stick, RecipeDisplays.normalize(
                new SlotDisplay.OnlyWithComponent(stick, DataComponents.CUSTOM_NAME)));
    }

    @Test
    void wrapperNestedInCompositeIsUnwrapped() {
        SlotDisplay planks = item(Items.OAK_PLANKS);
        SlotDisplay stick = item(Items.STICK);
        SlotDisplay nested = new SlotDisplay.Composite(List.of(new SlotDisplay.WithAnyPotion(planks), stick));
        assertEquals(new SlotDisplay.Composite(List.of(planks, stick)), RecipeDisplays.normalize(nested));
    }

    @Test
    void wrapperNestedInWithRemainderIsUnwrapped() {
        SlotDisplay planks = item(Items.OAK_PLANKS);
        SlotDisplay remainder = empty();
        SlotDisplay nested = new SlotDisplay.WithRemainder(
                new SlotDisplay.OnlyWithComponent(planks, DataComponents.CUSTOM_NAME), remainder);
        assertEquals(new SlotDisplay.WithRemainder(planks, remainder), RecipeDisplays.normalize(nested));
    }

    @Test
    void getSlotsUnwrapsIngredients() {
        RecipeDisplay display = new ShapelessCraftingRecipeDisplay(
                List.of(new SlotDisplay.WithAnyPotion(item(Items.STICK)), empty()),
                item(Items.OAK_PLANKS), empty());
        assertEquals(List.of(item(Items.STICK), empty()), RecipeDisplays.getSlots(display));
    }

    @Test
    void tippedArrowResultResolvesToArrow() {
        RecipeDisplay display = new ShapedCraftingRecipeDisplay(1, 1,
                List.of(item(Items.STICK)),
                new SlotDisplay.WithAnyPotion(item(Items.TIPPED_ARROW)),
                empty());
        assertEquals(Items.TIPPED_ARROW, RecipeDisplays.getOutputItem(display, Map.of(), tags));
    }

    @Test
    void dyedResultStaysUnresolved() {
        RecipeDisplay display = new ShapelessCraftingRecipeDisplay(
                List.of(item(Items.LEATHER_CHESTPLATE), item(Items.LAPIS_LAZULI)),
                new SlotDisplay.DyedSlotDemo(item(Items.LAPIS_LAZULI), item(Items.LEATHER_CHESTPLATE)),
                empty());
        assertNull(RecipeDisplays.getOutputItem(display, Map.of(), tags));
    }

    // --- helpers -------------------------------------------------------------

    private static SlotDisplay item(Item item) {
        return new SlotDisplay.ItemSlotDisplay(item.builtInRegistryHolder());
    }

    private static SlotDisplay empty() {
        return SlotDisplay.Empty.INSTANCE;
    }
}
