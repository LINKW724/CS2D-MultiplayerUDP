package cs2d.AIControl.team;

import cs2d.AIControl.team.TacticalOrder.Posture;
import cs2d.AIControl.team.TacticalOrder.Role;
import cs2d.AIControl.team.TacticalOrder.TaskType;
import cs2d.AIControl.team.TeamTacticalSnapshot.Vec2;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TdmPostureDoctrineTest {

    private final TdmPostureDoctrine doctrine = new TdmPostureDoctrine();

    @Test
    void onlyEntryAdvanceRushes() {
        assertEquals(Posture.RUSH, doctrine.choose(order(TaskType.ADVANCE, Role.ENTRY)));
        assertEquals(Posture.STEALTH_ADVANCE, doctrine.choose(order(TaskType.ADVANCE, Role.SUPPORT)));
        assertEquals(Posture.STEALTH_ADVANCE,
                doctrine.choose(order(TaskType.RESPOND_TO_CONTACT, Role.SUPPORT)));
        assertEquals(Posture.STEALTH_ADVANCE, doctrine.choose(order(TaskType.FLANK, Role.FLANKER)));
    }

    private static TacticalOrder order(TaskType taskType, Role role) {
        return new TacticalOrder("bot", taskType, role, "route-a", null,
                new Vec2(500, 0), true, 100.0, Set.of(), 0.0, 0.0,
                2_000L, "task");
    }
}
