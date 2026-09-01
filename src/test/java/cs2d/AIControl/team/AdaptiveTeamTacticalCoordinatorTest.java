package cs2d.AIControl.team;

import cs2d.AIControl.team.TacticalOrder.Role;
import cs2d.AIControl.team.TacticalOrder.TaskType;
import cs2d.AIControl.team.TeamTacticalSnapshot.AgentSnapshot;
import cs2d.AIControl.team.TeamTacticalSnapshot.ContactSnapshot;
import cs2d.AIControl.team.TeamTacticalSnapshot.ContactType;
import cs2d.AIControl.team.TeamTacticalSnapshot.HazardSnapshot;
import cs2d.AIControl.team.TeamTacticalSnapshot.HazardType;
import cs2d.AIControl.team.TeamTacticalSnapshot.Vec2;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdaptiveTeamTacticalCoordinatorTest {

    private final AdaptiveTeamTacticalCoordinator coordinator = new AdaptiveTeamTacticalCoordinator();

    @Test
    void singleAgentReceivesUsefulOrderWithoutFixedSquadRequirement() {
        long now = 10_000L;
        AgentSnapshot solo = agent("solo", 100, 100, "route-a", points(100, 100, 600, 100));

        Map<String, TacticalOrder> orders = coordinator.coordinate(snapshot(now, List.of(solo), List.of(), List.of()));

        assertEquals(1, orders.size());
        assertEquals(Role.ANCHOR, orders.get("solo").role());
        assertEquals(TaskType.CONTROL_ROUTE, orders.get("solo").taskType());
    }

    @Test
    void twoAgentsStayWithinTradeDistanceInsteadOfSplittingAcrossMap() {
        long now = 20_000L;
        AgentSnapshot anchor = agent("a", 100, 100, "route-a", points(100, 100, 500, 100));
        AgentSnapshot partner = agent("b", 1_600, 100, "route-b", points(1_600, 100, 2_000, 100));

        Map<String, TacticalOrder> orders = coordinator.coordinate(
                snapshot(now, List.of(anchor, partner), List.of(), List.of()));

        TacticalOrder partnerOrder = orders.get("b");
        assertEquals(TaskType.SUPPORT, partnerOrder.taskType());
        assertEquals("a", partnerOrder.supportTargetId());
        assertEquals(new Vec2(100, 100), partnerOrder.movementTarget());
    }

    @Test
    void largeTeamSendsOnlyFourClosestAgentsToGunshot() {
        long now = 30_000L;
        List<AgentSnapshot> agents = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            agents.add(agent(String.format("bot-%02d", i), 100 + i * 20, 100, null, List.of()));
        }
        ContactSnapshot shot = new ContactSnapshot("enemy", new Vec2(100, 100), ContactType.GUNSHOT, now);

        Map<String, TacticalOrder> orders = coordinator.coordinate(
                snapshot(now, agents, List.of(shot), List.of()));

        long responders = orders.values().stream()
                .filter(order -> order.allowedSoundTargetIds().contains("enemy"))
                .count();
        assertEquals(4, responders);
    }

    @Test
    void manyGunshotsInSameBattleZoneStillUseOnlyOneResponseTask() {
        long now = 35_000L;
        List<AgentSnapshot> agents = new ArrayList<>();
        List<ContactSnapshot> contacts = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            agents.add(agent(String.format("bot-%02d", i), 100 + i * 20, 100, null, List.of()));
            contacts.add(new ContactSnapshot("enemy-" + i,
                    new Vec2(500 + (i % 5) * 30, 500 + (i / 5) * 30), ContactType.GUNSHOT, now));
        }

        Map<String, TacticalOrder> orders = coordinator.coordinate(
                snapshot(now, agents, contacts, List.of()));

        long responders = orders.values().stream()
                .filter(order -> !order.allowedSoundTargetIds().isEmpty())
                .count();
        assertEquals(4, responders);
        assertTrue(orders.values().stream()
                .filter(order -> !order.allowedSoundTargetIds().isEmpty())
                .allMatch(order -> order.allowedSoundTargetIds().size() == 25));
    }

    @Test
    void repeatedDeathHeatMovesOnlyNonAnchorTowardSaferRoute() {
        long now = 40_000L;
        List<Vec2> riskyRoute = points(100, 100, 300, 100, 500, 100);
        List<Vec2> safeRoute = points(700, 100, 900, 100, 1_100, 100);
        List<AgentSnapshot> agents = List.of(
                agent("a1", 100, 100, "risky", riskyRoute),
                agent("a2", 160, 100, "risky", riskyRoute),
                agent("b1", 700, 100, "safe", safeRoute),
                agent("b2", 760, 100, "safe", safeRoute));
        List<HazardSnapshot> hazards = List.of(
                new HazardSnapshot(new Vec2(250, 100), 4.0, HazardType.FRIENDLY_DEATH, now),
                new HazardSnapshot(new Vec2(300, 120), 4.0, HazardType.DROPPED_WEAPON, now));

        Map<String, TacticalOrder> orders = coordinator.coordinate(snapshot(now, agents, List.of(), hazards));

        TacticalOrder flanker = orders.get("a2");
        assertEquals(TaskType.FLANK, flanker.taskType());
        assertEquals(Role.FLANKER, flanker.role());
        assertEquals("safe", flanker.routeId());
        assertNotNull(flanker.movementTarget());
        assertEquals(Role.ANCHOR, orders.get("a1").role());
    }

    @Test
    void isolatedAgentIsOrderedToRegroupWithNearestTeammate() {
        long now = 50_000L;
        List<AgentSnapshot> agents = List.of(
                agent("a", 100, 100, null, List.of()),
                agent("b", 200, 100, null, List.of()),
                agent("c", 2_000, 100, null, List.of()));

        Map<String, TacticalOrder> orders = coordinator.coordinate(snapshot(now, agents, List.of(), List.of()));

        TacticalOrder isolated = orders.get("c");
        assertEquals(TaskType.REGROUP, isolated.taskType());
        assertEquals("b", isolated.supportTargetId());
        assertTrue(isolated.movementTarget().distanceSq(new Vec2(200, 100)) < 0.001);
    }

    private static TeamTacticalSnapshot snapshot(long now, List<AgentSnapshot> agents,
            List<ContactSnapshot> contacts, List<HazardSnapshot> hazards) {
        return new TeamTacticalSnapshot("CT", now, 4_392, 3_840, agents, contacts, hazards);
    }

    private static AgentSnapshot agent(String id, double x, double y, String routeId, List<Vec2> routePoints) {
        return new AgentSnapshot(id, new Vec2(x, y), 100, false, false, routeId, routePoints);
    }

    private static List<Vec2> points(double... coordinates) {
        List<Vec2> points = new ArrayList<>();
        for (int i = 0; i < coordinates.length; i += 2) {
            points.add(new Vec2(coordinates[i], coordinates[i + 1]));
        }
        return List.copyOf(points);
    }
}
