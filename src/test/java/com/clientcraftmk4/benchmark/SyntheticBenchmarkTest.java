package com.clientcraftmk4.benchmark;

import com.clientcraftmk4.RecipeResolver;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.RecipeBookCategory;
import net.minecraft.world.item.crafting.display.RecipeDisplayEntry;
import net.minecraft.world.item.crafting.display.RecipeDisplayId;
import net.minecraft.world.item.crafting.display.ShapelessCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.SlotDisplay;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * End-to-end proof that the benchmark harness runs headlessly: builds a couple of
 * synthetic recipes, round-trips them through {@link BenchmarkSnapshot} serialization,
 * installs a {@link SnapshotEnvironment}, and runs {@link RecipeResolver#runBenchmark}.
 * Unlike {@link RecipeResolverBenchmark}, this needs no in-game {@code /ccdump}.
 */
public class SyntheticBenchmarkTest {

    @BeforeAll
    static void bootstrap() {
        TestBootstrap.ensure();
    }

    @Test
    void snapshotRoundTripsAndBenchmarks() throws Exception {
        RegistryAccess registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
        RecipeBookCategory category = BuiltInRegistries.RECIPE_BOOK_CATEGORY.stream().findFirst().orElseThrow();

        // stick  <- 1 oak plank ; crafting table <- 4 oak planks
        RecipeDisplayEntry stick = shapeless(1, Items.STICK, category, Items.OAK_PLANKS);
        RecipeDisplayEntry table = shapeless(2, Items.CRAFTING_TABLE, category,
                Items.OAK_PLANKS, Items.OAK_PLANKS, Items.OAK_PLANKS, Items.OAK_PLANKS);

        BenchmarkSnapshot original = new BenchmarkSnapshot(List.of(List.of(stick, table)), Map.of());

        Path tmp = Files.createTempFile("cc-bench-synth", ".dat");
        try {
            original.write(tmp, registries);
            BenchmarkSnapshot loaded = BenchmarkSnapshot.read(tmp, registries);
            assertEquals(2, loaded.recipeCount(), "round-trip preserves recipe count");

            Map<Item, Integer> inventory = Map.of(Items.OAK_PLANKS, 64);
            RecipeResolver.setEnvironment(new SnapshotEnvironment(loaded, inventory));

            // If this completes without throwing, the full pipeline + client-mixin
            // collection building work headlessly.
            RecipeResolver.runBenchmark(2, 5);
        } finally {
            Files.deleteIfExists(tmp);
            RecipeResolver.clearCache();
        }
    }

    private static RecipeDisplayEntry shapeless(int id, Item output, RecipeBookCategory category, Item... ingredients) {
        List<SlotDisplay> slots = new ArrayList<>(ingredients.length);
        for (Item ing : ingredients) slots.add(new SlotDisplay.ItemSlotDisplay(ing));
        ShapelessCraftingRecipeDisplay display = new ShapelessCraftingRecipeDisplay(
                slots, new SlotDisplay.ItemSlotDisplay(output), SlotDisplay.Empty.INSTANCE);
        return new RecipeDisplayEntry(new RecipeDisplayId(id), display, OptionalInt.empty(), category, Optional.empty());
    }
}
