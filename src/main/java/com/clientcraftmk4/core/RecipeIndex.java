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
     * Cached least-flexible-first slot order per entry (issue #8). Pure per-model data
     * (recipe set + tag membership only — never the inventory), so it is shared across
     * threads like {@link #requiredCache}.
     */
    private final ConcurrentHashMap<RecipeDisplayId, List<SlotDisplay>> resolveOrderCache = new ConcurrentHashMap<>();

    /** Rank cap: keeps the per-recipe slot-rank sum far from overflow for any real recipe. */
    private static final int RANK_CAP = 1 << 20;

    /** How deep the rank recursion follows "item → its recipe → that recipe's slots". */
    private static final int RANK_DEPTH = 2;

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

    /**
     * This entry's ingredient slots in resolve order: least-flexible (type-specific)
     * slots first, tag-flexible slots last; ties keep recipe order (stable).
     *
     * <p>Issue #8: a shapeless target like birch_chest_boat lists its chest slot
     * before its boat slot. The chest's recipe draws from the {@code #planks} pool —
     * which contains the very birch planks the boat's type-specific recipe needs —
     * so crafting the chest first can strand the boat (all it has left is an oak
     * plank it cannot use). Resolving the boat first reserves the birch planks;
     * the chest then takes any 8 planks from what remains — an allocation the
     * server's own ingredient picker can no longer break (it chooses freely among
     * tag members, but every specific ingredient is already crafted).
     *
     * <p>Used by both resolve contexts and the overlay's craftability pass so the
     * count badge, the overlay tints and the executed plan always agree.
     */
    public List<SlotDisplay> resolveSlots(RecipeDisplayEntry entry) {
        List<SlotDisplay> slots = RecipeDisplays.getSlots(entry);
        if (slots == null || slots.size() < 2) return slots;
        return resolveOrderCache.computeIfAbsent(entry.id(), id -> orderSlots(slots));
    }

    private List<SlotDisplay> orderSlots(List<SlotDisplay> slots) {
        int n = slots.size();
        long[] ranks = new long[n];
        long prev = Long.MIN_VALUE;
        boolean sorted = true;
        for (int i = 0; i < n; i++) {
            SlotDisplay slot = slots.get(i);
            ranks[i] = slot instanceof SlotDisplay.Empty ? 0 : slotRank(slot, RANK_DEPTH);
            if (ranks[i] < prev) sorted = false;
            prev = ranks[i];
        }
        if (sorted) return slots;

        // Stable insertion sort by rank (n ≤ 9 crafting slots; equal ranks keep
        // recipe order so nothing reshuffles when ranks tie).
        int[] order = new int[n];
        for (int i = 0; i < n; i++) order[i] = i;
        for (int k = 1; k < n; k++) {
            int idx = order[k];
            long rank = ranks[idx];
            int j = k - 1;
            while (j >= 0 && ranks[order[j]] > rank) {
                order[j + 1] = order[j];
                j--;
            }
            order[j + 1] = idx;
        }
        List<SlotDisplay> ordered = new ArrayList<>(n);
        for (int i : order) ordered.add(slots.get(i));
        return List.copyOf(ordered);
    }

    /**
     * Resolve-order rank of one slot (0 = fully type-specific, higher = more flexible).
     * Exposed for the overlay, which must evaluate grid tiles in the same order the
     * planner will craft them.
     */
    public int slotRank(SlotDisplay slot) {
        return slot instanceof SlotDisplay.Empty ? 0 : slotRank(slot, RANK_DEPTH);
    }

    /**
     * How flexible satisfying this slot is. Item slots inherit the flexibility of the
     * item's cheapest recipe (a chest is as flexible as its {@code #planks} ingredient;
     * a birch boat is as specific as its birch planks); tag slots are flexible by
     * definition, scaled by how many members the tag offers.
     */
    private int slotRank(SlotDisplay slot, int depth) {
        if (slot instanceof SlotDisplay.ItemSlotDisplay d) return itemRank(d.item().value(), depth);
        if (slot instanceof SlotDisplay.ItemStackSlotDisplay d) return itemRank(d.stack().item().value(), depth);
        if (slot instanceof SlotDisplay.TagSlotDisplay d) {
            TagKey<Item> tag = RecipeDisplays.getSlotTag(d);
            List<Item> members = tag != null ? tagIndex.members(tag) : null;
            // A tag is flexible by definition; breadth scales the rank so narrow tags
            // (e.g. #birch_logs) still sort before broad ones (e.g. #planks).
            return Math.max(2, members != null ? members.size() : 2);
        }
        if (slot instanceof SlotDisplay.WithRemainder d) return slotRank(d.input(), depth);
        if (slot instanceof SlotDisplay.Composite d) {
            List<SlotDisplay> contents = d.contents();
            if (contents.size() == 1) return slotRank(contents.getFirst(), depth);
            int max = 0;
            for (SlotDisplay sub : contents) max = Math.max(max, slotRank(sub, depth));
            return Math.max(1 + contents.size(), max);
        }
        return 0;
    }

    /**
     * Flexibility of crafting {@code item}: the cheapest (least flexible) of its
     * recipes. Raw resources with no recipe are fully specific (0). Depth-capped so
     * craft cycles (planks → wood → planks) terminate.
     */
    private int itemRank(Item item, int depth) {
        if (depth <= 0) return 0;
        List<RecipeDisplayEntry> recipes = byOutput.get(item);
        if (recipes == null || recipes.isEmpty()) return 0;
        int min = Integer.MAX_VALUE;
        for (RecipeDisplayEntry entry : recipes) min = Math.min(min, recipeRank(entry, depth));
        return min == Integer.MAX_VALUE ? 0 : min;
    }

    /** Sum of the recipe's slot ranks — more tag slots (or broader tags) rank higher. */
    private int recipeRank(RecipeDisplayEntry entry, int depth) {
        List<SlotDisplay> slots = RecipeDisplays.getSlots(entry);
        if (slots == null || slots.isEmpty()) return 0;
        long sum = 0;
        for (SlotDisplay slot : slots) {
            if (slot instanceof SlotDisplay.Empty) continue;
            sum += slotRank(slot, depth - 1);
            if (sum >= RANK_CAP) return RANK_CAP;
        }
        return (int) sum;
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
