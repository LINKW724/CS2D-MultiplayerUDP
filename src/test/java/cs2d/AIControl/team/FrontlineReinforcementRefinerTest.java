package cs2d.AIControl.team;

import cs2d.AIControl.team.TacticalOrder.Posture;
import cs2d.AIControl.team.TacticalOrder.Role;
import cs2d.AIControl.team.TacticalOrder.TaskType;
import cs2d.AIControl.team.TeamTacticalSnapshot.AgentSnapshot;
import cs2d.AIControl.team.TeamTacticalSnapshot.ContactSnapshot;
import cs2d.AIControl.team.TeamTacticalSnapshot.ContactType;
import cs2d.AIControl.team.TeamTacticalSnapshot.RouteSnapshot;
import cs2d.AIControl.team.TeamTacticalSnapshot.Vec2;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FrontlineReinforcementRefinerTest {

    private final FrontlineReinforcementRefiner refiner = new FrontlineReinforcementRefiner();

    @Test
    void rearAgentRushesAlongFriendlyControlledRoute() {
        TacticalPlan refined = refine(List.of(agent("front", 900, false), agent("rear", 200, false)),
                List.of(), Map.of("front", order("front", Role.ENTRY), "rear", order("rear", Role.SUPPORT)));

        assertEquals(Posture.STEALTH_ADVANCE, refined.orders().get("front").posture());
        assertEquals(Posture.RUSH, refined.orders().get("rear").posture());
    }

    @Test
    void rushingAgentKeepsRunningUntilItReachesSafeFollowingDistance() {
        refine(List.of(agent("front", 900, false), agent("rear", 200, false)), List.of(),
                Map.of("front", order("front", Role.ENTRY), "rear", order("rear", Role.SUPPORT)));

        TacticalPlan stillRushing = refine(
                List.of(agent("front", 900, false), agent("rear", 500, false)), List.of(),
                Map.of("front", order("front", Role.ENTRY), "rear", order("rear", Role.SUPPORT)));
        TacticalPlan caughtUp = refine(
                List.of(agent("front", 900, false), agent("rear", 650, false)), List.of(),
                Map.of("front", order("front", Role.ENTRY), "rear", order("rear", Role.SUPPORT)));

        assertEquals(Posture.RUSH, stillRushing.orders().get("rear").posture());
        assertEquals(Posture.STEALTH_ADVANCE, caughtUp.orders().get("rear").posture());
    }

    @Test
    void damageOrNearbyContactPreventsBlindRush() {
        ContactSnapshot nearby = new ContactSnapshot("enemy", new Vec2(220, 0), ContactType.GUNSHOT, 1_000L);
        TacticalPlan damaged = refine(
                List.of(agent("front", 900, false), agent("rear", 200, true)), List.of(),
                Map.of("front", order("front", Role.ENTRY), "rear", order("rear", Role.SUPPORT)));
        TacticalPlan contact = refine(
                List.of(agent("front", 900, false), agent("rear", 200, false)), List.of(nearby),
                Map.of("front", order("front", Role.ENTRY), "rear", order("rear", Role.SUPPORT)));

        assertEquals(Posture.STEALTH_ADVANCE, damaged.orders().get("rear").posture());
        assertEquals(Posture.STEALTH_ADVANCE, contact.orders().get("rear").posture());
    }

    @Test
    void anchorDoesNotAbandonItsQuietAssignment() {
        TacticalPlan refined = refine(List.of(agent("front", 900, false), agent("rear", 200, false)),
                List.of(), Map.of("front", order("front", Role.ENTRY), "rear", order("rear", Role.ANCHOR)));

        assertEquals(Posture.STEALTH_ADVANCE, refined.orders().get("rear").posture());
    }

    private TacticalPlan refine(List<AgentSnapshot> agents, List<ContactSnapshot> contacts,
            Map<String, TacticalOrder> orders) {
        RouteSnapshot route = new RouteSnapshot("route-a", "TDM_CT_T",
                List.of(new Vec2(0, 0), new Vec2(500, 0), new Vec2(1_000, 0)),
                1_000.0, 4, List.of());
        TeamTacticalSnapshot snapshot = new TeamTacticalSnapshot("CT", 1_000L, 1_600, 900,
                List.of(route), agents, contacts, List.of());
        return refiner.refine(snapshot, new TacticalPlan(List.of(), orders));
    }

    private static AgentSnapshot agent(String id, double x, boolean recentlyDamaged) {
        return new AgentSnapshot(id, new Vec2(x, 0), 100, false, recentlyDamaged,
                "route-a", List.of());
    }

    private static TacticalOrder order(String id, Role role) {
        return new TacticalOrder(id, TaskType.ADVANCE, role, "route-a", null,
                new Vec2(1_000, 0), true, 180.0, Set.of(), 0.0, 0.0,
                10_000L, "route-task", Posture.STEALTH_ADVANCE, 0L);
    }
}
