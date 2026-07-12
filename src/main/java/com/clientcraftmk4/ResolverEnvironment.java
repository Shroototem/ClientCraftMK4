package com.clientcraftmk4;

import net.minecraft.client.gui.screens.recipebook.RecipeCollection;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;

import java.util.List;
import java.util.Map;

/**
 * The single seam through which {@link RecipeResolver} and
 * {@link com.clientcraftmk4.tree.RecipeTreeBuilder} pull live game state.
 *
 * <p>Production uses {@link LiveClientEnvironment}, which reads from
 * {@code Minecraft.getInstance()}. The benchmark harness swaps in an
 * implementation backed by a captured snapshot + fake inventory so the entire
 * resolver pipeline can run headlessly without a live client.
 */
public interface ResolverEnvironment {

    /** Result of a single inventory scan: main inventory and container/bundle contents. */
    record InventoryScan(Map<Item, Integer> main, Map<Item, Integer> container) {}

    /** True when the world/player (or benchmark data) is available to resolve against. */
    boolean isReady();

    /** Crafting grid dimension of the active context (3 for a crafting table, 2 otherwise). */
    int gridSize();

    /** All crafting recipe collections known to the recipe book. */
    List<RecipeCollection> craftingCollections();

    /**
     * Scans the inventory (and, when enabled, container/bundle contents) in a single pass.
     * Item stacks with a custom name are excluded from the main map, mirroring the live behaviour.
     */
    InventoryScan scanInventory();

    /** All registered items belonging to {@code tag}, or {@code null} if the tag is unknown. */
    List<Item> tagMembers(TagKey<Item> tag);

    /** True when {@code item} is a member of {@code tag}. */
    boolean itemHasTag(Item item, TagKey<Item> tag);

    /** Runs {@code r} on the render/client thread (synchronously in the benchmark). */
    void runOnMainThread(Runnable r);
}
