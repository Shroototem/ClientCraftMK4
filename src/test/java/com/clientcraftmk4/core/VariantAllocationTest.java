package com.clientcraftmk4.core;

import com.clientcraftmk4.core.resolver.ResolveContext;
import net.minecraft.SharedConstants;
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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Issue #8 regression tests: crafting a birch chest boat from 1 oak plank + 12 birch
 * planks must plan the type-specific boat before the tag-flexible chest, and flexible
 * pool draws must prefer the most abundant member.
 *
 * <p>The fake recipe set mirrors vanilla: a chest from 8 "any planks" slots (modelled
 * as a composite so no registry tag binding is needed — the resolver treats multi-option
 * composites exactly like tag pools), a birch boat from 5 birch-planks slots, and the
 * shapeless chest+boat target with the chest listed first, as vanilla's
 * birch_chest_boat JSON does. The chest's flexible slots list birch before oak, so
 * neither the old display order nor an abundance rule alone can pass — only reserving
 * the type-specific ingredient first does.
 */
class VariantAllocationTest {

    private static RecipeIndex index;
    private static TagIndex tags;
    private static RecipeGraph graph;
    private static ResolveContext ctx;
    private static RecipeDisplayEntry chest;
    private static RecipeDisplayEntry boat;
    private static RecipeDisplayEntry target;
    private static RecipeDisplayEntry flexOne;

    @BeforeAll
    static void setUp() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        // A headless test never loads the vanilla datapack, so item holders have no
        // component map bound — ItemStack(Holder) requires one. The resolve path only
        // reads item identity/count, so an empty component map is sufficient.
        for (Item item : List.of(Items.OAK_PLANKS, Items.BIRCH_PLANKS, Items.CHEST,
                Items.BIRCH_BOAT, Items.BIRCH_CHEST_BOAT, Items.STICK)) {
            ((Holder.Reference<Item>) item.builtInRegistryHolder()).bindComponents(DataComponentMap.EMPTY);
        }
        // Built after bootstrap: the display entries address real registry items.
        chest = entry(1,
                new ShapedCraftingRecipeDisplay(3, 3,
                        List.of(anyPlanks(), anyPlanks(), anyPlanks(),
                                anyPlanks(), empty(),
                                anyPlanks(), anyPlanks(), anyPlanks(), anyPlanks()),
                        item(Items.CHEST), empty()));
        boat = entry(2,
                new ShapedCraftingRecipeDisplay(3, 2,
                        List.of(birchPlanks(), empty(), birchPlanks(),
                                birchPlanks(), birchPlanks(), birchPlanks()),
                        item(Items.BIRCH_BOAT), empty()));
        // Chest listed first, as vanilla's birch_chest_boat JSON does.
        target = entry(3,
                new ShapelessCraftingRecipeDisplay(
                        List.of(item(Items.CHEST), item(Items.BIRCH_BOAT)),
                        item(Items.BIRCH_CHEST_BOAT), empty()));
        // One flexible "any planks" slot, birch listed first.
        flexOne = entry(4,
                new ShapelessCraftingRecipeDisplay(
                        List.of(anyPlanks()), item(Items.STICK), empty()));
        tags = new TagIndex();
        index = RecipeIndex.build(List.of(new net.minecraft.client.gui.screens.recipebook.RecipeCollection(
                List.of(chest, boat, target, flexOne))), tags);
        graph = GraphBuilder.build(index, tags);
        ctx = ResolveContext.of(index, tags, graph, 3);
    }

    /**
     * The exact issue #8 inventory: 1 oak + 12 birch is enough for exactly one
     * allocation — boat gets 5 birch, chest gets 7 birch + the oak. The chest's
     * pool must not be drawn before the boat reserves its birch planks.
     */
    @Test
    void specificIngredientReservedBeforeFlexiblePool() {
        WorkMap work = work(Map.of(Items.OAK_PLANKS, 1, Items.BIRCH_PLANKS, 12));
        List<RecipeDisplayId> steps = new ArrayList<>();
        assertTrue(ctx.resolve(target, work, steps, new HashSet<>(), 0, null),
                "1 oak + 12 birch must craft a birch chest boat");
        // Boat first (reserves the 5 birch), chest from the remaining 8 planks, target last.
        assertEquals(List.of(boat.id(), chest.id(), target.id()), steps);
        // The oak plank is used by the chest; every birch plank is spent.
        assertEquals(0, work.get(graph.id(Items.OAK_PLANKS)));
        assertEquals(0, work.get(graph.id(Items.BIRCH_PLANKS)));
    }

    /** 12 total planks (1 oak + 11 birch) is below the 13 the craft needs. */
    @Test
    void insufficientTotalStaysUncraftable() {
        WorkMap work = work(Map.of(Items.OAK_PLANKS, 1, Items.BIRCH_PLANKS, 11));
        List<RecipeDisplayId> steps = new ArrayList<>();
        assertFalse(ctx.resolve(target, work, steps, new HashSet<>(), 0, null));
        assertTrue(steps.isEmpty(), "a failed resolve must roll its steps back");
    }

    /** The user's abundance rule: pool draws take the largest member first (oak 10 > birch 5). */
    @Test
    void flexiblePoolDrawsMostAbundantMemberFirst() {
        WorkMap work = work(Map.of(Items.OAK_PLANKS, 10, Items.BIRCH_PLANKS, 5));
        List<RecipeDisplayId> steps = new ArrayList<>();
        assertTrue(ctx.resolve(flexOne, work, steps, new HashSet<>(), 0, null));
        // Birch is listed first in the composite, but oak is the most abundant member.
        assertEquals(9, work.get(graph.id(Items.OAK_PLANKS)));
        assertEquals(5, work.get(graph.id(Items.BIRCH_PLANKS)));
    }

    /** A birch boat can never consume oak planks, no matter what is left in the pool. */
    @Test
    void typeSpecificSlotsNeverConsumeOtherTypes() {
        WorkMap work = work(Map.of(Items.OAK_PLANKS, 1, Items.BIRCH_PLANKS, 4));
        List<RecipeDisplayId> steps = new ArrayList<>();
        assertFalse(ctx.resolve(boat, work, steps, new HashSet<>(), 0, null),
                "4 birch + 1 oak cannot make a birch boat");
        assertTrue(steps.isEmpty());

        work = work(Map.of(Items.OAK_PLANKS, 9, Items.BIRCH_PLANKS, 5));
        steps = new ArrayList<>();
        assertTrue(ctx.resolve(boat, work, steps, new HashSet<>(), 0, null));
        assertEquals(9, work.get(graph.id(Items.OAK_PLANKS)), "the oak planks must stay untouched");
        assertEquals(0, work.get(graph.id(Items.BIRCH_PLANKS)));
    }

    /** With planks to spare, any allocation works — the reservation order must not regress it. */
    @Test
    void surplusInventoryStillCrafts() {
        WorkMap work = work(Map.of(Items.OAK_PLANKS, 2, Items.BIRCH_PLANKS, 13));
        List<RecipeDisplayId> steps = new ArrayList<>();
        assertTrue(ctx.resolve(target, work, steps, new HashSet<>(), 0, null));
        assertEquals(List.of(boat.id(), chest.id(), target.id()), steps);
    }

    // --- helpers -------------------------------------------------------------

    private static WorkMap work(Map<Item, Integer> inventory) {
        return WorkMap.from(new HashMap<>(inventory), graph);
    }

    private static RecipeDisplayEntry entry(int id, RecipeDisplay display) {
        return new RecipeDisplayEntry(new RecipeDisplayId(id), display, OptionalInt.empty(),
                RecipeBookCategories.CRAFTING_MISC, Optional.empty());
    }

    private static SlotDisplay item(Item item) {
        return new SlotDisplay.ItemSlotDisplay(item.builtInRegistryHolder());
    }

    private static SlotDisplay empty() {
        return SlotDisplay.Empty.INSTANCE;
    }

    /** Fixed birch-planks slot (vanilla's single-item ingredients normalize to this). */
    private static SlotDisplay birchPlanks() {
        return item(Items.BIRCH_PLANKS);
    }

    /**
     * Flexible "any planks" slot: birch listed first so neither recipe order nor
     * composite content order can save the allocation — the same class of slot
     * the resolver's tag branches handle for real tags.
     */
    private static SlotDisplay anyPlanks() {
        return new SlotDisplay.Composite(List.of(birchPlanks(), item(Items.OAK_PLANKS)));
    }
}
