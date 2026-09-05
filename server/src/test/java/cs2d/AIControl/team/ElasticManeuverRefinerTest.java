package cs2d.AIControl.team;

import cs2d.AIControl.team.TacticalOrder.TaskType;
import cs2d.AIControl.team.TacticalOrder.Posture;
import cs2d.AIControl.team.TeamTacticalSnapshot.AgentSnapshot;
import cs2d.AIControl.team.TeamTacticalSnapshot.ContactSnapshot;
import cs2d.AIControl.team.TeamTacticalSnapshot.ContactType;
import cs2d.AIControl.team.TeamTacticalSnapshot.RouteSnapshot;
import cs2d.AIControl.team.TeamTacticalSnapshot.Vec2;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ElasticManeuverRefinerTest {

    private final AdaptiveTeamTacticalCoordinator baseCoordinator =
            new AdaptiveTeamTacticalCoordinator(List.of());
    private final ElasticManeuverRefiner refiner = new ElasticManeuverRefiner();

    @Test
    void threeAgentsCreateAtomicSuppressAndFlankOperation() {
        long now = 10_000L;
        RouteSnapshot main = route("main", List.of(), points(0, 0, 500, 0, 1_000, 0));
        RouteSnapshot flank = route("flank", List.of(), points(0, 500, 500, 500, 1_000, 500));
        TeamTacticalSnapshot snapshot = snapshotWithGunshot(now, List.of(main, flank), List.of(
                agent("a", 700, 0, "main"),
                agent("b", 750, 0, "main"),
                agent("c", 700, 500, "flank")));

        TacticalPlan refined = refiner.refine(snapshot, baseCoordinator.plan(snapshot));

        List<TacticalTask> operationTasks = refined.tasks().stream()
                .filter(task -> task.operationId() != null)
                .toList();
        assertEquals(2, operationTasks.size());
        assertEquals(1, operationTasks.stream().map(TacticalTask::operationId).distinct().count());
        TacticalTask suppress = operationTasks.stream()
                .filter(task -> task.taskType() == TaskType.SUPPRESS).findFirst().orElseThrow();
        TacticalTask flankTask = operationTasks.stream()
                .filter(task -> task.taskType() == TaskType.FLANK).findFirst().orElseThrow();
        assertEquals(2, suppress.minimumAgents());
        assertEquals(1, flankTask.minimumAgents());
        assertEquals(2, refined.orders().values().stream()
                .filter(order -> order.taskType() == TaskType.SUPPRESS).count());
        assertTrue(refined.orders().values().stream()
                .filter(order -> order.taskType() == TaskType.SUPPRESS)
                .allMatch(order -> order.movementTarget().x() >= 1_000.0));
        assertEquals(1, refined.orders().values().stream()
                .filter(order -> order.taskType() == TaskType.FLANK).count());
    }

    @Test
    void overlappingRoutesDoNotPretendToBePincer() {
        long now = 20_000L;
        RouteSnapshot main = route("main", List.of("same"), points(0, 0, 500, 0, 1_000, 0));
        RouteSnapshot same = route("same", List.of("main"), points(0, 20, 500, 20, 1_000, 20));
        TeamTacticalSnapshot snapshot = snapshotWithGunshot(now, List.of(main, same), List.of(
                agent("a", 700, 0, "main"),
                agent("b", 750, 0, "main"),
                agent("c", 700, 20, "same")));

        TacticalPlan base = baseCoordinator.plan(snapshot);
        TacticalPlan refined = refiner.refine(snapshot, base);

        assertTrue(refined.tasks().stream().noneMatch(task -> task.operationId() != null));
        assertEquals(base.orders(), refined.orders());
    }

    @Test
    void noGunshotDoesNotInventPincerOperation() {
        long now = 25_000L;
        RouteSnapshot main = route("main", List.of(), points(0, 0, 500, 0, 1_000, 0));
        RouteSnapshot flank = route("flank", List.of(), points(0, 500, 500, 500, 1_000, 500));
        TeamTacticalSnapshot snapshot = snapshot(now, List.of(main, flank), List.of(
                agent("a", 700, 0, "main"),
                agent("b", 750, 0, "main"),
                agent("c", 700, 500, "flank")));

        TacticalPlan base = baseCoordinator.plan(snapshot);
        TacticalPlan refined = refiner.refine(snapshot, base);

        assertTrue(refined.tasks().stream().noneMatch(task -> task.operationId() != null));
        assertEquals(base.orders(), refined.orders());
    }

    @Test
    void irrelevantRemoteGunshotDoesNotLaunchPincer() {
        long now = 27_000L;
        RouteSnapshot main = route("main", List.of(), points(0, 0, 500, 0, 1_000, 0));
        RouteSnapshot flank = route("flank", List.of(), points(0, 500, 500, 500, 1_000, 500));
        List<AgentSnapshot> agents = List.of(
                agent("a", 700, 0, "main"),
                agent("b", 750, 0, "main"),
                agent("c", 700, 500, "flank"));
        ContactSnapshot remote = new ContactSnapshot("enemy", new Vec2(5_000, 5_000),
                ContactType.GUNSHOT, now);
        TeamTacticalSnapshot snapshot = new TeamTacticalSnapshot("CT", now, 2_000, 1_200,
                List.of(main, flank), agents, List.of(remote), List.of());

        TacticalPlan base = baseCoordinator.plan(snapshot);
        TacticalPlan refined = refiner.refine(snapshot, base);

        assertTrue(refined.tasks().stream().noneMatch(task -> task.operationId() != null));
        assertEquals(base.orders(), refined.orders());
    }

    @Test
    void oneOrTwoRespawnsAssembleInsteadOfRunningAlone() {
        long now = 30_000L;
        RouteSnapshot route = route("main", List.of(), points(0, 0, 300, 0, 800, 0, 1_200, 0));
        TeamTacticalSnapshot snapshot = snapshot(now, List.of(route), List.of(
                agent("new-a", 30, 20, "main"),
                agent("new-b", 60, 20, "main"),
                agent("front-a", 900, 0, "main"),
                agent("front-b", 1_050, 0, "main")));

        TacticalPlan refined = refiner.refine(snapshot, baseCoordinator.plan(snapshot));

        assertEquals(TaskType.ASSEMBLE, refined.orders().get("new-a").taskType());
        assertEquals(TaskType.ASSEMBLE, refined.orders().get("new-b").taskType());
        assertFalse(refined.orders().get("new-a").preserveMapRoute());
        assertNotNull(refined.orders().get("new-a").movementTarget());
        assertTrue(refined.orders().values().stream()
                .filter(order -> order.taskType() == TaskType.ASSEMBLE).count() <= 2);
    }

    @Test
    void threeRespawnsLeaveAsOneWaveWithMinimumThree() {
        long now = 40_000L;
        RouteSnapshot route = route("main", List.of(), points(0, 0, 300, 0, 800, 0, 1_200, 0));
        TeamTacticalSnapshot snapshot = snapshot(now, List.of(route), List.of(
                agent("new-a", 30, 20, "main"),
                agent("new-b", 60, 20, "main"),
                agent("new-c", 90, 20, "main"),
                agent("front-a", 900, 0, "main"),
                agent("front-b", 1_050, 0, "main")));

        TacticalPlan refined = refiner.refine(snapshot, baseCoordinator.plan(snapshot));

        TacticalTask wave = refined.tasks().stream()
                .filter(task -> task.operationId() != null && task.taskType() == TaskType.ADVANCE)
                .findFirst().orElseThrow();
        assertEquals(3, wave.minimumAgents());
        assertEquals(5, wave.maximumAgents());
        assertEquals(3, refined.orders().values().stream()
                .filter(order -> wave.taskId().equals(order.taskId())).count());
        assertTrue(refined.orders().values().stream()
                .filter(order -> wave.taskId().equals(order.taskId()))
                .allMatch(order -> order.posture() == Posture.RUSH));
    }

    private static TeamTacticalSnapshot snapshot(long now, List<RouteSnapshot> routes,
            List<AgentSnapshot> agents) {
        return new TeamTacticalSnapshot("CT", now, 2_000, 1_200,
                routes, agents, List.of(), List.of());
    }

    private static TeamTacticalSnapshot snapshotWithGunshot(long now, List<RouteSnapshot> routes,
            List<AgentSnapshot> agents) {
        ContactSnapshot gunshot = new ContactSnapshot("enemy", new Vec2(900, 250),
                ContactType.GUNSHOT, now);
        return new TeamTacticalSnapshot("CT", now, 2_000, 1_200,
                routes, agents, List.of(gunshot), List.of());
    }

    private static AgentSnapshot agent(String id, double x, double y, String routeId) {
        return new AgentSnapshot(id, new Vec2(x, y), 100, false, false, routeId, List.of());
    }

    private static RouteSnapshot route(String id, List<String> overlaps, List<Vec2> points) {
        return new RouteSnapshot(id, "TDM_CT_T", points, 1_200.0, 0, overlaps);
    }

    private static List<Vec2> points(double... coordinates) {
        java.util.ArrayList<Vec2> result = new java.util.ArrayList<>();
        for (int i = 0; i < coordinates.length; i += 2) {
            result.add(new Vec2(coordinates[i], coordinates[i + 1]));
        }
        return List.copyOf(result);
    }
}
