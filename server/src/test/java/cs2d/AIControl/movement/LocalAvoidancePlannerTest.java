package cs2d.AIControl.movement;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LocalAvoidancePlannerTest {

    @Test
    void alternatingRepulsionCannotFlipDirectionInsideCommitmentWindow() {
        LocalAvoidancePlanner planner = new LocalAvoidancePlanner();

        assertEquals(List.of("A"), keys(planner.plan(1_000, "bot-1", List.of("W"),
                observation(-1.0, 0.0))));
        assertEquals(List.of("A"), keys(planner.plan(1_100, "bot-1", List.of("W"),
                observation(1.0, 0.0))));
        assertEquals(List.of("A"), keys(planner.plan(1_500, "bot-1", List.of("W"),
                observation(1.0, 0.0))));
    }

    @Test
    void oppositeDirectionRequiresSustainedConfirmationAfterHold() {
        LocalAvoidancePlanner planner = new LocalAvoidancePlanner();
        planner.plan(1_000, "bot-1", List.of("W"), observation(-1.0, 0.0));

        assertEquals(List.of("A"), keys(planner.plan(1_600, "bot-1", List.of("W"),
                observation(1.0, 0.0))));
        assertEquals(List.of("A"), keys(planner.plan(1_849, "bot-1", List.of("W"),
                observation(1.0, 0.0))));
        assertEquals(List.of("D"), keys(planner.plan(1_850, "bot-1", List.of("W"),
                observation(1.0, 0.0))));
    }

    @Test
    void botWithRightOfWayDoesNotReceiveAvoidanceInput() {
        LocalAvoidancePlanner planner = new LocalAvoidancePlanner();

        MovementIntent intent = planner.plan(1_000, "bot-1", List.of("W"),
                new LocalAvoidancePlanner.Observation(-1.0, 0.0, 2, false, true));

        assertEquals(List.of(), intent.keys());
    }

    @Test
    void steeringUsesOnlyTheAxisPerpendicularToRoute() {
        LocalAvoidancePlanner planner = new LocalAvoidancePlanner();

        assertEquals(List.of("W"), keys(planner.plan(1_000, "bot-1", List.of("D"),
                new LocalAvoidancePlanner.Observation(5.0, -1.0, 1, true, true))));
        assertEquals(List.of(), keys(planner.plan(1_100, "bot-1", List.of("W", "D"),
                observation(-1.0, -1.0))));
    }

    @Test
    void resetRemovesPreviousSteeringCommitment() {
        LocalAvoidancePlanner planner = new LocalAvoidancePlanner();
        planner.plan(1_000, "bot-1", List.of("W"), observation(-1.0, 0.0));

        planner.reset();

        assertEquals(List.of("D"), keys(planner.plan(1_100, "bot-1", List.of("W"),
                observation(1.0, 0.0))));
    }

    @Test
    void emergencyUnstuckKeepsItsSideAcrossRapidRetries() {
        LocalAvoidancePlanner planner = new LocalAvoidancePlanner();

        int first = planner.chooseUnstuckSide(1_000, "bot-1");
        int retry = planner.chooseUnstuckSide(2_500, "bot-1");
        int prolongedFailure = planner.chooseUnstuckSide(5_600, "bot-1");

        assertEquals(first, retry);
        assertEquals(-first, prolongedFailure);
    }

    @Test
    void weakNearbyRepulsionDoesNotCauseUnnecessarySidestep() {
        LocalAvoidancePlanner planner = new LocalAvoidancePlanner();

        MovementIntent intent = planner.plan(1_000, "bot-1", List.of("W"),
                new LocalAvoidancePlanner.Observation(0.2, 0.0, 1, true, false));

        assertEquals(List.of(), intent.keys());
    }

    @Test
    void narrowCorridorQueuesInsteadOfPushingIntoWalls() {
        LocalAvoidancePlanner planner = new LocalAvoidancePlanner();

        MovementIntent intent = planner.plan(1_000, "bot-2", List.of("W"),
                new LocalAvoidancePlanner.Observation(1.0, 0.0, 1, true, true, false, false));

        assertEquals(MovementIntent.CompositionMode.EXCLUSIVE, intent.mode());
        assertEquals("corridor-queue", intent.sourceId());
        assertEquals(List.of(), intent.keys());
    }

    @Test
    void blockedPreferredSideUsesTheOnlyOpenSide() {
        LocalAvoidancePlanner planner = new LocalAvoidancePlanner();

        MovementIntent intent = planner.plan(1_000, "bot-2", List.of("W"),
                new LocalAvoidancePlanner.Observation(-1.0, 0.0, 1, true, true, false, true));

        assertEquals(List.of("D"), intent.keys());
    }

    private static LocalAvoidancePlanner.Observation observation(double x, double y) {
        return new LocalAvoidancePlanner.Observation(x, y, 1, true, true);
    }

    private static List<String> keys(MovementIntent intent) {
        return intent.keys();
    }
}
