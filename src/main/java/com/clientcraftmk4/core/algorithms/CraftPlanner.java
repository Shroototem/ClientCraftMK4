package com.clientcraftmk4.core.algorithms;

import com.clientcraftmk4.config.ClientCraftConfig;
import com.clientcraftmk4.core.Constants;
import com.clientcraftmk4.core.CraftModel;
import com.clientcraftmk4.core.GameContext;
import com.clientcraftmk4.core.InventoryProvider;
import com.clientcraftmk4.core.InventorySnapshot;
import com.clientcraftmk4.core.RecipeDisplays;
import com.clientcraftmk4.core.TagIndex;
import com.clientcraftmk4.core.WorkMap;
import com.clientcraftmk4.core.resolver.ResolveContext;
import com.clientcraftmk4.craft.AutoCrafter;
import net.minecraft.client.Minecraft;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.display.RecipeDisplayEntry;
import net.minecraft.world.item.crafting.display.RecipeDisplayId;
import net.minecraft.world.item.crafting.display.SlotDisplay;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Builds {@link AutoCrafter.CraftPlan}s on click (plan §7). Byte-equivalent to
 * MK4's {@code buildCraftCyclesForMode}: ONCE returns a single resolve; STACK
 * repeats until the output stack fills; ALL with a direct recipe uses the
 * fast LCM path ({@link #computeDirectCrafts}) with {@code craftAll} clicks,
 * then falls back to per-iteration resolves. The resolver runs on
 * {@link WorkMap} and the plan is memoised per
 * {@code (recipeId, mode, inventoryGeneration, modelGeneration, gridSize)}.
 */
public final class CraftPlanner {
    private static final Logger LOG = LoggerFactory.getLogger(com.clientcraftmk4.core.Constants.MOD_ID);

    /**
     * Cache key with a precomputed hash: the record version rehashed 5 fields (including
     * a RecipeDisplayId) on every lookup. Lookups happen per craft click; the fields are
     * immutable so hashing once at construction is exactly equivalent.
     */
    private static final class PlanKey {
        final RecipeDisplayId id;
        final AutoCrafter.Mode mode;
        final long invGen;
        final long modelGen;
        final int gridSize;
        final int hash;

        PlanKey(RecipeDisplayId id, AutoCrafter.Mode mode, long invGen, long modelGen, int gridSize) {
            this.id = id;
            this.mode = mode;
            this.invGen = invGen;
            this.modelGen = modelGen;
            this.gridSize = gridSize;
            int h = id.hashCode();
            h = 31 * h + mode.hashCode();
            h = 31 * h + Long.hashCode(invGen);
            h = 31 * h + Long.hashCode(modelGen);
            h = 31 * h + Integer.hashCode(gridSize);
            this.hash = h;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof PlanKey k)) return false;
            return hash == k.hash && gridSize == k.gridSize && invGen == k.invGen
                    && modelGen == k.modelGen && mode == k.mode && id.equals(k.id);
        }

        @Override
        public int hashCode() {
            return hash;
        }
    }

    // Guarded by its own lock: plan() can race craft clicks against the stabilizing
    // tick handler. Cap raised 8 -> 32: ALL/STACK cycling thrashed the old cap and
    // recomputed 999-deep plans from scratch on every miss.
    private static final LinkedHashMap<PlanKey, AutoCrafter.CraftPlan> planCache = new LinkedHashMap<>(32, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<PlanKey, AutoCrafter.CraftPlan> eldest) {
            return size() > 32;
        }
    };

    private CraftPlanner() {}

    public static AutoCrafter.CraftPlan plan(RecipeDisplayEntry target, AutoCrafter.Mode mode) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return null;
        CraftModel model = CraftModel.current();
        if (model == null) return null;

        int gridSize = GameContext.gridSize();
        InventorySnapshot snap = InventoryProvider.current();

        PlanKey key = new PlanKey(target.id(), mode, snap.generation(), model.modelGeneration(), gridSize);
        synchronized (planCache) {
            AutoCrafter.CraftPlan cached = planCache.get(key);
            if (cached != null) return cached;
        }

        AutoCrafter.CraftPlan plan = buildPlan(target, mode, model, gridSize, snap);
        if (plan != null) {
            synchronized (planCache) {
                planCache.put(key, plan);
            }
        }
        return plan;
    }

    private static AutoCrafter.CraftPlan buildPlan(RecipeDisplayEntry target, AutoCrafter.Mode mode,
                                                   CraftModel model, int gridSize, InventorySnapshot snap) {
        long t0 = ClientCraftConfig.debugLogging ? System.nanoTime() : 0;

        WorkMap available = WorkMap.from(snap.inventory(), model.graph());
        // One shared context per plan: the old code built a fresh ResolveContext per repeat
        // (up to 999x per ALL click); the context is stateless w.r.t. the work map.
        ResolveContext ctx = ResolveContext.of(model, gridSize);
        List<RecipeDisplayId> firstSteps = new ArrayList<>();
        if (!ctx.resolve(target, available, firstSteps, new HashSet<>(), 0, null)) {
            return null;
        }

        // A "direct" recipe has exactly 1 step (no sub-crafting required). For ALL mode
        // with direct recipes, craftAll fills the grid with full stacks per click.
        boolean directCraft = mode == AutoCrafter.Mode.ALL && firstSteps.size() == 1;

        ItemStack output = RecipeDisplays.resolveResult(target.display(), snap.inventory(), model.tagIndex());
        int outputCount = Math.max(1, output.getCount());

        int maxRepeats = switch (mode) {
            case ONCE -> 1;
            case STACK -> (output.getMaxStackSize() + outputCount - 1) / outputCount;
            case ALL -> Constants.MAX_REPEATS;
        };
        if (maxRepeats <= 0) return null;

        // Fast path: ONCE mode needs only the first resolve.
        if (maxRepeats == 1) {
            return new AutoCrafter.CraftPlan(List.of(firstSteps), false);
        }

        List<List<RecipeDisplayId>> cycles = new ArrayList<>(Math.min(maxRepeats, 64));
        cycles.add(firstSteps);

        if (directCraft) {
            // Count how many crafts can be done with direct items only.
            Map<Object, Integer> needed = new HashMap<>();
            Map<Object, Integer> avail = new HashMap<>();
            Map<Item, Integer> simMap = new HashMap<>(snap.inventory());
            int directCount = computeDirectCrafts(target, simMap, maxRepeats, needed, avail, model, gridSize);
            int craftsPerClick = output.getMaxStackSize() / outputCount;
            int directClicks = Math.max(1, (directCount + craftsPerClick - 1) / craftsPerClick);

            for (int r = 1; r < directClicks; r++) {
                cycles.add(List.of(firstSteps.getFirst()));
            }

            // Deduct direct items from the working map, then continue with sub-crafting.
            deductDirect(needed, available, model);
            for (int r = directCount; r < maxRepeats; r++) {
                List<RecipeDisplayId> steps = new ArrayList<>();
                if (!ctx.resolve(target, available, steps, new HashSet<>(), 0, null)) break;
                cycles.add(steps);
            }
        } else {
            for (int r = 1; r < maxRepeats; r++) {
                List<RecipeDisplayId> steps = new ArrayList<>();
                if (!ctx.resolve(target, available, steps, new HashSet<>(), 0, null)) break;
                cycles.add(steps);
            }
        }

        // Direct craft flag only applies if ALL cycles are single-step direct.
        // Single loop (was 2 stream passes with spliterator+lambda allocs).
        boolean allDirect = directCraft;
        int totalSteps = 0;
        for (List<RecipeDisplayId> c : cycles) {
            totalSteps += c.size();
            if (c.size() != 1) allDirect = false;
        }

        if (ClientCraftConfig.debugLogging) {
            long elapsed = System.nanoTime() - t0;
            LOG.info("[CC] BuildCraft({}): {}ms | {} cycles, {} steps, direct={}",
                    mode, elapsed / 1_000_000, cycles.size(), totalSteps, allDirect);
        }
        return new AutoCrafter.CraftPlan(cycles, allDirect);
    }

    /**
     * Core implementation: counts (and deducts from {@code sim}) direct-only crafts.
     * Port of MK4's {@code computeDirectCrafts} — byte-equivalent, kept Map-based.
     */
    private static int computeDirectCrafts(RecipeDisplayEntry target, Map<Item, Integer> sim, int maxRepeats,
            Map<Object, Integer> needed, Map<Object, Integer> avail, CraftModel model, int gridSize) {
        List<SlotDisplay> slots = RecipeDisplays.getSlots(target);
        if (slots == null || maxRepeats <= 0) return 0;

        needed.clear();
        avail.clear();
        TagIndex tags = model.tagIndex();

        for (SlotDisplay slot : slots) {
            if (slot instanceof SlotDisplay.Empty) continue;
            TagKey<Item> tag = RecipeDisplays.getSlotTag(slot);
            if (tag != null) {
                needed.merge(tag, 1, Integer::sum);
                if (!avail.containsKey(tag)) avail.put(tag, tags.sumTagInventory(tag, sim));
            } else if (slot instanceof SlotDisplay.Composite) {
                return 0;
            } else {
                Item item = RecipeDisplays.resolveSlotItem(slot, sim, tags, true);
                if (item == null) return 0;
                if (sim.getOrDefault(item, 0) <= 0) return 0;
                needed.merge(item, 1, Integer::sum);
                avail.putIfAbsent(item, sim.getOrDefault(item, 0));
            }
        }

        int maxCrafts = maxRepeats;
        for (var e : needed.entrySet()) {
            int crafts = avail.getOrDefault(e.getKey(), 0) / e.getValue();
            if (crafts < maxCrafts) maxCrafts = crafts;
        }
        if (maxCrafts <= 0) return 0;

        for (var e : needed.entrySet()) {
            int toConsume = maxCrafts * e.getValue();
            if (e.getKey() instanceof Item item) {
                sim.merge(item, -toConsume, Integer::sum);
            } else if (e.getKey() instanceof TagKey<?>) {
                @SuppressWarnings("unchecked")
                TagKey<Item> tagKey = (TagKey<Item>) e.getKey();
                Set<Item> members = tags.inventoryTagMembers(tagKey);
                if (members != null) {
                    for (Item m : members) {
                        int have = sim.getOrDefault(m, 0);
                        if (have <= 0) continue;
                        int take = Math.min(have, toConsume);
                        sim.merge(m, -take, Integer::sum);
                        toConsume -= take;
                        if (toConsume <= 0) break;
                    }
                }
            }
        }
        return maxCrafts;
    }

    /** Applies the per-ingredient deductions to the journal-backed working map. */
    private static void deductDirect(Map<Object, Integer> needed, WorkMap work, CraftModel model) {
        for (var e : needed.entrySet()) {
            if (e.getKey() instanceof Item item) {
                work.consume(model.graph().id(item), e.getValue());
            } else if (e.getKey() instanceof TagKey<?>) {
                @SuppressWarnings("unchecked")
                TagKey<Item> tagKey = (TagKey<Item>) e.getKey();
                Set<Item> members = model.tagIndex().inventoryTagMembers(tagKey);
                if (members != null) {
                    int toConsume = e.getValue();
                    for (Item m : members) {
                        int id = model.graph().id(m);
                        int have = work.get(id);
                        if (have <= 0) continue;
                        int take = Math.min(have, toConsume);
                        work.consume(id, take);
                        toConsume -= take;
                        if (toConsume <= 0) break;
                    }
                }
            }
        }
    }
}
