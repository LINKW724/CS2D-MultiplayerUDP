package cs2d.AIControl.team;

import cs2d.AIControl.team.TacticalOrder.Posture;
import cs2d.AIControl.team.TacticalOrder.Role;
import cs2d.AIControl.team.TacticalOrder.TaskType;
import cs2d.AIControl.team.TeamTacticalSnapshot.Vec2;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TacticalPostureExecutionPolicyTest {

    private final TacticalPostureExecutionPolicy policy = new TacticalPostureExecutionPolicy();

    @Test
    void hearingOrRememberingEnemyCannotEnterEngage() {
        TacticalOrder rush = order(Posture.RUSH);

        TacticalPostureExecutionPolicy.Decision decision = policy.decide(
                rush, 1_000L, false, true, false);

        assertEquals(Posture.RUSH, decision.posture());
        assertFalse(decision.allowCombatMovement());
    }

    @Test
    void visibleTargetMustAlsoBeInsideEffectiveRange() {
        TacticalOrder rush = order(Posture.RUSH);

        assertEquals(Posture.STEALTH_ADVANCE,
                policy.decide(rush, 1_000L, true, false, false).posture());
        assertEquals(Posture.ENGAGE,
                policy.decide(rush, 1_000L, true, true, false).posture());
        assertTrue(policy.decide(rush, 1_000L, true, true, false).allowCombatMovement());
    }

    @Test
    void arrivalAndMissingTaskBecomeSilentAmbush() {
        TacticalPostureExecutionPolicy.Decision arrived = policy.decide(
                order(Posture.STEALTH_ADVANCE), 1_000L, false, false, true);

        assertEquals(Posture.AMBUSH, arrived.posture());
        assertTrue(arrived.holdPosition());
        assertTrue(arrived.walkSilently());
        assertEquals(Posture.AMBUSH,
                policy.decide(null, 1_000L, false, false, false).posture());
    }

    private static TacticalOrder order(Posture posture) {
        return new TacticalOrder("bot", TaskType.ADVANCE, Role.ENTRY, "route-a", null,
                new Vec2(400, 100), true, 100.0, Set.of(), 0.0, 0.0,
                2_000L, "task", posture, 1_500L);
    }
}
