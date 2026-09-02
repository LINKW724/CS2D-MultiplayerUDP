package cs2d.AIControl.movement;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoverScanBudgetTest {

    @Test
    void capsSynchronizedScansAndReopensOnNextWindow() {
        CoverScanBudget budget = new CoverScanBudget(2, 16);

        assertTrue(budget.tryAcquire(1_000));
        assertTrue(budget.tryAcquire(1_001));
        assertFalse(budget.tryAcquire(1_002));
        assertTrue(budget.tryAcquire(1_024));
    }
}
