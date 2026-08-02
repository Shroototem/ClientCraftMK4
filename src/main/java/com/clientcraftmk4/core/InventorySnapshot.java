package com.clientcraftmk4.core;

import net.minecraft.world.item.Item;

import java.util.HashMap;
import java.util.Map;

/**
 * Immutable inventory + container snapshot with a monotonic generation id
 * (plan §8.2). Bumped only when content equality fails — slot-position changes
 * do not bump, matching MK4 exactly.
 */
public record InventorySnapshot(
        Map<Item, Integer> inventory,
        Map<Item, Integer> container,
        long generation
) {
    public static final InventorySnapshot EMPTY = new InventorySnapshot(Map.of(), Map.of(), 0);

    public InventorySnapshot {
        inventory = Map.copyOf(inventory);
        container = Map.copyOf(container);
    }

    public boolean isEmpty() {
        return inventory.isEmpty() && container.isEmpty();
    }

    /** inventory ∪ container (a fresh merged map). */
    public Map<Item, Integer> combined() {
        if (container.isEmpty()) return inventory;
        Map<Item, Integer> m = new HashMap<>(inventory);
        container.forEach((k, v) -> m.merge(k, v, Integer::sum));
        return m;
    }
}
