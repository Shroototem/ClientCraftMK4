package com.clientcraftmk4.pipeline;

import com.clientcraftmk4.core.CountEngine;
import com.clientcraftmk4.mixin.accessor.RecipeCollectionAccessor;
import net.minecraft.client.ClientRecipeBook;
import net.minecraft.client.gui.screens.recipebook.RecipeCollection;
import net.minecraft.client.gui.screens.recipebook.SearchRecipeBookCategory;
import net.minecraft.world.item.crafting.display.RecipeDisplayEntry;
import net.minecraft.world.item.crafting.display.RecipeDisplayId;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Builds the vanilla {@link RecipeCollection}s that the recipe book renders,
 * plus the per-collection rank (direct=0, container=1, none=2) and the
 * auto-craft collection set (plan §13.5). Port of MK4's result-assembly loop.
 */
public final class CollectionAssembler {
    private CollectionAssembler() {}

    /** Uncounted placeholder collections so the tab is populated on first open. */
    public static List<RecipeCollection> placeholder(ClientRecipeBook book) {
        List<RecipeCollection> out = new ArrayList<>();
        for (RecipeCollection coll : book.getCollection(SearchRecipeBookCategory.CRAFTING)) {
            List<RecipeDisplayEntry> entries = coll.getRecipes();
            if (entries.isEmpty()) continue;
            RecipeCollection nc = new RecipeCollection(entries);
            RecipeCollectionAccessor acc = (RecipeCollectionAccessor) nc;
            for (RecipeDisplayEntry e : entries) acc.displayable().add(e.id());
            out.add(nc);
        }
        return out;
    }

    public static ResolveResult assemble(ResolveRequest r, CountEngine.CraftCounts c) {
        List<RecipeCollection> allCrafting = r.recipeBook().getCollection(SearchRecipeBookCategory.CRAFTING);
        List<RecipeCollection> result = new ArrayList<>();
        Map<RecipeCollection, Integer> ranks = new IdentityHashMap<>();
        Set<RecipeCollection> autoCraft = new HashSet<>();
        Map<RecipeDisplayId, Integer> counts = new HashMap<>();

        List<List<RecipeDisplayEntry>> collAllEntries = c.collAllEntries();
        for (int i = 0; i < allCrafting.size(); i++) {
            List<RecipeDisplayEntry> allEntries = collAllEntries.get(i);
            if (allEntries.isEmpty()) continue;

            List<RecipeDisplayEntry> craftable = new ArrayList<>();
            boolean hasDirect = false;
            boolean hasContainer = false;

            for (RecipeDisplayEntry entry : allEntries) {
                int finalCount = c.counts().getOrDefault(entry.id(), 0);
                if (finalCount > 0) {
                    craftable.add(entry);
                    hasDirect = true;
                    counts.put(entry.id(), finalCount);
                } else if (c.containerCraftable().contains(entry.id())) {
                    craftable.add(entry);
                    hasContainer = true;
                }
            }

            RecipeCollection nc = new RecipeCollection(allEntries);
            RecipeCollectionAccessor acc = (RecipeCollectionAccessor) nc;
            for (RecipeDisplayEntry e : allEntries) acc.displayable().add(e.id());
            for (RecipeDisplayEntry e : craftable) acc.craftable().add(e.id());
            ranks.put(nc, hasDirect ? 0 : hasContainer ? 1 : 2);
            result.add(nc);
        }
        autoCraft.addAll(result);

        return new ResolveResult(result, counts, c.containerCraftable(), c.containerAvailableItems(),
                ranks, autoCraft, r.cacheKey(), r.snapshot().generation(), r.modelGeneration());
    }
}
