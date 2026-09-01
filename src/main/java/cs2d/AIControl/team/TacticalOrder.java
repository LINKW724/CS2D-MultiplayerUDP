package cs2d.AIControl.team;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/** Generic high-level intent. It contains no key presses or concrete AI module references. */
public record TacticalOrder(
        String agentId,
        TaskType taskType,
        Role role,
        String routeId,
        String supportTargetId,
        TeamTacticalSnapshot.Vec2 movementTarget,
        boolean preserveMapRoute,
        double arrivalRadius,
        Set<String> allowedSoundTargetIds,
        double maxSoundResponseDistance,
        double routeRisk,
        long expiresAt,
        String taskId) {

    public TacticalOrder {
        allowedSoundTargetIds = allowedSoundTargetIds == null
                ? Set.of()
                : Collections.unmodifiableSet(new HashSet<>(allowedSoundTargetIds));
        taskType = taskType == null ? TaskType.CONTROL_ROUTE : taskType;
        role = role == null ? Role.FREE : role;
        arrivalRadius = Math.max(0.0, arrivalRadius);
        maxSoundResponseDistance = Math.max(0.0, maxSoundResponseDistance);
        taskId = taskId == null || taskId.isBlank() ? null : taskId;
    }

    /** Compatibility constructor for order-only coordinators. */
    public TacticalOrder(String agentId, TaskType taskType, Role role, String routeId,
            String supportTargetId, TeamTacticalSnapshot.Vec2 movementTarget, boolean preserveMapRoute,
            double arrivalRadius, Set<String> allowedSoundTargetIds, double maxSoundResponseDistance,
            double routeRisk, long expiresAt) {
        this(agentId, taskType, role, routeId, supportTargetId, movementTarget, preserveMapRoute,
                arrivalRadius, allowedSoundTargetIds, maxSoundResponseDistance, routeRisk, expiresAt, null);
    }

    public TacticalOrder withTaskId(String newTaskId) {
        return new TacticalOrder(agentId, taskType, role, routeId, supportTargetId, movementTarget,
                preserveMapRoute, arrivalRadius, allowedSoundTargetIds, maxSoundResponseDistance,
                routeRisk, expiresAt, newTaskId);
    }

    public boolean isActive(long now) {
        return expiresAt <= 0 || now <= expiresAt;
    }

    public boolean allowsSoundTarget(String enemyId, double distance) {
        return enemyId != null
                && distance <= maxSoundResponseDistance
                && allowedSoundTargetIds.contains(enemyId);
    }

    public enum TaskType {
        CONTROL_ROUTE,
        ADVANCE,
        SUPPORT,
        REGROUP,
        FLANK,
        RESPOND_TO_CONTACT,
        SUPPRESS,
        ASSEMBLE
    }

    public enum Role {
        FREE,
        ENTRY,
        SUPPORT,
        ANCHOR,
        FLANKER,
        RESERVE,
        SUPPRESSOR
    }
}
