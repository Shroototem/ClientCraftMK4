package com.clientcraftmk4.benchmark;

import net.minecraft.SharedConstants;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;

/**
 * Headless bootstrap for the benchmark tests.
 *
 * <p>{@code Bootstrap.bootStrap()} registers the item registry but, in this Minecraft
 * version, does not bind the data-component maps onto each registry holder — so
 * {@code new ItemStack(item)} throws "Components not bound yet". Full binding via
 * {@code DataComponentInitializers} needs a datapack-backed {@code HolderLookup.Provider}
 * (e.g. fire-resistant items resolve the {@code damage_type/is_fire} tag), which is
 * exactly the datapack load the snapshot approach avoids.
 *
 * <p>The resolver only reads item, count and max-stack-size from a stack, so we bind a
 * minimal component map with {@code MAX_STACK_SIZE = 64} to every item holder. That
 * unblocks stack construction and keeps STACK/ALL auto-craft planning representative
 * (it plans full 64-stacks). Actual per-item stack limits are not modelled — this is a
 * timing harness, not a correctness check.
 */
final class TestBootstrap {

    private static boolean done = false;

    private TestBootstrap() {}

    static synchronized void ensure() {
        if (done) return;
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        DataComponentMap components = DataComponentMap.builder()
                .set(DataComponents.MAX_STACK_SIZE, 64)
                .build();
        BuiltInRegistries.ITEM.forEach(item -> {
            var holder = item.builtInRegistryHolder();
            if (!holder.areComponentsBound()) holder.bindComponents(components);
        });
        done = true;
    }
}
