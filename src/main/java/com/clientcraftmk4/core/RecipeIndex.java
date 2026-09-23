package com.clientcraftmk4.core;

import net.minecraft.client.gui.screens.recipebook.RecipeCollection;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.display.RecipeDisplayEntry;
import net.minecraft.world.item.crafting.display.RecipeDisplayId;
import net.minecraft.world.item.crafting.display.SlotDisplay;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

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
    private final int totalEntries;
    /**
     * Items some ingredient slot of the entry strictly requires (see {@link #requiredItems}).
     * Static per entry (slot structure never changes within a model); shared across threads,
     * so a concurrent map. Lets the resolver's sub-candidate loop replace a full slot-tree
     * walk per candidate per deficit per attempt with one {@code contains}.
     */
    private final ConcurrentHashMap<RecipeDisplayId, Set<Item>> requiredCache = new ConcurrentHashMap<>();

    /**
     * Tags referenced per entry (pre-expansion — unlike the graph edges, this retains which
     * tags each recipe's slots use). Built once per model; feeds the incremental-resolve
     * tag→recipes index. Only entries with tag slots allocate.
     */
    private final Map<RecipeDisplayId, List<TagKey<Item>>> entryTags;

    private RecipeIndex(Map<Item, List<RecipeDisplayEntry>> byOutput, TagIndex tagIndex,
                        int totalEntries, Map<RecipeDisplayId, List<TagKey<Item>>> entryTags) {
        this.byOutput = byOutput;
        this.tagIndex = tagIndex;
        this.totalEntries = totalEntries;
        this.entryTags = entryTags;
    }

    /** Tags referenced by each entry's slots (entries without tag slots absent). */
    public Map<RecipeDisplayId, List<TagKey<Item>>> entryTags() {
        return entryTags;
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
        return totalEntries;
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

    /**
     * Items that some ingredient slot of {@code entry} strictly requires — i.e. the set form
     * of {@code slotRequiresItem}: single-item slots contribute their item, tag slots never do,
     * composites contribute the intersection of their options (all must require), remainders
     * unwrap. {@code recipeConsumesItem(entry, target)} ⟺ {@code requiredItems(entry).contains(target)}
     * exactly (empty composite and unknown slot types contribute nothing, matching the
     * {@code false} branches). Pure function of the slot tree: safe to share per model.
     */
    public Set<Item> requiredItems(RecipeDisplayEntry entry) {
        return requiredCache.computeIfAbsent(entry.id(), id -> buildRequired(entry));
    }

    private static Set<Item> buildRequired(RecipeDisplayEntry entry) {
        List<SlotDisplay> slots = RecipeDisplays.getSlots(entry);
        if (slots == null || slots.isEmpty()) return Set.of();
        Set<Item> out = null;
        for (SlotDisplay slot : slots) {
            Set<Item> r = requiredOf(slot);
            if (r.isEmpty()) continue;
            if (out == null) out = new HashSet<>(r);
            else out.addAll(r);
        }
        return out == null ? Set.of() : Collections.unmodifiableSet(out);
    }

    private static Set<Item> requiredOf(SlotDisplay slot) {
        if (slot instanceof SlotDisplay.Empty) return Set.of();
        if (slot instanceof SlotDisplay.ItemSlotDisplay d) return Set.of(d.item().value());
        if (slot instanceof SlotDisplay.ItemStackSlotDisplay d) return Set.of(d.stack().item().value());
        if (slot instanceof SlotDisplay.TagSlotDisplay) return Set.of();
        if (slot instanceof SlotDisplay.WithRemainder d) return requiredOf(d.input());
        if (slot instanceof SlotDisplay.Composite d) {
            if (d.contents().isEmpty()) return Set.of();
            Set<Item> acc = null;
            for (SlotDisplay sub : d.contents()) {
                Set<Item> r = requiredOf(sub);
                if (acc == null) acc = new HashSet<>(r);
                else acc.retainAll(r);
                if (acc.isEmpty()) break;
            }
            return acc == null ? Set.of() : acc;
        }
        return Set.of();
    }

    /** Builds the index and collects the known tag set (MK4's {@code ensureIndex} body). */
    public static RecipeIndex build(List<RecipeCollection> allCrafting, TagIndex tagIndex) {
        Map<Item, List<RecipeDisplayEntry>> index = new LinkedHashMap<>(1024);
        Map<RecipeDisplayId, List<TagKey<Item>>> entryTags = new HashMap<>();
        int total = 0;
        for (RecipeCollection coll : allCrafting) {
            for (RecipeDisplayEntry entry : coll.getRecipes()) {
                // Most outputs have a single recipe; size 2 covers the common multi-recipe
                // outputs (planks, sticks) without the default-10 waste per key.
                Item out = RecipeDisplays.getOutputItem(entry.display(), tagIndex);
                if (out != null) {
                    index.computeIfAbsent(out, k -> new ArrayList<>(2)).add(entry);
                    total++;
                }
                List<SlotDisplay> slots = RecipeDisplays.getSlots(entry);
                if (slots == null) continue;
                for (SlotDisplay slot : slots) tagIndex.collectTags(slot);
                List<TagKey<Item>> tags = null;
                for (SlotDisplay slot : slots) {
                    if (tags == null) tags = new ArrayList<>(2);
                    collectEntryTagsInto(slot, tags);
                }
                if (tags != null && !tags.isEmpty()) entryTags.put(entry.id(), tags);
            }
        }
        for (Item item : index.keySet()) tagIndex.tagsOf(item);
        return new RecipeIndex(index, tagIndex, total, entryTags);
    }

    /** Tags referenced by one slot tree (deduped), mirroring {@link TagIndex#collectTags}. */
    private static void collectEntryTagsInto(SlotDisplay slot, List<TagKey<Item>> out) {
        if (slot instanceof SlotDisplay.TagSlotDisplay) {
            TagKey<Item> tag = RecipeDisplays.getSlotTag(slot);
            if (tag != null && !out.contains(tag)) out.add(tag);
        } else if (slot instanceof SlotDisplay.Composite d) {
            for (SlotDisplay sub : d.contents()) collectEntryTagsInto(sub, out);
        } else if (slot instanceof SlotDisplay.WithRemainder d) {
            collectEntryTagsInto(d.input(), out);
        }
    }
}
