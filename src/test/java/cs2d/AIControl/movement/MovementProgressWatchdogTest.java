package cs2d.AIControl.movement;

import org.junit.jupiter.api.Test;

import java.awt.geom.Point2D;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MovementProgressWatchdogTest {

    @Test
    void confirmsBlockageOnlyAfterThreeFailedWindows() {
        MovementProgressWatchdog watchdog = new MovementProgressWatchdog();
        Point2D.Double stationary = new Point2D.Double(0.0, 0.0);

        assertFalse(watchdog.observe(1_000, stationary, List.of("D"), 100.0, true).blocked());
        assertFalse(watchdog.observe(1_200, stationary, List.of("D"), 100.0, true).blocked());
        assertFalse(watchdog.observe(1_400, stationary, List.of("D"), 100.0, true).blocked());
        MovementProgressWatchdog.Assessment confirmed =
                watchdog.observe(1_600, stationary, List.of("D"), 100.0, true);

        assertTrue(confirmed.evaluated());
        assertTrue(confirmed.blocked());
    }

    @Test
    void progressAlongIssuedDirectionPreventsFalseBlockage() {
        MovementProgressWatchdog watchdog = new MovementProgressWatchdog();

        watchdog.observe(1_000, new Point2D.Double(0.0, 0.0), List.of("D"), 100.0, true);
        MovementProgressWatchdog.Assessment assessment = watchdog.observe(1_200,
                new Point2D.Double(18.0, 0.0), List.of("D"), 100.0, true);

        assertTrue(assessment.evaluated());
        assertFalse(assessment.blocked());
    }

    @Test
    void lateralMotionDoesNotMasqueradeAsForwardProgress() {
        MovementProgressWatchdog watchdog = new MovementProgressWatchdog();

        watchdog.observe(1_000, new Point2D.Double(0.0, 0.0), List.of("D"), 100.0, true);
        MovementProgressWatchdog.Assessment assessment = watchdog.observe(1_200,
                new Point2D.Double(0.0, 20.0), List.of("D"), 100.0, true);

        assertTrue(assessment.evaluated());
        assertTrue(assessment.progressRatio() < 0.18);
    }

    @Test
    void intentionalIdleResetsFailureHistory() {
        MovementProgressWatchdog watchdog = new MovementProgressWatchdog();
        Point2D.Double stationary = new Point2D.Double(0.0, 0.0);

        watchdog.observe(1_000, stationary, List.of("D"), 100.0, true);
        watchdog.observe(1_200, stationary, List.of("D"), 100.0, true);
        watchdog.observe(1_400, stationary, List.of(), 0.0, false);
        watchdog.observe(1_600, stationary, List.of("D"), 100.0, true);
        MovementProgressWatchdog.Assessment assessment =
                watchdog.observe(1_800, stationary, List.of("D"), 100.0, true);

        assertFalse(assessment.blocked());
    }
}
