package cs2d.AIControl.movement;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LocomotionFacingPolicyTest {

    @Test
    void sharpShortReversalBackpedalsWithoutTurningAway() {
        LocomotionFacingPolicy policy = new LocomotionFacingPolicy();

        // Path angle is deliberately still smoothed near zero; movement already points left.
        assertEquals(0.0, policy.chooseFacing(1_000, 0.0, 0.08, List.of("A")), 0.0001);
        assertEquals(0.0, policy.chooseFacing(1_600, 0.0, 0.16, List.of("A")), 0.0001);
    }

    @Test
    void longReversalEventuallyTurnsToTravelDirection() {
        LocomotionFacingPolicy policy = new LocomotionFacingPolicy();
        policy.chooseFacing(1_000, 0.0, Math.PI, List.of("A"));

        assertEquals(Math.PI, policy.chooseFacing(1_901, 0.0, Math.PI, List.of("A")), 0.0001);
    }

    @Test
    void ordinaryPathTurnStillFacesTheRoute() {
        LocomotionFacingPolicy policy = new LocomotionFacingPolicy();

        assertEquals(Math.PI / 2.0,
                policy.chooseFacing(1_000, 0.0, Math.PI / 2.0, List.of("S")), 0.0001);
    }
}
