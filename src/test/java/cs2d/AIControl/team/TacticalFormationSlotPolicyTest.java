package cs2d.AIControl.team;

import cs2d.AIControl.team.TacticalOrder.Role;
import cs2d.AIControl.team.TacticalOrder.TaskType;
import cs2d.AIControl.team.TeamTacticalSnapshot.AgentSnapshot;
import cs2d.AIControl.team.TeamTacticalSnapshot.RouteSnapshot;
import cs2d.AIControl.team.TeamTacticalSnapshot.Vec2;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class TacticalFormationSlotPolicyTest {

    @Test
    void agentsSharingAuthoredRouteReceiveDistinctRouteSafeSlots() {
        TacticalFormationSlotPolicy policy = new TacticalFormationSlotPolicy();
        long now = 1_000L;
        Map<String, TacticalOrder> orders = Map.of(
                "a", order("a", now),
                "b", order("b", now),
                "c", order("c", now));
        RouteSnapshot route = new RouteSnapshot("route-a", "TDM_CT_T",
                List.of(new Vec2(0, 0), new Vec2(500, 0), new Vec2(1_000, 0)),
                1_000.0, 4, List.of());
        TeamTacticalSnapshot snapshot = new TeamTacticalSnapshot("CT", now, 1_600, 900,
                List.of(route), List.of(
                        agent("a"), agent("b"), agent("c")), List.of(), List.of());

        Map<String, TacticalOrder> slotted = policy.assign(orders, snapshot);

        assertEquals(new Vec2(1_000, 0), slotted.get("a").movementTarget());
        assertEquals(new Vec2(954, 0), slotted.get("b").movementTarget());
        assertEquals(new Vec2(908, 0), slotted.get("c").movementTarget());
        assertNotEquals(slotted.get("a").movementTarget(), slotted.get("b").movementTarget());
    }

    private static TacticalOrder order(String id, long now) {
        return new TacticalOrder(id, TaskType.ADVANCE, Role.ENTRY, "route-a", null,
                new Vec2(1_000, 0), true, 180.0, Set.of(), 0.0, 0.0,
                now + 1_000L, "shared-task");
    }

    private static AgentSnapshot agent(String id) {
        return new AgentSnapshot(id, new Vec2(0, 0), 100, false, false,
                "route-a", List.of());
    }
}
