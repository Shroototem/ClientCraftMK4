package com.clientcraftmk4.core.algorithms;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CraftPlannerClicksTest {

    @Test
    void clicksCoverCraftsByStackSize() {
        assertEquals(8, CraftPlanner.directClicks(500, 64, 0));
        assertEquals(1, CraftPlanner.directClicks(10, 64, 0));
        assertEquals(3, CraftPlanner.directClicks(3, 1, 0));
        assertEquals(1, CraftPlanner.directClicks(64, 64, 0));
        assertEquals(2, CraftPlanner.directClicks(65, 64, 0));
    }

    @Test
    void extraClicksAddedAndClampedToCrafts() {
        assertEquals(11, CraftPlanner.directClicks(50, 64, 10));
        assertEquals(1, CraftPlanner.directClicks(1, 64, 5));
    }

    @Test
    void zeroStackSizeIsTreatedAsOne() {
        assertEquals(5, CraftPlanner.directClicks(5, 0, 0));
    }
}
