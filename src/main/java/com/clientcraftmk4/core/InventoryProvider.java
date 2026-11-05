package com.clientcraftmk4.core;

import com.clientcraftmk4.config.ClientCraftConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractCraftingMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.BundleContents;
import net.minecraft.world.item.component.ItemContainerContents;

import java.util.HashMap;
import java.util.Map;

/**
 * The only place that touches the player's inventory (plan §8.2 / §12).
 *
 * <p>{@link #current()} re-walks the player's inventory and bumps the generation on
 * content change. {@link #latest()} is a plain volatile field read (no player access) —
 * used by recipe helpers during model builds, mirroring MK4's static {@code cachedInventory}.
 *
 * <p>Counts cover the main inventory slots and the open crafting grid, and only stacks
 * the server crafts with ({@link Inventory#isUsableForCrafting}: not damaged, not enchanted).
 * Custom-named items (any {@code CUSTOM_NAME} data component) are excluded
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

        // Sized for a full player inventory + hotbar so the per-frame walk doesn't rehash.
        Map<Item, Integer> inv = new HashMap<>(64);
        Map<Item, Integer> cont = new HashMap<>(16);
        var invObj = mc.player.getInventory();
        // Every slot is scanned for containers (an offhand shulker still reports its contents);
        // only main-inventory slots count toward the crafting inventory.
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
                    //? if >=26.3 {
                    bundle.itemCopies().forEach(contained ->
                            cont.merge(contained.getItem(), contained.getCount(), Integer::sum));
                    //?}
                    //? if <26.3 {
                    /*bundle.itemCopyStream().forEach(contained ->
                            cont.merge(contained.getItem(), contained.getCount(), Integer::sum));
                    *///?}
                }
            }

            if (i < Inventory.INVENTORY_SIZE && Inventory.isUsableForCrafting(stack)) {
                inv.merge(stack.getItem(), stack.getCount(), Integer::sum);
            }
        }

        AbstractCraftingMenu menu = GameContext.craftingMenu();
        if (menu != null) {
            for (Slot slot : menu.getInputGridSlots()) {
                ItemStack stack = slot.getItem();
                if (!stack.isEmpty() && Inventory.isUsableForCrafting(stack)) {
                    inv.merge(stack.getItem(), stack.getCount(), Integer::sum);
                }
            }
        }

        InventorySnapshot prev = latest;
        // Compare first so an unchanged read allocates no snapshot. Generations bump only on
        // content change.
        if (inv.equals(prev.inventory()) && cont.equals(prev.container())) return prev;
        InventorySnapshot snap = new InventorySnapshot(inv, cont, prev.generation() + 1);
        latest = snap;
        return snap;
    }

    /** MK4's {@code pollInventory} — forces a fresh snapshot read so generation changes surface. */
    public static void poll() {
        current();
    }

    public static void reset() {
        latest = InventorySnapshot.EMPTY;
    }
}
