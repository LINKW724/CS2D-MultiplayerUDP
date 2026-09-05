package cs2d.AIControl.movement;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class MovementArbiterTest {

    private final MovementArbiter arbiter = new MovementArbiter();

    @Test
    void pathDirectionCannotBeReversedByTeammateSteering() {
        MovementDecision decision = arbiter.decide(List.of(
                MovementIntent.base("path", 100, List.of("W", "D")),
                MovementIntent.steering("avoidance", 200, List.of("S", "A"))));

        assertEquals(List.of("W", "D"), decision.keys());
        assertEquals(2, decision.suppressedOpposingInputs());
    }

    @Test
    void steeringMayUseAnAxisThatThePathDoesNotOwn() {
        MovementDecision decision = arbiter.decide(List.of(
                MovementIntent.base("path", 100, List.of("W")),
                MovementIntent.steering("avoidance", 200, List.of("A"))));

        assertEquals(List.of("W", "A"), decision.keys());
        assertEquals(List.of("path", "avoidance"), decision.contributingSources());
    }

    @Test
    void highestPriorityExclusiveIntentOwnsMovement() {
        MovementDecision decision = arbiter.decide(List.of(
                MovementIntent.base("path", 100, List.of("W")),
                MovementIntent.exclusive("grenade", 300, List.of("D")),
                MovementIntent.exclusive("unstuck", 500, List.of("S", "A"))));

        assertEquals(List.of("S", "A"), decision.keys());
        assertEquals(List.of("unstuck"), decision.contributingSources());
    }

    @Test
    void contradictoryKeysFromOneProducerAreCollapsedBeforeExecution() {
        MovementDecision decision = arbiter.decide(List.of(
                MovementIntent.base("broken-producer", 100, List.of("W", "S", "A", "D"))));

        assertEquals(List.of("W", "A"), decision.keys());
        assertFalse(decision.keys().containsAll(List.of("W", "S")));
        assertFalse(decision.keys().containsAll(List.of("A", "D")));
        assertEquals(2, decision.suppressedOpposingInputs());
    }

    @Test
    void steeringCannotCreateStrategicMovementWithoutABaseIntent() {
        MovementDecision decision = arbiter.decide(List.of(
                MovementIntent.steering("avoidance", 200, List.of("A"))));

        assertEquals(MovementDecision.idle(), decision);
    }

    @Test
    void explicitCorridorStopOwnsMovementWithoutInventingKeys() {
        MovementDecision decision = arbiter.decide(List.of(
                MovementIntent.base("path", 100, List.of("W")),
                MovementIntent.stop("corridor-queue", 250)));

        assertEquals(List.of(), decision.keys());
        assertEquals(List.of("corridor-queue"), decision.contributingSources());
    }
}
