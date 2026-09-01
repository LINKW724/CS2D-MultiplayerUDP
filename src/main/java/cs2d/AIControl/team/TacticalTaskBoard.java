package cs2d.AIControl.team;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Stateful lifecycle boundary between pure planning and order publication.
 * It enforces task capacity without coupling the coordinator to server state.
 */
public final class TacticalTaskBoard {

    private final Map<String, TaskState> states = new HashMap<>();

    public ReconciledPlan reconcile(String teamId, TacticalPlan proposed, long now) {
        return reconcile(teamId, proposed, null, now);
    }

    public ReconciledPlan reconcile(String teamId, TacticalPlan proposed,
            TeamTacticalSnapshot snapshot, long now) {
        TacticalPlan safePlan = proposed == null ? new TacticalPlan(List.of(), Map.of()) : proposed;
        if (safePlan.tasks().isEmpty()) {
            return new ReconciledPlan(List.of(), activeOrders(safePlan.orders(), now), Map.copyOf(states));
        }

        String safeTeamId = teamId == null ? "" : teamId;
        Map<String, TacticalTask> tasksById = new LinkedHashMap<>();
        safePlan.tasks().stream()
                .sorted(Comparator.comparingInt(TacticalTask::priority).reversed()
                        .thenComparing(TacticalTask::taskId))
                .forEach(task -> tasksById.putIfAbsent(task.taskId(), task));

        Set<String> presentKeys = new HashSet<>();
        Map<String, TacticalOrder> acceptedOrders = new LinkedHashMap<>();
        List<TacticalTask> acceptedTasks = new ArrayList<>();
        Map<String, List<TacticalOrder>> assignmentsByTask = new HashMap<>();
        for (TacticalTask task : tasksById.values()) {
            List<TacticalOrder> assigned = safePlan.orders().values().stream()
                    .filter(order -> order != null && task.taskId().equals(order.taskId()))
                    .filter(order -> order.isActive(now))
                    .sorted(Comparator.comparing(TacticalOrder::agentId))
                    .limit(task.maximumAgents())
                    .toList();
            assignmentsByTask.put(task.taskId(), assigned);
        }
        Map<String, Boolean> operationReady = operationReadiness(
                safeTeamId, tasksById.values(), assignmentsByTask, now);
        Set<String> failedOperations = failedOperations(safeTeamId, tasksById.values());
        Set<String> completedOperations = completedOperations(
                tasksById.values(), assignmentsByTask, snapshot);

        for (TacticalTask task : tasksById.values()) {
            String stateKey = stateKey(safeTeamId, task.taskId());
            presentKeys.add(stateKey);
            TaskState previous = states.get(stateKey);

            List<TacticalOrder> assigned = assignmentsByTask.getOrDefault(task.taskId(), List.of());
            Set<String> assignedAgentIds = assigned.stream()
                    .map(TacticalOrder::agentId)
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
            boolean completedMovementHasNewAssignment = previous != null
                    && previous.status() == TaskStatus.COMPLETED
                    && isFiniteMovementTask(task.taskType())
                    && !previous.assignedAgentIds().equals(assignedAgentIds);

            TaskStatus status;
            if (task.isExpired(now)) {
                status = TaskStatus.EXPIRED;
            } else if (task.operationId() != null && failedOperations.contains(task.operationId())) {
                status = TaskStatus.FAILED;
            } else if (previous != null && previous.status() == TaskStatus.FAILED) {
                status = previous.status();
            } else if (previous != null && previous.status() == TaskStatus.COMPLETED
                    && !completedMovementHasNewAssignment) {
                status = previous.status();
            } else if (task.operationId() != null && completedOperations.contains(task.operationId())) {
                status = TaskStatus.COMPLETED;
            } else if (task.operationId() != null
                    && !operationReady.getOrDefault(task.operationId(), false)) {
                status = TaskStatus.PROPOSED;
            } else if (assigned.size() < task.minimumAgents()) {
                status = TaskStatus.PROPOSED;
            } else if (hasReachedObjective(task, assigned, snapshot)) {
                status = TaskStatus.COMPLETED;
            } else {
                status = TaskStatus.ACTIVE;
            }

            long changedAt = previous != null && previous.status() == status ? previous.statusChangedAt() : now;
            states.put(stateKey, new TaskState(safeTeamId, task, status, assignedAgentIds, changedAt));

            if (status == TaskStatus.ACTIVE) {
                acceptedTasks.add(task);
                for (TacticalOrder order : assigned) {
                    acceptedOrders.putIfAbsent(order.agentId(), order);
                }
            }
        }

        for (Map.Entry<String, TaskState> entry : new ArrayList<>(states.entrySet())) {
            TaskState state = entry.getValue();
            if (!state.teamId().equals(safeTeamId) || presentKeys.contains(entry.getKey())) {
                continue;
            }
            TaskStatus terminal = state.task().isExpired(now) ? TaskStatus.EXPIRED : TaskStatus.CANCELLED;
            if (state.status() != terminal) {
                states.put(entry.getKey(), new TaskState(safeTeamId, state.task(), terminal, Set.of(), now));
            }
        }

        for (TacticalOrder order : safePlan.orders().values()) {
            if (order != null && order.taskId() == null && order.isActive(now)) {
                acceptedOrders.putIfAbsent(order.agentId(), order);
            }
        }

        return new ReconciledPlan(List.copyOf(acceptedTasks), Map.copyOf(acceptedOrders), statesFor(safeTeamId));
    }

    private Map<String, Boolean> operationReadiness(String teamId, java.util.Collection<TacticalTask> tasks,
            Map<String, List<TacticalOrder>> assignmentsByTask, long now) {
        Map<String, List<TacticalTask>> operations = new HashMap<>();
        for (TacticalTask task : tasks) {
            if (task.operationId() != null) {
                operations.computeIfAbsent(task.operationId(), ignored -> new ArrayList<>()).add(task);
            }
        }
        Map<String, Boolean> result = new HashMap<>();
        for (Map.Entry<String, List<TacticalTask>> entry : operations.entrySet()) {
            boolean ready = entry.getValue().stream().allMatch(task -> {
                TaskState previous = states.get(stateKey(teamId, task.taskId()));
                boolean alreadyComplete = previous != null && previous.status() == TaskStatus.COMPLETED;
                int assigned = assignmentsByTask.getOrDefault(task.taskId(), List.of()).size();
                return !task.isExpired(now) && (alreadyComplete || assigned >= task.minimumAgents());
            });
            result.put(entry.getKey(), ready);
        }
        return result;
    }

    private Set<String> failedOperations(String teamId, java.util.Collection<TacticalTask> tasks) {
        Set<String> result = new HashSet<>();
        for (TacticalTask task : tasks) {
            if (task.operationId() == null) {
                continue;
            }
            TaskState previous = states.get(stateKey(teamId, task.taskId()));
            if (previous != null && previous.status() == TaskStatus.FAILED) {
                result.add(task.operationId());
            }
        }
        return result;
    }

    private Set<String> completedOperations(java.util.Collection<TacticalTask> tasks,
            Map<String, List<TacticalOrder>> assignmentsByTask, TeamTacticalSnapshot snapshot) {
        Map<String, List<TacticalTask>> flankLegs = new HashMap<>();
        for (TacticalTask task : tasks) {
            if (task.operationId() != null && task.taskType() == TacticalOrder.TaskType.FLANK) {
                flankLegs.computeIfAbsent(task.operationId(), ignored -> new ArrayList<>()).add(task);
            }
        }
        Set<String> completed = new HashSet<>();
        for (Map.Entry<String, List<TacticalTask>> entry : flankLegs.entrySet()) {
            boolean allFlankLegsArrived = entry.getValue().stream().allMatch(task ->
                    hasReachedObjective(task, assignmentsByTask.getOrDefault(task.taskId(), List.of()), snapshot));
            if (allFlankLegsArrived) {
                completed.add(entry.getKey());
            }
        }
        return completed;
    }

    public void complete(String teamId, String taskId, long now) {
        setTerminalStatus(teamId, taskId, TaskStatus.COMPLETED, now);
    }

    public void fail(String teamId, String taskId, long now) {
        setTerminalStatus(teamId, taskId, TaskStatus.FAILED, now);
    }

    public Map<String, TaskState> statesFor(String teamId) {
        String safeTeamId = teamId == null ? "" : teamId;
        Map<String, TaskState> result = new LinkedHashMap<>();
        states.values().stream()
                .filter(state -> state.teamId().equals(safeTeamId))
                .sorted(Comparator.comparing(state -> state.task().taskId()))
                .forEach(state -> result.put(state.task().taskId(), state));
        return Map.copyOf(result);
    }

    public void clear() {
        states.clear();
    }

    private void setTerminalStatus(String teamId, String taskId, TaskStatus status, long now) {
        String safeTeamId = teamId == null ? "" : teamId;
        String key = stateKey(safeTeamId, taskId);
        TaskState current = states.get(key);
        if (current != null) {
            states.put(key, new TaskState(safeTeamId, current.task(), status,
                    current.assignedAgentIds(), now));
        }
    }

    private static Map<String, TacticalOrder> activeOrders(Map<String, TacticalOrder> orders, long now) {
        Map<String, TacticalOrder> result = new LinkedHashMap<>();
        orders.values().stream()
                .filter(order -> order != null && order.isActive(now))
                .sorted(Comparator.comparing(TacticalOrder::agentId))
                .forEach(order -> result.putIfAbsent(order.agentId(), order));
        return Map.copyOf(result);
    }

    private static boolean hasReachedObjective(TacticalTask task, List<TacticalOrder> assigned,
            TeamTacticalSnapshot snapshot) {
        if (snapshot == null || task.objectivePosition() == null || task.arrivalRadius() <= 0.0
                || !isFiniteMovementTask(task.taskType())) {
            return false;
        }
        Map<String, TeamTacticalSnapshot.AgentSnapshot> agentsById = new HashMap<>();
        for (TeamTacticalSnapshot.AgentSnapshot agent : snapshot.agents()) {
            if (agent != null && agent.id() != null && agent.position() != null) {
                agentsById.put(agent.id(), agent);
            }
        }
        double arrivalRadiusSq = task.arrivalRadius() * task.arrivalRadius();
        return !assigned.isEmpty() && assigned.stream().allMatch(order -> {
            TeamTacticalSnapshot.AgentSnapshot agent = agentsById.get(order.agentId());
            return agent != null && agent.position().distanceSq(task.objectivePosition()) <= arrivalRadiusSq;
        });
    }

    private static boolean isFiniteMovementTask(TacticalOrder.TaskType type) {
        return type == TacticalOrder.TaskType.ASSEMBLE
                || type == TacticalOrder.TaskType.ADVANCE
                || type == TacticalOrder.TaskType.FLANK
                || type == TacticalOrder.TaskType.SUPPORT
                || type == TacticalOrder.TaskType.REGROUP;
    }

    private static String stateKey(String teamId, String taskId) {
        return teamId + ':' + taskId;
    }

    public enum TaskStatus {
        PROPOSED,
        ACTIVE,
        COMPLETED,
        FAILED,
        EXPIRED,
        CANCELLED
    }

    public record TaskState(
            String teamId,
            TacticalTask task,
            TaskStatus status,
            Set<String> assignedAgentIds,
            long statusChangedAt) {
        public TaskState {
            assignedAgentIds = assignedAgentIds == null ? Set.of() : Set.copyOf(assignedAgentIds);
        }
    }

    public record ReconciledPlan(
            List<TacticalTask> activeTasks,
            Map<String, TacticalOrder> activeOrders,
            Map<String, TaskState> taskStates) {
        public ReconciledPlan {
            activeTasks = activeTasks == null ? List.of() : List.copyOf(activeTasks);
            activeOrders = activeOrders == null ? Map.of() : Map.copyOf(activeOrders);
            taskStates = taskStates == null ? Map.of() : Map.copyOf(taskStates);
        }
    }
}
