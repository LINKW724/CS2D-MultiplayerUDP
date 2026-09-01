package cs2d.AIControl.team;

import cs2d.AIControl.team.TacticalIdlePolicy.Decision;
import cs2d.AIControl.team.TacticalOrder.Role;
import cs2d.AIControl.team.TacticalOrder.TaskType;
import cs2d.AIControl.team.TeamTacticalSnapshot.Vec2;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TacticalIdlePolicyTest {

    private final TacticalIdlePolicy policy = new TacticalIdlePolicy();

    @Test
    void missingOrderMeansSilentAmbushEvenIfAnOldRouteIsStillActive() {
        assertEquals(Decision.ambush(), policy.decide(null, 1_000L, false, true));
    }

    @Test
    void passiveOrderWithoutRemainingMovementFallsBackToAmbush() {
        TacticalOrder passive = order(null, 2_000L);

        assertEquals(Decision.ambush(), policy.decide(passive, 1_000L, false, false));
        assertEquals(Decision.active(), policy.decide(passive, 1_000L, false, true));
    }

    @Test
    void movementOrderAndImmediateThreatBothBreakAmbush() {
        assertEquals(Decision.active(), policy.decide(order(new Vec2(300, 200), 2_000L),
                1_000L, false, false));
        assertEquals(Decision.active(), policy.decide(null, 1_000L, true, false));
    }

    private static TacticalOrder order(Vec2 target, long expiresAt) {
        return new TacticalOrder("bot", TaskType.ADVANCE, Role.ENTRY, "route", null,
                target, true, 100.0, Set.of(), 0.0, 0.0, expiresAt, "task");
    }
}
