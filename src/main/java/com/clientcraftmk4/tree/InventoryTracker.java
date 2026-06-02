package com.clientcraftmk4.tree;

import com.clientcraftmk4.ClientCraftConfig;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.component.BundleContents;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.*;

public class InventoryTracker {
    private Map<Item, Integer> lastInventory = Map.of();
    private Map<Item, Integer> lastContainerInventory = Map.of();
    private long generation = 0;
    private long lastSnapshotTick = -1;

    /**
     * Take a new inventory snapshot. Returns the set of items whose counts changed.
     * Returns null if called on the same tick (no snapshot taken).
     */
    public Set<Item> snapshot(Inventory inventory, long worldTick) {
        if (worldTick == lastSnapshotTick) return null;
        lastSnapshotTick = worldTick;

        Map<Item, Integer> current = new HashMap<>();
        Map<Item, Integer> currentContainer = new HashMap<>();

        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (stack.isEmpty()) continue;

            if (ClientCraftConfig.searchContainers) {
                ItemContainerContents container = stack.get(DataComponents.CONTAINER);
                if (container != null) {
                    container.nonEmptyItemCopyStream().forEach(contained ->
                        currentContainer.merge(contained.getItem(), contained.getCount(), Integer::sum)
                    );
                }
                BundleContents bundle = stack.get(DataComponents.BUNDLE_CONTENTS);
                if (bundle != null) {
                    bundle.itemCopyStream().forEach(contained ->
                        currentContainer.merge(contained.getItem(), contained.getCount(), Integer::sum)
                    );
                }
            }

            if (stack.has(DataComponents.CUSTOM_NAME)) continue;
            current.merge(stack.getItem(), stack.getCount(), Integer::sum);
        }

        if (current.equals(lastInventory) && currentContainer.equals(lastContainerInventory)) {
            return Set.of();
        }

        Set<Item> changed = new HashSet<>();
        collectChanges(current, lastInventory, changed);
        collectChanges(currentContainer, lastContainerInventory, changed);

        lastInventory = current;
        lastContainerInventory = currentContainer;
        generation++;

        return changed;
    }

    private static void collectChanges(Map<Item, Integer> current, Map<Item, Integer> old, Set<Item> changed) {
        for (Map.Entry<Item, Integer> e : current.entrySet()) {
            if (!Objects.equals(old.get(e.getKey()), e.getValue())) changed.add(e.getKey());
        }
        for (Item item : old.keySet()) {
            if (!current.containsKey(item)) changed.add(item);
        }
    }

    public Map<Item, Integer> getInventory() { return lastInventory; }
    public Map<Item, Integer> getContainerInventory() { return lastContainerInventory; }
    public long getGeneration() { return generation; }

    public void clear() {
        lastInventory = Map.of();
        lastContainerInventory = Map.of();
        generation = 0;
        lastSnapshotTick = -1;
    }
}
