package cs2d.AIControl.BG;

import cs2d.AIControl.team.TacticalOrder;
import cs2d.AIControl.team.TacticalOrder.Role;
import cs2d.AIControl.team.TacticalOrder.TaskType;
import cs2d.AIControl.team.TeamTacticalSnapshot.Vec2;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SoundPursuitGateTest {

    private final SoundPursuitGate gate = new SoundPursuitGate();

    @Test
    void locksOneSoundTargetBeforeAllowingAnother() {
        long now = 1_000L;
        TacticalOrder order = responseOrder("task-a", new Vec2(320, 480), now);
        gate.updateOrder(order, now);
        gate.lockTarget("enemy-a", now);

        assertTrue(gate.acceptsTarget("enemy-a", now + 100L));
        assertFalse(gate.acceptsTarget("enemy-b", now + 100L));
        assertTrue(gate.acceptsTarget("enemy-b", now + SoundPursuitGate.TARGET_LOCK_MS));
    }

    @Test
    void stopsPursuingSameBattleZoneAfterFourSeconds() {
        long now = 2_000L;
        TacticalOrder first = responseOrder("task-a", new Vec2(320, 480), now);
        gate.updateOrder(first, now);

        TacticalOrder refreshedTtl = responseOrder("task-b", new Vec2(320, 480), now + 3_000L);
        gate.updateOrder(refreshedTtl, now + 3_000L);

        assertTrue(gate.canPursue(refreshedTtl, now + SoundPursuitGate.MAX_PURSUIT_MS));
        assertFalse(gate.canPursue(refreshedTtl, now + SoundPursuitGate.MAX_PURSUIT_MS + 1L));
    }

    @Test
    void newBattleZoneStartsFreshAndNonResponseOrderResetsGate() {
        long now = 3_000L;
        TacticalOrder first = responseOrder("task-a", new Vec2(320, 480), now);
        gate.updateOrder(first, now);
        assertFalse(gate.canPursue(first, now + SoundPursuitGate.MAX_PURSUIT_MS + 1L));

        TacticalOrder newZone = responseOrder("task-b", new Vec2(960, 480), now + 5_000L);
        gate.updateOrder(newZone, now + 5_000L);
        assertTrue(gate.canPursue(newZone, now + 5_000L));

        gate.updateOrder(null, now + 5_001L);
        assertNull(gate.lockedTargetId(now + 5_001L));
    }

    private static TacticalOrder responseOrder(String taskId, Vec2 target, long now) {
        return new TacticalOrder("bot", TaskType.RESPOND_TO_CONTACT, Role.SUPPORT, null, null,
                target, false, 240.0, Set.of("enemy-a", "enemy-b"), 900.0, 0.0,
                now + 1_200L, taskId);
    }
}
