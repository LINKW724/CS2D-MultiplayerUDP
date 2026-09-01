package cs2d.AIControl.team;

import cs2d.AIControl.team.TacticalOrder.Role;
import cs2d.AIControl.team.TacticalOrder.TaskType;
import cs2d.AIControl.team.TacticalTask.EngagementRule;
import cs2d.AIControl.team.TacticalTaskBoard.TaskStatus;
import cs2d.AIControl.team.TeamTacticalSnapshot.AgentSnapshot;
import cs2d.AIControl.team.TeamTacticalSnapshot.Vec2;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TacticalTaskBoardTest {

    private final TacticalTaskBoard board = new TacticalTaskBoard();

    @Test
    void taskBelowMinimumStaysProposedAndPublishesNoOrders() {
        long now = 1_000L;
        TacticalTask task = task("pinch", 2, 3, now + 1_000L);
        TacticalPlan plan = new TacticalPlan(List.of(task), Map.of("a", order("a", "pinch", now)));

        TacticalTaskBoard.ReconciledPlan result = board.reconcile("CT", plan, now);

        assertTrue(result.activeOrders().isEmpty());
        assertEquals(TaskStatus.PROPOSED, result.taskStates().get("pinch").status());
    }

    @Test
    void taskActivatesAtMinimumAndCapsAssignmentsAtMaximum() {
        long now = 2_000L;
        TacticalTask task = task("response", 2, 2, now + 1_000L);
        TacticalPlan plan = new TacticalPlan(List.of(task), Map.of(
                "c", order("c", "response", now),
                "a", order("a", "response", now),
                "b", order("b", "response", now)));

        TacticalTaskBoard.ReconciledPlan result = board.reconcile("CT", plan, now);

        assertEquals(Set.of("a", "b"), result.activeOrders().keySet());
        assertEquals(TaskStatus.ACTIVE, result.taskStates().get("response").status());
        assertEquals(Set.of("a", "b"), result.taskStates().get("response").assignedAgentIds());
    }

    @Test
    void expiredAndExplicitlyCompletedTasksStopPublishingOrders() {
        long now = 3_000L;
        TacticalTask activeTask = task("flank", 1, 2, now + 1_000L);
        TacticalPlan activePlan = new TacticalPlan(List.of(activeTask), Map.of("a", order("a", "flank", now)));

        assertFalse(board.reconcile("T", activePlan, now).activeOrders().isEmpty());
        board.complete("T", "flank", now + 1L);
        TacticalTask refreshedTask = task("flank", 1, 2, now + 2_000L);
        TacticalPlan refreshedPlan = new TacticalPlan(List.of(refreshedTask),
                Map.of("a", order("a", "flank", now + 2L)));
        TacticalTaskBoard.ReconciledPlan completed = board.reconcile("T", refreshedPlan, now + 2L);
        assertTrue(completed.activeOrders().isEmpty());
        assertEquals(TaskStatus.COMPLETED, completed.taskStates().get("flank").status());

        TacticalTask expiredTask = task("old", 1, 1, now - 1L);
        TacticalTaskBoard.ReconciledPlan expired = board.reconcile("CT",
                new TacticalPlan(List.of(expiredTask), Map.of("b", order("b", "old", now))), now);
        assertEquals(TaskStatus.EXPIRED, expired.taskStates().get("old").status());
        assertTrue(expired.activeOrders().isEmpty());
    }

    @Test
    void orderOnlyCoordinatorRemainsCompatible() {
        long now = 4_000L;
        TacticalOrder legacy = order("legacy", null, now);

        TacticalTaskBoard.ReconciledPlan result = board.reconcile("CT",
                TacticalPlan.ordersOnly(Map.of("legacy", legacy)), now);

        assertEquals(legacy, result.activeOrders().get("legacy"));
    }

    @Test
    void finiteMovementTaskCompletesWhenAllAssignedAgentsReachObjective() {
        long now = 5_000L;
        TacticalTask task = task("regroup", 1, 2, now + 1_000L);
        TacticalPlan plan = new TacticalPlan(List.of(task), Map.of("a", order("a", "regroup", now)));
        AgentSnapshot arrived = new AgentSnapshot("a", new Vec2(105, 105), 100,
                false, false, null, List.of());
        TeamTacticalSnapshot snapshot = new TeamTacticalSnapshot("CT", now, 1_600, 900,
                List.of(arrived), List.of(), List.of());

        TacticalTaskBoard.ReconciledPlan result = board.reconcile("CT", plan, snapshot, now);

        assertTrue(result.activeOrders().isEmpty());
        assertEquals(TaskStatus.COMPLETED, result.taskStates().get("regroup").status());
    }

    @Test
    void advanceAndAssemblyCompleteAfterAssignedAgentsActuallyArrive() {
        long now = 5_500L;
        for (TaskType type : List.of(TaskType.ADVANCE, TaskType.ASSEMBLE)) {
            TacticalTaskBoard localBoard = new TacticalTaskBoard();
            String taskId = type.name().toLowerCase();
            TacticalTask task = new TacticalTask(taskId, type, "route-a", new Vec2(100, 100),
                    2, 2, 60, 0.0, EngagementRule.IGNORE_REMOTE_SOUNDS,
                    80.0, Set.of(), now + 1_000L);
            TacticalPlan plan = new TacticalPlan(List.of(task), Map.of(
                    "a", order("a", taskId, type, now),
                    "b", order("b", taskId, type, now)));
            TeamTacticalSnapshot snapshot = new TeamTacticalSnapshot("CT", now, 1_600, 900,
                    List.of(
                            new AgentSnapshot("a", new Vec2(95, 100), 100, false, false, null, List.of()),
                            new AgentSnapshot("b", new Vec2(105, 100), 100, false, false, null, List.of())),
                    List.of(), List.of());

            TacticalTaskBoard.ReconciledPlan result = localBoard.reconcile("CT", plan, snapshot, now);

            assertTrue(result.activeOrders().isEmpty());
            assertEquals(TaskStatus.COMPLETED, result.taskStates().get(taskId).status());
        }
    }

    @Test
    void completedAdvanceReopensWhenARespawnCreatesANewAssignment() {
        long now = 5_650L;
        TacticalTask advance = new TacticalTask("route-advance", TaskType.ADVANCE, "route-a",
                new Vec2(100, 100), 1, 2, 60, 0.0,
                EngagementRule.IGNORE_REMOTE_SOUNDS, 80.0, Set.of(), now + 2_000L);
        TacticalPlan firstLife = new TacticalPlan(List.of(advance), Map.of(
                "a", order("a", advance.taskId(), TaskType.ADVANCE, now)));
        TeamTacticalSnapshot arrived = new TeamTacticalSnapshot("CT", now, 1_600, 900,
                List.of(new AgentSnapshot("a", new Vec2(100, 100), 100,
                        false, false, "route-a", List.of())),
                List.of(), List.of());
        assertEquals(TaskStatus.COMPLETED,
                board.reconcile("CT", firstLife, arrived, now)
                        .taskStates().get(advance.taskId()).status());

        TacticalPlan respawnLife = new TacticalPlan(List.of(advance), Map.of(
                "b", order("b", advance.taskId(), TaskType.ADVANCE, now + 1L)));
        TeamTacticalSnapshot respawned = new TeamTacticalSnapshot("CT", now + 1L, 1_600, 900,
                List.of(new AgentSnapshot("b", new Vec2(0, 0), 100,
                        false, false, null, List.of())),
                List.of(), List.of());

        TacticalTaskBoard.ReconciledPlan reopened = board.reconcile(
                "CT", respawnLife, respawned, now + 1L);

        assertEquals(TaskStatus.ACTIVE, reopened.taskStates().get(advance.taskId()).status());
        assertEquals(Set.of("b"), reopened.activeOrders().keySet());
    }

    @Test
    void flankArrivalCompletesItsSuppressLegAndReleasesWholeOperation() {
        long now = 5_800L;
        String operationId = "pincer-arrived";
        TacticalTask suppress = operationTask("suppress-arrived", TaskType.SUPPRESS, 2, 2,
                operationId, now + 1_000L);
        TacticalTask flank = operationTask("flank-arrived", TaskType.FLANK, 1, 1,
                operationId, now + 1_000L);
        TacticalPlan plan = new TacticalPlan(List.of(suppress, flank), Map.of(
                "a", order("a", suppress.taskId(), TaskType.SUPPRESS, now),
                "b", order("b", suppress.taskId(), TaskType.SUPPRESS, now),
                "c", order("c", flank.taskId(), TaskType.FLANK, now)));
        TeamTacticalSnapshot snapshot = new TeamTacticalSnapshot("CT", now, 1_600, 900,
                List.of(
                        new AgentSnapshot("a", new Vec2(20, 20), 100, false, false, null, List.of()),
                        new AgentSnapshot("b", new Vec2(30, 20), 100, false, false, null, List.of()),
                        new AgentSnapshot("c", new Vec2(100, 100), 100, false, false, null, List.of())),
                List.of(), List.of());

        TacticalTaskBoard.ReconciledPlan result = board.reconcile("CT", plan, snapshot, now);

        assertTrue(result.activeOrders().isEmpty());
        assertEquals(TaskStatus.COMPLETED, result.taskStates().get(suppress.taskId()).status());
        assertEquals(TaskStatus.COMPLETED, result.taskStates().get(flank.taskId()).status());
    }

    @Test
    void compoundOperationDoesNotStartUntilEveryLegHasMinimumAgents() {
        long now = 6_000L;
        String operationId = "pincer";
        TacticalTask suppress = operationTask("suppress", TaskType.SUPPRESS, 2, 2,
                operationId, now + 1_000L);
        TacticalTask flank = operationTask("flank", TaskType.FLANK, 1, 1,
                operationId, now + 1_000L);
        TacticalPlan incomplete = new TacticalPlan(List.of(suppress, flank), Map.of(
                "a", order("a", "suppress", now),
                "b", order("b", "suppress", now)));

        TacticalTaskBoard.ReconciledPlan waiting = board.reconcile("CT", incomplete, now);

        assertTrue(waiting.activeOrders().isEmpty());
        assertEquals(TaskStatus.PROPOSED, waiting.taskStates().get("suppress").status());
        assertEquals(TaskStatus.PROPOSED, waiting.taskStates().get("flank").status());

        TacticalPlan complete = new TacticalPlan(List.of(suppress, flank), Map.of(
                "a", order("a", "suppress", now),
                "b", order("b", "suppress", now),
                "c", order("c", "flank", now)));
        TacticalTaskBoard.ReconciledPlan active = board.reconcile("CT", complete, now + 1L);
        assertEquals(Set.of("a", "b", "c"), active.activeOrders().keySet());
    }

    private static TacticalTask task(String id, int minimum, int maximum, long expiresAt) {
        return new TacticalTask(id, TaskType.FLANK, "route-a", new Vec2(100, 100), minimum, maximum,
                50, 0.0, EngagementRule.IGNORE_REMOTE_SOUNDS, 100.0, Set.of(), expiresAt);
    }

    private static TacticalTask operationTask(String id, TaskType type, int minimum, int maximum,
            String operationId, long expiresAt) {
        return new TacticalTask(id, type, "route-a", new Vec2(100, 100), minimum, maximum,
                50, 0.0, EngagementRule.IGNORE_REMOTE_SOUNDS, 100.0, Set.of(), expiresAt, operationId);
    }

    private static TacticalOrder order(String agentId, String taskId, long now) {
        return order(agentId, taskId, TaskType.FLANK, now);
    }

    private static TacticalOrder order(String agentId, String taskId, TaskType type, long now) {
        return new TacticalOrder(agentId, type, Role.FLANKER, "route-a", null,
                new Vec2(100, 100), false, 100.0, Set.of(), 1_000.0, 0.0, now + 1_000L, taskId);
    }
}
