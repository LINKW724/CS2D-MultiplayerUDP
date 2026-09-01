package cs2d.AIControl.team;

import cs2d.AIControl.team.TacticalOrder.Role;
import cs2d.AIControl.team.TacticalOrder.TaskType;
import cs2d.AIControl.team.TeamTacticalSnapshot.AgentSnapshot;
import cs2d.AIControl.team.TeamTacticalSnapshot.ContactSnapshot;
import cs2d.AIControl.team.TeamTacticalSnapshot.ContactType;
import cs2d.AIControl.team.TeamTacticalSnapshot.HazardSnapshot;
import cs2d.AIControl.team.TeamTacticalSnapshot.HazardType;
import cs2d.AIControl.team.TeamTacticalSnapshot.RouteSnapshot;
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
    void bootstrapAssignsExplicitAuthoredRoutesAndEndpointsToWholeSpawnWave() {
        long now = 9_000L;
        AdaptiveTeamTacticalCoordinator bootstrapCoordinator = new AdaptiveTeamTacticalCoordinator(
                new BalancedRouteAssignmentPolicy(), List.of());
        List<AgentSnapshot> agents = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            agents.add(agent(String.format("bot-%02d", i), 2_466 + i, 1_086, null, List.of()));
        }
        List<RouteSnapshot> routes = List.of(
                route("route-0", points(2_466, 1_086, 1_700, 4_230)),
                route("route-1", points(2_466, 1_086, 1_600, 4_230)),
                route("route-2", points(2_466, 1_086, 1_500, 4_230)),
                route("route-3", points(2_466, 1_086, 1_400, 4_230)),
                route("route-4", points(2_466, 1_086, 1_300, 4_230)));
        TeamTacticalSnapshot snapshot = new TeamTacticalSnapshot("CT", now, 4_392, 4_500,
                routes, agents, List.of(), List.of());

        TacticalPlan plan = bootstrapCoordinator.plan(snapshot);
        Map<String, Long> routeCounts = plan.orders().values().stream()
                .collect(java.util.stream.Collectors.groupingBy(TacticalOrder::routeId,
                        java.util.stream.Collectors.counting()));

        assertEquals(10, plan.orders().size());
        assertEquals(5, routeCounts.size());
        assertTrue(routeCounts.values().stream().allMatch(count -> count == 2L));
        assertTrue(plan.orders().values().stream().allMatch(order ->
                order.taskType() == TaskType.ADVANCE
                        && order.routeId() != null
                        && order.movementTarget() != null
                        && order.preserveMapRoute()));
        assertTrue(plan.orders().values().stream().allMatch(order -> routes.stream()
                .filter(route -> route.routeId().equals(order.routeId()))
                .anyMatch(route -> route.keyPoints().get(route.keyPoints().size() - 1)
                        .equals(order.movementTarget()))));
    }

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
    void largeTeamSendsOnlyThreeNearbyAgentsToGunshot() {
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
        assertEquals(3, responders);
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
        assertEquals(3, responders);
        assertTrue(orders.values().stream()
                .filter(order -> !order.allowedSoundTargetIds().isEmpty())
                .allMatch(order -> order.allowedSoundTargetIds().size() == 25));

        TacticalPlan plan = coordinator.plan(snapshot(now, agents, contacts, List.of()));
        List<TacticalTask> responseTasks = plan.tasks().stream()
                .filter(task -> task.taskType() == TaskType.RESPOND_TO_CONTACT)
                .toList();
        assertEquals(1, responseTasks.size());
        assertEquals(1, responseTasks.get(0).minimumAgents());
        assertEquals(3, responseTasks.get(0).maximumAgents());
        assertEquals(3, plan.orders().values().stream()
                .filter(order -> responseTasks.get(0).taskId().equals(order.taskId()))
                .count());
    }

    @Test
    void ignoresGunshotThatCannotBeReachedWithinParticipationWindow() {
        long now = 36_000L;
        List<AgentSnapshot> agents = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            agents.add(agent("bot-" + i, i * 20, 0, null, List.of()));
        }
        ContactSnapshot remote = new ContactSnapshot("enemy", new Vec2(2_000, 0),
                ContactType.GUNSHOT, now);

        TacticalPlan plan = coordinator.plan(snapshot(now, agents, List.of(remote), List.of()));

        assertTrue(plan.orders().values().stream()
                .noneMatch(order -> order.taskType() == TaskType.RESPOND_TO_CONTACT));
    }

    @Test
    void ignoresRemoteSoundOutsideOrBehindAuthoredRouteButAcceptsNearbySound() {
        long now = 36_500L;
        List<Vec2> route = points(0, 0, 500, 0, 1_000, 0);
        List<AgentSnapshot> agents = List.of(
                agent("a", 900, 0, "route", route),
                agent("b", 930, 0, "route", route),
                agent("c", 960, 0, "route", route));
        ContactSnapshot behind = new ContactSnapshot("behind", new Vec2(100, 0),
                ContactType.GUNSHOT, now);
        ContactSnapshot offRoute = new ContactSnapshot("off-route", new Vec2(600, 700),
                ContactType.GUNSHOT, now);

        TacticalPlan rejected = coordinator.plan(snapshot(now, agents, List.of(behind, offRoute), List.of()));
        assertTrue(rejected.orders().values().stream()
                .noneMatch(order -> order.taskType() == TaskType.RESPOND_TO_CONTACT));

        ContactSnapshot nearby = new ContactSnapshot("nearby", new Vec2(700, 0),
                ContactType.GUNSHOT, now);
        TacticalPlan accepted = coordinator.plan(snapshot(now, agents, List.of(nearby), List.of()));
        assertTrue(accepted.orders().values().stream()
                .anyMatch(order -> order.taskType() == TaskType.RESPOND_TO_CONTACT));
    }

    @Test
    void everyAdaptiveOrderBelongsToAnExplicitBoundedTask() {
        long now = 37_000L;
        List<AgentSnapshot> agents = List.of(
                agent("a", 100, 100, "route-a", points(100, 100, 500, 100)),
                agent("b", 180, 100, "route-a", points(100, 100, 500, 100)),
                agent("c", 900, 100, "route-b", points(900, 100, 1_200, 100)));

        TacticalPlan plan = coordinator.plan(snapshot(now, agents, List.of(), List.of()));

        assertEquals(agents.size(), plan.orders().size());
        assertTrue(plan.orders().values().stream().allMatch(order -> order.taskId() != null));
        assertTrue(plan.tasks().stream().allMatch(task -> task.minimumAgents() >= 1
                && task.maximumAgents() >= task.minimumAgents()));
        assertTrue(plan.orders().values().stream().allMatch(order -> plan.tasks().stream()
                .anyMatch(task -> task.taskId().equals(order.taskId()))));
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
    void canAssignAnAuthoredRouteThatCurrentlyHasNoAgents() {
        long now = 45_000L;
        List<Vec2> riskyRoute = points(100, 100, 300, 100, 500, 100);
        List<Vec2> occupiedRoute = points(650, 100, 850, 100, 1_050, 100);
        List<Vec2> unusedSafeRoute = points(700, 500, 900, 500, 1_100, 500);
        List<AgentSnapshot> agents = List.of(
                agent("a1", 100, 100, "risky", riskyRoute),
                agent("a2", 150, 100, "risky", riskyRoute),
                agent("b1", 650, 100, "occupied", occupiedRoute),
                agent("b2", 700, 100, "occupied", occupiedRoute));
        List<RouteSnapshot> routes = List.of(
                route("risky", riskyRoute),
                route("occupied", occupiedRoute),
                route("unused-safe", unusedSafeRoute));
        List<HazardSnapshot> hazards = List.of(
                new HazardSnapshot(new Vec2(300, 100), 5.0, HazardType.FRIENDLY_DEATH, now));
        TeamTacticalSnapshot snapshot = new TeamTacticalSnapshot("CT", now, 4_392, 3_840,
                routes, agents, List.of(), hazards);

        Map<String, TacticalOrder> orders = coordinator.coordinate(snapshot);

        assertTrue(orders.values().stream().anyMatch(order -> order.taskType() == TaskType.FLANK
                && "unused-safe".equals(order.routeId())));
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

    private static RouteSnapshot route(String id, List<Vec2> points) {
        return new RouteSnapshot(id, "TDM_CT_T", points, 1_000.0, 0, List.of());
    }

    private static List<Vec2> points(double... coordinates) {
        List<Vec2> points = new ArrayList<>();
        for (int i = 0; i < coordinates.length; i += 2) {
            points.add(new Vec2(coordinates[i], coordinates[i + 1]));
        }
        return List.copyOf(points);
    }
}
