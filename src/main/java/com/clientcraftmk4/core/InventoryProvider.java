package com.clientcraftmk4.core;

import com.clientcraftmk4.config.ClientCraftConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.BundleContents;
import net.minecraft.world.item.component.ItemContainerContents;

import java.util.HashMap;
import java.util.Map;

/**
 * The only place that touches the player's inventory (plan §8.2 / §12).
 *
 * <p>{@link #current()} re-walks the player's inventory, bumps the generation on
 * content change, and refreshes the tag indices. {@link #latest()} is a plain
 * volatile field read (no player access) — used by recipe helpers during model
 * builds, mirroring MK4's static {@code cachedInventory}.
 *
 * <p>Custom-named items (any {@code CUSTOM_NAME} data component) are excluded
 * from the snapshot entirely — they can never act as generic ingredients.
 * Containers (shulker boxes / bundles) are scanned one level deep only, and only
 * when {@code searchContainers} is enabled; they never contribute to counts.
 */
public final class InventoryProvider {
    private static volatile InventorySnapshot latest = InventorySnapshot.EMPTY;

    private InventoryProvider() {}

    public static InventorySnapshot latest() {
        return latest;
    }

    public static long generation() {
        return latest.generation();
    }

    /** Re-reads the player inventory (MK4's {@code getOrSnapshotInventory}). */
    public static InventorySnapshot current() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return latest;

        Map<Item, Integer> inv = new HashMap<>();
        Map<Item, Integer> cont = new HashMap<>();
        var invObj = mc.player.getInventory();
        for (int i = 0; i < invObj.getContainerSize(); i++) {
            ItemStack stack = invObj.getItem(i);
            if (stack.isEmpty()) continue;

            if (ClientCraftConfig.searchContainers) {
                ItemContainerContents container = stack.get(DataComponents.CONTAINER);
                if (container != null) {
                    container.nonEmptyItemCopyStream().forEach(contained ->
                            cont.merge(contained.getItem(), contained.getCount(), Integer::sum));
                }
                BundleContents bundle = stack.get(DataComponents.BUNDLE_CONTENTS);
                if (bundle != null) {
                    //? if >=26.3-snapshot-7 {
                    bundle.itemCopies().forEach(contained ->
                            cont.merge(contained.getItem(), contained.getCount(), Integer::sum));
                    //?}
                    //? if <26.3-snapshot-7 {
                    /*bundle.itemCopyStream().forEach(contained ->
                            cont.merge(contained.getItem(), contained.getCount(), Integer::sum));
                    *///?}
                }
            }

            if (stack.has(DataComponents.CUSTOM_NAME)) continue;
            inv.merge(stack.getItem(), stack.getCount(), Integer::sum);
        }

        InventorySnapshot prev = latest;
        InventorySnapshot snap = new InventorySnapshot(inv, cont, prev.generation() + 1);
        if (!inv.equals(prev.inventory()) || !cont.equals(prev.container())) {
            latest = snap;
            refreshTagIndices(snap);
            return snap;
        }
        // Content unchanged — but the model may have been rebuilt, in which case the
        // fresh TagIndex still needs its inventory indices populated (guarded internally).
        refreshTagIndices(prev);
        return prev;
    }

    /** MK4's {@code pollInventory} — forces a fresh snapshot read so generation changes surface. */
    public static void poll() {
        current();
    }

    private static void refreshTagIndices(InventorySnapshot snap) {
        CraftModel model = CraftModel.current();
        if (model != null) {
            model.tagIndex().refreshInventory(snap.inventory(), snap.container(), model.recipeIndex());
        }
    }

    public static void reset() {
        latest = InventorySnapshot.EMPTY;
    }
}
