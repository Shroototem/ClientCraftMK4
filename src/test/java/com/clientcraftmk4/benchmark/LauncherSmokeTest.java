package com.clientcraftmk4.benchmark;

import com.clientcraftmk4.mixin.RecipeResultCollectionAccessor;
import net.minecraft.client.gui.screens.recipebook.RecipeCollection;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Validates that fabric-loader-junit boots with registries frozen and the
 * CLIENT mixins applied. If this passes, the full benchmark harness is viable.
 */
public class LauncherSmokeTest {

    @BeforeAll
    static void bootstrap() {
        TestBootstrap.ensure();
    }

    @Test
    void registriesAreBootstrapped() {
        assertTrue(BuiltInRegistries.ITEM.size() > 0, "item registry should be populated");
        assertNotNull(Items.STICK, "vanilla items should be loaded");
    }

    @Test
    void clientMixinIsApplied() {
        // An empty collection is enough to prove the accessor mixin is on the class.
        RecipeCollection collection = new RecipeCollection(List.of());
        RecipeResultCollectionAccessor accessor = (RecipeResultCollectionAccessor) collection;
        assertNotNull(accessor.getCraftableRecipes(), "craftable set accessor works (mixin applied)");
    }
}
