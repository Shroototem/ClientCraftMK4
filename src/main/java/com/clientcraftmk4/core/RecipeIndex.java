package com.clientcraftmk4.core;

import net.minecraft.client.gui.screens.recipebook.RecipeCollection;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.display.RecipeDisplayEntry;
import net.minecraft.world.item.crafting.display.RecipeDisplayId;
import net.minecraft.world.item.crafting.display.SlotDisplay;

import java.util.*;

/**
 * {@code Item → List<RecipeDisplayEntry>} in vanilla iteration order
 * (a LinkedHashMap, matching MK4's {@code recipesByOutput}). Also owns the
 * memoised lower-case display-name cache used by the search filter.
 */
public final class RecipeIndex {
    private final Map<Item, List<RecipeDisplayEntry>> byOutput;
    private final TagIndex tagIndex;
    private final Map<Item, String> lowerCaseNames = new HashMap<>();
    private final Map<RecipeDisplayId, String> entryDisplayNames = new HashMap<>();

    private RecipeIndex(Map<Item, List<RecipeDisplayEntry>> byOutput, TagIndex tagIndex) {
        this.byOutput = byOutput;
        this.tagIndex = tagIndex;
    }

    /** Recipe entries producing {@code item}, in recipe-book order; null if none. */
    public List<RecipeDisplayEntry> get(Item item) {
        return byOutput.get(item);
    }

    public Set<Item> outputs() {
        return byOutput.keySet();
    }

    public boolean isEmpty() {
        return byOutput.isEmpty();
    }

    /** Total number of recipe entries (used to detect recipe-set changes cheaply). */
    public int totalCount() {
        int n = 0;
        for (List<RecipeDisplayEntry> entries : byOutput.values()) n += entries.size();
        return n;
    }

    public String getLowerCaseName(Item item) {
        return lowerCaseNames.computeIfAbsent(item,
                i -> new ItemStack(i).getHoverName().getString().toLowerCase(Locale.ROOT));
    }

    /**
     * Lower-case display name of an entry's resolved output, memoised per
     * {@link RecipeDisplayId}. The search filter calls this for every entry on
     * every keystroke — resolving the result slot display each time dominated
     * that cost; after the first pass this is a single map hit.
     */
    public String lowerCaseDisplayName(RecipeDisplayEntry entry) {
        return entryDisplayNames.computeIfAbsent(entry.id(), id -> {
            ItemStack out = RecipeDisplays.resolveResult(entry.display(), tagIndex);
            return out.isEmpty() ? "" : getLowerCaseName(out.getItem());
        });
    }

    /** Builds the index and collects the known tag set (MK4's {@code ensureIndex} body). */
    public static RecipeIndex build(List<RecipeCollection> allCrafting, TagIndex tagIndex) {
        Map<Item, List<RecipeDisplayEntry>> index = new LinkedHashMap<>();
        for (RecipeCollection coll : allCrafting) {
            for (RecipeDisplayEntry entry : coll.getRecipes()) {
                Item out = RecipeDisplays.getOutputItem(entry.display(), tagIndex);
                if (out != null) index.computeIfAbsent(out, k -> new ArrayList<>()).add(entry);
                List<SlotDisplay> slots = RecipeDisplays.getSlots(entry.display());
                if (slots != null) for (SlotDisplay slot : slots) tagIndex.collectTags(slot);
            }
        }
        for (Item item : index.keySet()) tagIndex.tagsOf(item);
        return new RecipeIndex(index, tagIndex);
    }
}
