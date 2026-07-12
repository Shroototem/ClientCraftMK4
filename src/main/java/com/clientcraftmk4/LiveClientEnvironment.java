package com.clientcraftmk4;

import net.minecraft.client.ClientRecipeBook;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.CraftingScreen;
import net.minecraft.client.gui.screens.recipebook.RecipeCollection;
import net.minecraft.client.gui.screens.recipebook.SearchRecipeBookCategory;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.BundleContents;
import net.minecraft.world.item.component.ItemContainerContents;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Production {@link ResolverEnvironment} backed by the live Minecraft client.
 * This is the default the mod ships with; behaviour matches the pre-refactor
 * inline {@code Minecraft.getInstance()} accesses in {@link RecipeResolver} exactly.
 */
public class LiveClientEnvironment implements ResolverEnvironment {

    @Override
    public boolean isReady() {
        Minecraft client = Minecraft.getInstance();
        return client.level != null && client.player != null;
    }

    @Override
    public int gridSize() {
        return (Minecraft.getInstance().screen instanceof CraftingScreen) ? 3 : 2;
    }

    @Override
    public List<RecipeCollection> craftingCollections() {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null) return List.of();
        ClientRecipeBook book = client.player.getRecipeBook();
        return book.getCollection(SearchRecipeBookCategory.CRAFTING);
    }

    @Override
    public InventoryScan scanInventory() {
        Minecraft client = Minecraft.getInstance();
        if (client.level == null || client.player == null) {
            return new InventoryScan(Map.of(), Map.of());
        }
        Map<Item, Integer> snapshot = new HashMap<>();
        Map<Item, Integer> containerSnapshot = new HashMap<>();
        var inv = client.player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.isEmpty()) continue;

            if (ClientCraftConfig.searchContainers) {
                ItemContainerContents container = stack.get(DataComponents.CONTAINER);
                if (container != null) {
                    container.nonEmptyItemCopyStream().forEach(contained ->
                            containerSnapshot.merge(contained.getItem(), contained.getCount(), Integer::sum));
                }
                BundleContents bundle = stack.get(DataComponents.BUNDLE_CONTENTS);
                if (bundle != null) {
                    bundle.itemCopyStream().forEach(contained ->
                            containerSnapshot.merge(contained.getItem(), contained.getCount(), Integer::sum));
                }
            }

            if (stack.has(DataComponents.CUSTOM_NAME)) continue;
            snapshot.merge(stack.getItem(), stack.getCount(), Integer::sum);
        }
        return new InventoryScan(snapshot, containerSnapshot);
    }

    @Override
    public List<Item> tagMembers(TagKey<Item> tag) {
        Minecraft client = Minecraft.getInstance();
        if (client.level == null) return null;
        var registry = client.level.registryAccess().lookupOrThrow(Registries.ITEM);
        var entries = registry.get(tag);
        if (entries.isEmpty()) return null;
        List<Item> items = new ArrayList<>();
        for (var entry : entries.get()) items.add(entry.value());
        return items;
    }

    @Override
    public boolean itemHasTag(Item item, TagKey<Item> tag) {
        return item.builtInRegistryHolder().is(tag);
    }

    @Override
    public void runOnMainThread(Runnable r) {
        Minecraft.getInstance().execute(r);
    }
}
