package com.clientcraftmk4.core;

import com.clientcraftmk4.core.RecipeGraph.GraphFlatData;
import net.minecraft.world.item.Item;

import java.util.Arrays;
import java.util.Map;

/**
 * Int-array backed working inventory with O(1) snapshots and structured rollback.
 *
 * <p>This replaces MK4's per-attempt {@code new HashMap<>(work)} copies in the recursive
 * resolver (plan §6.4). A snapshot is a single head-pointer into an undo log; rolling back
 * replays the log backwards, restoring only the mutations that were actually made.
 *
 * <p>A deterministic id-ordered "present" list tracks every item id that has ever had
 * a positive count, so tag-fallback scans (which MK4 performed over a HashMap's undefined
 * iteration order) are stable across runs (plan §7.3 decision).
 *
 * <p>This class is deliberately vanilla-free so it can be unit tested in isolation.
 */
public final class WorkMap {
    private int[] count;
    private int[] log;              // (itemId, delta) pairs
    private int head;               // next free slot in the log

    private int[] present;    // item ids that have ever had a positive count, sorted by id
    private int presentSize;
    private boolean[] inPresent;

    public WorkMap(int capacity) {
        int cap = Math.max(16, capacity);
        this.count = new int[cap];
        // Deep qty=999 sims journal ~18k ints; start large to avoid repeated doubling+arraycopy.
        this.log = new int[Math.max(cap * 2, 4096)];
        this.present = new int[cap];
        this.inPresent = new boolean[cap];
    }

    /** Builds a working map from an inventory snapshot, deterministically ordered by item id. */
    public static WorkMap from(Map<Item, Integer> inventory, RecipeGraph graph) {
        WorkMap wm = new WorkMap(capacityOf(graph));
        wm.fillFrom(inventory, graph);
        return wm;
    }

    private static int capacityOf(RecipeGraph graph) {
        GraphFlatData f = graph.flat();
        return f != null ? Math.max(16, f.n()) : 16;
    }

    public int capacity() {
        return count.length;
    }

    /** Resets all state (counts, present list, undo log) and refills from an inventory snapshot. */
    public void resetTo(Map<Item, Integer> inventory, RecipeGraph graph) {
        GraphFlatData f = graph.flat();
        if (f != null) ensureCapacity(f.n());
        for (int i = 0; i < presentSize; i++) {
            count[present[i]] = 0;
            inPresent[present[i]] = false;
        }
        presentSize = 0;
        head = 0;
        fillFrom(inventory, graph);
    }

    /**
     * Grows backing arrays instead of dropping the refill (the old
     * {@code if (count.length < f.n()) return;} silently left stale state on model growth).
     */
    public void ensureCapacity(int needed) {
        if (needed <= count.length) return;
        int cap = Math.max(needed, count.length * 2);
        int[] newCount = new int[cap];
        System.arraycopy(count, 0, newCount, 0, count.length);
        int[] newPresent = new int[cap];
        System.arraycopy(present, 0, newPresent, 0, presentSize);
        boolean[] newInPresent = new boolean[cap];
        for (int i = 0; i < presentSize; i++) newInPresent[newPresent[i]] = true;
        int[] newLog = new int[Math.max(cap * 2, 4096)];
        System.arraycopy(log, 0, newLog, 0, Math.min(head, newLog.length));
        this.count = newCount;
        this.present = newPresent;
        this.inPresent = newInPresent;
        this.log = newLog;
    }

    private void fillFrom(Map<Item, Integer> inventory, RecipeGraph graph) {
        GraphFlatData f = graph.flat();
        if (f == null) return;
        // Single idMap.get per entry, counts written directly, then one int[] sort for the
        // deterministic present order. No ArrayList, no Comparator lambda hashing per comparison
        // (the old comparingInt+getOrDefault hashed O(n log n) times per reset).
        int touched = 0;
        int[] ids = new int[inventory.size()];
        for (Map.Entry<Item, Integer> e : inventory.entrySet()) {
            Integer id = f.idMap().get(e.getKey());
            if (id == null || id < 0 || id >= count.length) continue;
            count[id] = e.getValue();
            ids[touched++] = id;
        }
        Arrays.sort(ids, 0, touched);
        for (int k = 0; k < touched; k++) {
            int id = ids[k];
            if (count[id] > 0 && !inPresent[id]) {
                inPresent[id] = true;
                present[presentSize++] = id;
            }
        }
    }

    public int get(int id) {
        return id >= 0 && id < count.length ? count[id] : 0;
    }

    /** Applies a delta (negative = consume, positive = produce) and journals it. */
    public void add(int id, int delta) {
        if (id < 0 || id >= count.length || delta == 0) return;
        ensureLog(2);
        log[head] = id;
        log[head + 1] = delta;
        head += 2;
        int before = count[id];
        count[id] += delta;
        if (before <= 0 && count[id] > 0 && !inPresent[id]) {
            inPresent[id] = true;
            // present is append-only by design (rollback keeps ever-positive ids for
            // deterministic scans); capacity is count.length so this cannot overflow.
            present[presentSize++] = id;
        }
    }

    public void consume(int id, int amt) {
        if (amt != 0) add(id, -amt);
    }

    public void produce(int id, int amt) {
        if (amt != 0) add(id, amt);
    }

    public int mark() {
        return head;
    }

    public void rollbackTo(int mark) {
        while (head > mark) {
            head -= 2;
            count[log[head]] -= log[head + 1];
        }
    }

    public int presentSize() {
        return presentSize;
    }

    public int presentIdAt(int i) {
        return present[i];
    }

    private void ensureLog(int extra) {
        if (head + extra <= log.length) return;
        int newLen = log.length * 2;
        if (newLen < head + extra) newLen = head + extra;
        int[] grown = new int[newLen];
        System.arraycopy(log, 0, grown, 0, head);
        log = grown;
    }
}
