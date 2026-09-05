package cs2d.AIControl.team;

import cs2d.AIControl.team.TacticalOrder.Posture;
import cs2d.AIControl.team.TacticalOrder.Role;
import cs2d.AIControl.team.TacticalOrder.TaskType;
import cs2d.AIControl.team.TeamTacticalSnapshot.Vec2;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TacticalPostureCommitmentBoardTest {

    private final TacticalPostureCommitmentBoard board = new TacticalPostureCommitmentBoard();

    @Test
    void completeIntentRewriteWaitsUntilCommitmentExpires() {
        long now = 1_000L;
        TacticalOrder stealth = order(Posture.STEALTH_ADVANCE, TaskType.FLANK,
                "route-a", new Vec2(400, 100), now);
        TacticalOrder first = board.reconcile("CT", Map.of("bot", stealth), now).get("bot");
        TacticalOrder rush = order(Posture.RUSH, TaskType.ADVANCE,
                "route-b", new Vec2(900, 300), now + 250L);

        TacticalOrder locked = board.reconcile("CT", Map.of("bot", rush), now + 250L).get("bot");

        assertEquals(Posture.STEALTH_ADVANCE, locked.posture());
        assertEquals(TaskType.FLANK, locked.taskType());
        assertEquals("route-a", locked.routeId());
        assertEquals(new Vec2(400, 100), locked.movementTarget());
        assertEquals(first.postureCommitUntil(), locked.postureCommitUntil());
        assertTrue(first.postureCommitUntil() >= now + 3_000L);
        assertTrue(first.postureCommitUntil() <= now + 6_000L);

        TacticalOrder afterExpiry = board.reconcile("CT", Map.of("bot", rush),
                first.postureCommitUntil() + 1L).get("bot");
        assertEquals(Posture.RUSH, afterExpiry.posture());
        assertEquals("route-b", afterExpiry.routeId());
        assertEquals(new Vec2(900, 300), afterExpiry.movementTarget());
    }

    @Test
    void uncertainContactCanImmediatelyDowngradeRushToStealth() {
        long now = 2_000L;
        board.reconcile("T", Map.of("bot", order(Posture.RUSH, TaskType.ADVANCE,
                "route-a", new Vec2(400, 100), now)), now);

        TacticalOrder response = board.reconcile("T", Map.of("bot",
                order(Posture.STEALTH_ADVANCE, TaskType.RESPOND_TO_CONTACT,
                        "route-b", new Vec2(900, 300), now + 250L)),
                now + 250L).get("bot");

        assertEquals(Posture.STEALTH_ADVANCE, response.posture());
        assertEquals(TaskType.ADVANCE, response.taskType());
        assertEquals("route-a", response.routeId());
        assertEquals(new Vec2(400, 100), response.movementTarget());
    }

    private static TacticalOrder order(Posture posture, TaskType taskType,
            String routeId, Vec2 target, long now) {
        return new TacticalOrder("bot", taskType, Role.ENTRY, routeId, null,
                target, true, 100.0, Set.of(), 0.0, 0.0,
                now + 10_000L, "task", posture, 0L);
    }
}
