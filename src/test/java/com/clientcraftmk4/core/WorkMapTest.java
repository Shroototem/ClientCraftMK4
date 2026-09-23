package com.clientcraftmk4.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Journal/rollback mechanics of {@link WorkMap} — pure logic, no Minecraft
 * state (ids are arbitrary graph slots).
 */
class WorkMapTest {

    @Test
    void addConsumeProduceTrackCounts() {
        WorkMap wm = new WorkMap(16);
        wm.add(5, 3);
        assertEquals(3, wm.get(5));
        wm.consume(5, 2);
        assertEquals(1, wm.get(5));
        wm.produce(5, 4);
        assertEquals(5, wm.get(5));
    }

    @Test
    void zeroDeltaDoesNotJournalOrChangeCounts() {
        WorkMap wm = new WorkMap(16);
        int mark = wm.mark();
        wm.add(1, 0);
        wm.consume(2, 0);
        wm.produce(3, 0);
        assertEquals(mark, wm.mark());
        assertEquals(0, wm.get(1));
    }

    @Test
    void rollbackRestoresExactCounts() {
        WorkMap wm = new WorkMap(16);
        wm.add(1, 10);
        wm.add(2, 20);
        int mark = wm.mark();
        wm.consume(1, 7);
        wm.consume(2, 25);
        wm.produce(3, 9);
        wm.rollbackTo(mark);
        assertEquals(10, wm.get(1));
        assertEquals(20, wm.get(2));
        assertEquals(0, wm.get(3));
    }

    @Test
    void nestedMarksRollbackInReverseOrder() {
        WorkMap wm = new WorkMap(16);
        wm.add(1, 100);
        int outer = wm.mark();
        wm.consume(1, 30);
        int inner = wm.mark();
        wm.consume(1, 45);
        wm.rollbackTo(inner);
        assertEquals(70, wm.get(1));
        wm.rollbackTo(outer);
        assertEquals(100, wm.get(1));
    }

    @Test
    void overConsumeGoesNegativeAndRollbackRecovers() {
        WorkMap wm = new WorkMap(16);
        wm.add(4, 2);
        int mark = wm.mark();
        wm.consume(4, 5);
        assertEquals(-3, wm.get(4));
        wm.rollbackTo(mark);
        assertEquals(2, wm.get(4));
    }

    @Test
    void presentListTracksEverPositiveIdsInInsertionOrder() {
        WorkMap wm = new WorkMap(16);
        wm.add(7, 1);
        wm.add(3, 2);
        wm.add(7, 1);
        assertEquals(2, wm.presentSize());
        assertEquals(7, wm.presentIdAt(0));
        assertEquals(3, wm.presentIdAt(1));

        int mark = wm.mark();
        wm.produce(9, 5);
        assertEquals(3, wm.presentSize());
        assertEquals(9, wm.presentIdAt(2));
        wm.rollbackTo(mark);
        assertEquals(0, wm.get(9));
        assertEquals(3, wm.presentSize(), "present list keeps ids that ever held a positive count");
    }

    @Test
    void undoLogGrowsBeyondInitialCapacity() {
        WorkMap wm = new WorkMap(16);
        for (int i = 0; i < 500; i++) {
            wm.add(i % 16, 1);
            wm.consume(i % 16, 1);
        }
        for (int id = 0; id < 16; id++) {
            assertEquals(0, wm.get(id), "id " + id + " must return to zero");
        }
        assertTrue(wm.presentSize() > 0);
    }

    @Test
    void outOfRangeAndZeroIdsAreIgnored() {
        WorkMap wm = new WorkMap(16);
        int mark = wm.mark();
        wm.add(-1, 5);
        wm.add(16, 5);
        assertEquals(mark, wm.mark());
        assertEquals(0, wm.get(-1));
        assertEquals(0, wm.get(16));
    }

    @Test
    void capacityReportsConstructorValue() {
        assertEquals(64, new WorkMap(64).capacity());
        assertEquals(16, new WorkMap(1).capacity(), "capacity is floored at 16");
    }

    @Test
    void ensureCapacityGrowsInsteadOfDroppingState() {
        WorkMap wm = new WorkMap(16);
        wm.add(5, 3);
        wm.ensureCapacity(1112);
        assertTrue(wm.capacity() >= 1112);
        assertEquals(3, wm.get(5), "existing counts must survive growth");
        // High ids usable after growth (old resetTo silently no-op'd when undersized).
        wm.add(1000, 7);
        assertEquals(7, wm.get(1000));
        int mark = wm.mark();
        wm.consume(1000, 2);
        assertEquals(5, wm.get(1000));
        wm.rollbackTo(mark);
        assertEquals(7, wm.get(1000));
    }

    @Test
    void presentListSurvivesGrowth() {
        WorkMap wm = new WorkMap(16);
        wm.add(3, 1);
        wm.ensureCapacity(512);
        wm.add(400, 2);
        assertEquals(1, wm.get(3));
        assertEquals(2, wm.get(400));
        assertTrue(wm.presentSize() >= 2);
    }
}
