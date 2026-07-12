package com.clientcraftmk4.benchmark;

import com.clientcraftmk4.ResolverEnvironment;
import net.minecraft.client.gui.screens.recipebook.RecipeCollection;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.crafting.display.RecipeDisplayEntry;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A {@link ResolverEnvironment} backed by a captured {@link BenchmarkSnapshot} and a
 * fake inventory, letting the resolver pipeline run headlessly in the benchmark JVM.
 * Grid size is fixed at 3 (crafting table), containers are unused, and callbacks run
 * synchronously.
 */
public final class SnapshotEnvironment implements ResolverEnvironment {

    private final List<RecipeCollection> collections;
    private final Map<Item, Integer> inventory;
    private final Map<TagKey<Item>, List<Item>> tagMembers;
    private final Map<Item, Set<TagKey<Item>>> itemTags;

    public SnapshotEnvironment(BenchmarkSnapshot snapshot, Map<Item, Integer> inventory) {
        this.inventory = inventory;
        this.tagMembers = snapshot.tagMembers;

        List<RecipeCollection> colls = new ArrayList<>(snapshot.collections.size());
        for (List<RecipeDisplayEntry> entries : snapshot.collections) {
            colls.add(new RecipeCollection(entries));
        }
        this.collections = colls;

        // Reverse index so itemHasTag() is O(1), mirroring holder.is(tag) semantics.
        Map<Item, Set<TagKey<Item>>> reverse = new HashMap<>();
        for (Map.Entry<TagKey<Item>, List<Item>> e : tagMembers.entrySet()) {
            for (Item item : e.getValue()) {
                reverse.computeIfAbsent(item, k -> new HashSet<>()).add(e.getKey());
            }
        }
        this.itemTags = reverse;
    }

    @Override public boolean isReady() { return true; }

    @Override public int gridSize() { return 3; }

    @Override public List<RecipeCollection> craftingCollections() { return collections; }

    @Override public InventoryScan scanInventory() {
        return new InventoryScan(new HashMap<>(inventory), Map.of());
    }

    @Override public List<Item> tagMembers(TagKey<Item> tag) { return tagMembers.get(tag); }

    @Override public boolean itemHasTag(Item item, TagKey<Item> tag) {
        Set<TagKey<Item>> tags = itemTags.get(item);
        return tags != null && tags.contains(tag);
    }

    @Override public void runOnMainThread(Runnable r) { r.run(); }
}
