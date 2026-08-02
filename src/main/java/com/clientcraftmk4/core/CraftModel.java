package com.clientcraftmk4.core;

import com.clientcraftmk4.config.ClientCraftConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.recipebook.RecipeCollection;
import net.minecraft.client.gui.screens.recipebook.SearchRecipeBookCategory;
import net.minecraft.client.ClientRecipeBook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * The persistent recipe-set model: recipe index + tag index + recipe graph
 * (plan §8.1). Rebuilt lazily on recipe reload or world change; the graph is
 * built lazily on the worker thread so the render path never pays for it.
 *
 * <p>Published immutably; {@code modelGeneration} uniquely identifies a model
 * version and lets in-flight resolves detect staleness (plan §9.3).
 */
public final class CraftModel {
    private static final Logger LOG = LoggerFactory.getLogger("clientcraftmk4");
    private static final Object LOCK = new Object();
    private static volatile CraftModel cached;
    private static volatile long generation = 0;
    private static volatile boolean dirty = true;
    private static int lastRecipeCount = -1;   // book-derived, guarded by LOCK (MK4's guard)

    private final RecipeIndex recipeIndex;
    private final TagIndex tagIndex;
    private final long modelGeneration;
    private RecipeGraph graph;          // lazily built, guarded by LOCK

    private CraftModel(RecipeIndex recipeIndex, TagIndex tagIndex, long modelGeneration) {
        this.recipeIndex = recipeIndex;
        this.tagIndex = tagIndex;
        this.modelGeneration = modelGeneration;
    }

    public RecipeIndex recipeIndex() {
        return recipeIndex;
    }

    public TagIndex tagIndex() {
        return tagIndex;
    }

    public long modelGeneration() {
        return modelGeneration;
    }

    /** Cheap part of the model: recipe index + tag index (no graph). */
    public static CraftModel current() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return null;
        synchronized (LOCK) {
            int count = recipeCount(mc);
            if (cached != null && !dirty && count == lastRecipeCount) return cached;
            long t0 = ClientCraftConfig.debugLogging ? System.nanoTime() : 0;
            ClientRecipeBook book = mc.player.getRecipeBook();
            List<RecipeCollection> allCrafting = book.getCollection(SearchRecipeBookCategory.CRAFTING);
            TagIndex tagIndex = new TagIndex();
            RecipeIndex recipeIndex = RecipeIndex.build(allCrafting, tagIndex);
            long gen = generation + 1;
            generation = gen;
            cached = new CraftModel(recipeIndex, tagIndex, gen);
            dirty = false;
            lastRecipeCount = count;
            if (ClientCraftConfig.debugLogging) {
                LOG.info("[CC] Model build: {} recipes, {} outputs, {} tags, generation {} ({}ms)",
                        count, recipeIndex.outputs().size(), tagIndex.knownTagCount(),
                        gen, (System.nanoTime() - t0) / 1_000_000);
            }
            return cached;
        }
    }

    /** The recipe graph (DAG + flat arrays), built once per model on the calling thread. */
    public static RecipeGraph graph() {
        CraftModel model = current();
        if (model == null) return null;
        synchronized (LOCK) {
            if (model.graph == null) {
                long t0 = ClientCraftConfig.debugLogging ? System.nanoTime() : 0;
                model.graph = GraphBuilder.build(model.recipeIndex(), model.tagIndex());
                if (ClientCraftConfig.debugLogging) {
                    LOG.info("[CC] Graph build: {} items, {} recipes ({}ms)",
                            model.graph.flat().n(), model.graph.flat().totalRecipes(),
                            (System.nanoTime() - t0) / 1_000_000);
                }
            }
            return model.graph;
        }
    }

    private static int recipeCount(Minecraft mc) {
        List<RecipeCollection> all = mc.player.getRecipeBook().getCollection(SearchRecipeBookCategory.CRAFTING);
        int count = 0;
        for (RecipeCollection c : all) count += c.getRecipes().size();
        return count;
    }

    public static long generation() {
        return generation;
    }

    /** Called from {@code ClientRecipeBookMixin.rebuildCollections} — plan §5.11. */
    public static void markDirty() {
        synchronized (LOCK) {
            dirty = true;
            generation++;
            lastRecipeCount = -1;
        }
    }

    public static void reset() {
        synchronized (LOCK) {
            cached = null;
            dirty = true;
            generation++;
            lastRecipeCount = -1;
        }
    }
}
