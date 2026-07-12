package com.clientcraftmk4.benchmark;

import com.clientcraftmk4.RecipeResolver;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Headless benchmark of the "what is craftable" resolver + auto-craft planner.
 *
 * <p>Replays a recipe snapshot captured in-game via {@code /ccdump} against the fake
 * inventory in {@code benchmark.json}, then runs {@link RecipeResolver#runBenchmark}
 * which prints per-stage timings. Launched through fabric-loader-junit (see build.gradle
 * {@code test} config) so the client mixins the resolver depends on are applied.
 *
 * <p>Run it with {@code ./gradlew benchmark}. Paths are overridable via the
 * {@code ccmk4.snapshot} and {@code ccmk4.inventory} system properties.
 */
public class RecipeResolverBenchmark {

    @BeforeAll
    static void bootstrap() {
        TestBootstrap.ensure();
    }

    @Test
    void benchmark() throws Exception {
        Path snapshotPath = Path.of(System.getProperty("ccmk4.snapshot", "run/benchmark-recipes.dat"));
        Path inventoryPath = Path.of(System.getProperty("ccmk4.inventory", "benchmark.json"));

        Assumptions.assumeTrue(Files.exists(snapshotPath),
                "No recipe snapshot at " + snapshotPath.toAbsolutePath()
                        + " — run the game and execute /ccdump first (see build.gradle).");

        RegistryAccess registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);

        BenchmarkSnapshot snapshot = BenchmarkSnapshot.read(snapshotPath, registries);
        Map<Item, Integer> inventory = loadInventory(inventoryPath);

        System.out.printf("Loaded snapshot: %d recipes / %d collections / %d tags; inventory: %d items%n",
                snapshot.recipeCount(), snapshot.collections.size(), snapshot.tagMembers.size(), inventory.size());

        RecipeResolver.setEnvironment(new SnapshotEnvironment(snapshot, inventory));
        RecipeResolver.runBenchmark(5, 25);
    }

    /**
     * Parses the {@code [{"id","count"}]} fake inventory. {@code count} is used directly:
     * a count above 64 simply represents more than one slot's worth, which the count-based
     * craftable computation already handles without special casing.
     */
    private static Map<Item, Integer> loadInventory(Path path) throws Exception {
        Map<Item, Integer> inventory = new LinkedHashMap<>();
        try (Reader reader = Files.newBufferedReader(path)) {
            JsonArray array = JsonParser.parseReader(reader).getAsJsonArray();
            for (var element : array) {
                JsonObject obj = element.getAsJsonObject();
                Identifier id = Identifier.parse(obj.get("id").getAsString());
                Item item = BuiltInRegistries.ITEM.getValue(id);
                int count = obj.has("count") ? obj.get("count").getAsInt() : 1;
                if (item != null && count > 0) inventory.merge(item, count, Integer::sum);
            }
        }
        return inventory;
    }
}
