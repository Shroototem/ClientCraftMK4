package com.clientcraftmk4.pipeline;

import com.clientcraftmk4.core.CountEngine;
import com.clientcraftmk4.mixin.accessor.RecipeCollectionAccessor;
import net.minecraft.client.gui.screens.recipebook.RecipeCollection;
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
    public static List<RecipeCollection> placeholder(List<RecipeCollection> allCrafting) {
        List<RecipeCollection> out = new ArrayList<>(allCrafting.size());
        for (RecipeCollection coll : allCrafting) {
            List<RecipeDisplayEntry> entries = coll.getRecipes();
            if (entries.isEmpty()) continue;
            RecipeCollection nc = new RecipeCollection(entries);
            RecipeCollectionAccessor acc = (RecipeCollectionAccessor) nc;
            for (RecipeDisplayEntry e : entries) acc.displayable().add(e.id());
            out.add(nc);
        }
        return out;
    }

    /** How the last resolve split each source collection, for reuse by the next one. */
    public record Assembly(List<RecipeCollection> source, int gridSize, Group[] groups) {}

    record Group(List<RecipeDisplayEntry> direct, List<RecipeDisplayEntry> containerOnly,
                 RecipeCollection first, RecipeCollection second) {}

    public static ResolveResult assemble(ResolveRequest r, CountEngine.CraftCounts c) {
        List<RecipeCollection> allCrafting = r.allCrafting();
        List<RecipeCollection> result = new ArrayList<>(allCrafting.size());
        Map<RecipeCollection, Integer> ranks = new IdentityHashMap<>(allCrafting.size() + 1);
        Set<RecipeCollection> autoCraft = new HashSet<>(allCrafting.size() + 1);
        Map<RecipeDisplayId, Integer> counts =
                new HashMap<>((int) (c.counts().size() / 0.75f) + 1);

        // The same source list and grid size means the same recipes, so the previous split applies.
        Assembly prev = r.previous() != null ? r.previous().assembly() : null;
        if (prev != null && (prev.source() != allCrafting || prev.gridSize() != r.gridSize())) prev = null;
        Group[] groups = new Group[allCrafting.size()];

        List<List<RecipeDisplayEntry>> collAllEntries = c.collAllEntries();
        for (int i = 0; i < allCrafting.size(); i++) {
            List<RecipeDisplayEntry> allEntries = collAllEntries.get(i);
            if (allEntries.isEmpty()) continue;

            List<RecipeDisplayEntry> direct = new ArrayList<>(allEntries.size());
            List<RecipeDisplayEntry> containerOnly = new ArrayList<>(4);

            for (RecipeDisplayEntry entry : allEntries) {
                int finalCount = c.counts().getOrDefault(entry.id(), 0);
                if (finalCount > 0) {
                    direct.add(entry);
                    counts.put(entry.id(), finalCount);
                } else if (c.containerCraftable().contains(entry.id())) {
                    containerOnly.add(entry);
                }
            }

            // Reuse needs the same split. The rank 1/2 first collection lists allEntries, which
            // quick count can change, so that is compared too.
            Group old = prev != null ? prev.groups()[i] : null;
            boolean reuse = old != null && sameEntries(direct, old.direct())
                    && sameEntries(containerOnly, old.containerOnly())
                    && (!direct.isEmpty() || sameEntries(allEntries, old.first().getRecipes()));

            // Craftable variants take priority: they form the main collection (rank 0)
            // so the button shows a craftable variant first. Container-only variants
            // (e.g. beds/wool you can't make yet, filled bundles) go into a separate
            // group (rank 1) so they never tint the craftable button purple.
            RecipeCollection first;
            RecipeCollection second = null;
            if (!direct.isEmpty()) {
                first = reuse ? old.first() : newCollection(direct, c);
                ranks.put(first, 0);
                if (!containerOnly.isEmpty()) {
                    second = reuse ? old.second() : newCollection(containerOnly, c);
                    ranks.put(second, 1);
                }
            } else if (!containerOnly.isEmpty()) {
                first = reuse ? old.first() : newCollection(allEntries, c);
                ranks.put(first, 1);
            } else {
                first = reuse ? old.first() : newCollection(allEntries, c);
                ranks.put(first, 2);
            }
            result.add(first);
            if (second != null) result.add(second);
            groups[i] = new Group(direct, containerOnly, first, second);
        }
        autoCraft.addAll(result);

        return new ResolveResult(result, counts, c.containerCraftable(), c.containerAvailableItems(),
                ranks, autoCraft, r.cacheKey(), r.snapshot().generation(), r.modelGeneration(), r.snapshot(),
                c.tagState(), new Assembly(allCrafting, r.gridSize(), groups));
    }

    private static RecipeCollection newCollection(List<RecipeDisplayEntry> entries, CountEngine.CraftCounts c) {
        RecipeCollection nc = new RecipeCollection(entries);
        RecipeCollectionAccessor acc = (RecipeCollectionAccessor) nc;
        for (RecipeDisplayEntry e : entries) {
            if (c.counts().getOrDefault(e.id(), 0) > 0 || c.containerCraftable().contains(e.id())) {
                acc.craftable().add(e.id());
            }
        }
        // Buttons cycle only the craftable variants when there are any, so a button never
        // shows or places a recipe it can't craft.
        if (acc.craftable().isEmpty()) {
            for (RecipeDisplayEntry e : entries) acc.displayable().add(e.id());
        } else {
            acc.displayable().addAll(acc.craftable());
        }
        return nc;
    }

    private static boolean sameEntries(List<RecipeDisplayEntry> a, List<RecipeDisplayEntry> b) {
        if (a.size() != b.size()) return false;
        for (int k = 0; k < a.size(); k++) {
            if (a.get(k) != b.get(k)) return false;
        }
        return true;
    }
}
