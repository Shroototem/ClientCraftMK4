package com.clientcraftmk4.core;

import com.clientcraftmk4.config.ClientCraftConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponents;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.BundleContents;
import net.minecraft.world.item.component.ItemContainerContents;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

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
    /**
     * Craftable tags whose member order/content changed on the last {@link #current()} read
     * (empty when indices didn't rebuild). Render-thread written and read (request builders
     * run there, right after {@code current()}), so no cross-thread concerns.
     */
    private static volatile Set<TagKey<Item>> lastChangedTags = Set.of();

    private InventoryProvider() {}

    public static InventorySnapshot latest() {
        return latest;
    }

    public static long generation() {
        return latest.generation();
    }

    /** See {@link #lastChangedTags}: the craftable-tag diff from the last snapshot read. */
    public static Set<TagKey<Item>> lastChangedTags() {
        return lastChangedTags;
    }

    /** Re-reads the player inventory (MK4's {@code getOrSnapshotInventory}). */
    public static InventorySnapshot current() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return latest;

        // Sized for a full player inventory + hotbar so the per-frame walk doesn't rehash.
        Map<Item, Integer> inv = new HashMap<>(64);
        Map<Item, Integer> cont = new HashMap<>(16);
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

            if (stack.has(DataComponents.CUSTOM_NAME)) continue;
            inv.merge(stack.getItem(), stack.getCount(), Integer::sum);
        }

        InventorySnapshot prev = latest;
        // Compare before snapshotting: the old code always built a snapshot (2x Map.copyOf
        // rehash) and threw it away when nothing changed. Visible generations still bump
        // only on content change, exactly as before.
        if (inv.equals(prev.inventory()) && cont.equals(prev.container())) {
            // Content unchanged — but the model may have been rebuilt, in which case the
            // fresh TagIndex still needs its inventory indices populated (guarded internally).
            lastChangedTags = refreshTagIndices(prev);
            return prev;
        }
        InventorySnapshot snap = new InventorySnapshot(inv, cont, prev.generation() + 1);
        latest = snap;
        lastChangedTags = refreshTagIndices(snap);
        return snap;
    }

    /** MK4's {@code pollInventory} — forces a fresh snapshot read so generation changes surface. */
    public static void poll() {
        current();
    }

    private static Set<TagKey<Item>> refreshTagIndices(InventorySnapshot snap) {
        CraftModel model = CraftModel.current();
        if (model != null) {
            return model.tagIndex().refreshInventory(snap.inventory(), snap.container(), model.recipeIndex());
        }
        return Set.of();
    }

    public static void reset() {
        latest = InventorySnapshot.EMPTY;
    }
}
