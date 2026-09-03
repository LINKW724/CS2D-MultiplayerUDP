package cs2d.AIControl.team;

import cs2d.AIControl.team.TacticalOrder.TaskType;
import cs2d.AIControl.team.TeamTacticalSnapshot.Vec2;

import java.util.Set;

/**
 * Immutable team-level work item. A task describes intent and capacity; it does
 * not know about GameState, Player, pathfinding or concrete AI modules.
 */
public record TacticalTask(
        String taskId,
        TaskType taskType,
        String routeId,
        Vec2 objectivePosition,
        int minimumAgents,
        int maximumAgents,
        int priority,
        double risk,
        EngagementRule engagementRule,
        double arrivalRadius,
        Set<String> allowedSoundTargetIds,
        long expiresAt,
        String operationId,
        CompletionPolicy completionPolicy) {

    public TacticalTask {
        if (taskId == null || taskId.isBlank()) {
            throw new IllegalArgumentException("taskId must not be blank");
        }
        taskType = taskType == null ? TaskType.CONTROL_ROUTE : taskType;
        minimumAgents = Math.max(0, minimumAgents);
        maximumAgents = Math.max(minimumAgents, maximumAgents);
        priority = Math.max(0, priority);
        risk = Math.max(0.0, risk);
        engagementRule = engagementRule == null ? EngagementRule.LOCAL_ONLY : engagementRule;
        arrivalRadius = Math.max(0.0, arrivalRadius);
        allowedSoundTargetIds = allowedSoundTargetIds == null ? Set.of() : Set.copyOf(allowedSoundTargetIds);
        operationId = operationId == null || operationId.isBlank() ? null : operationId;
        completionPolicy = completionPolicy == null ? defaultCompletionPolicy(taskType) : completionPolicy;
    }

    /** Compatibility constructor preserving the former canonical signature. */
    public TacticalTask(String taskId, TaskType taskType, String routeId, Vec2 objectivePosition,
            int minimumAgents, int maximumAgents, int priority, double risk,
            EngagementRule engagementRule, double arrivalRadius, Set<String> allowedSoundTargetIds,
            long expiresAt, String operationId) {
        this(taskId, taskType, routeId, objectivePosition, minimumAgents, maximumAgents,
                priority, risk, engagementRule, arrivalRadius, allowedSoundTargetIds, expiresAt,
                operationId, defaultCompletionPolicy(taskType));
    }

    /** Compatibility constructor for independent tasks without an operation. */
    public TacticalTask(String taskId, TaskType taskType, String routeId, Vec2 objectivePosition,
            int minimumAgents, int maximumAgents, int priority, double risk,
            EngagementRule engagementRule, double arrivalRadius, Set<String> allowedSoundTargetIds,
            long expiresAt) {
        this(taskId, taskType, routeId, objectivePosition, minimumAgents, maximumAgents,
                priority, risk, engagementRule, arrivalRadius, allowedSoundTargetIds, expiresAt,
                null, defaultCompletionPolicy(taskType));
    }

    public boolean isExpired(long now) {
        return expiresAt > 0 && now > expiresAt;
    }

    public enum EngagementRule {
        LOCAL_ONLY,
        ASSIGNED_CONTACTS,
        IGNORE_REMOTE_SOUNDS
    }

    /** Separates one-shot destinations from intents that must track a moving situation. */
    public enum CompletionPolicy {
        ARRIVAL,
        CONTINUOUS
    }

    private static CompletionPolicy defaultCompletionPolicy(TaskType taskType) {
        return switch (taskType == null ? TaskType.CONTROL_ROUTE : taskType) {
            case ADVANCE, FLANK, ASSEMBLE -> CompletionPolicy.ARRIVAL;
            case CONTROL_ROUTE, SUPPORT, REGROUP, RESPOND_TO_CONTACT, SUPPRESS -> CompletionPolicy.CONTINUOUS;
        };
    }
}
