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
        String taskId,
        Posture posture,
        long postureCommitUntil,
        LocomotionDirective locomotionDirective) {

    public TacticalOrder {
        allowedSoundTargetIds = allowedSoundTargetIds == null
                ? Set.of()
                : Collections.unmodifiableSet(new HashSet<>(allowedSoundTargetIds));
        taskType = taskType == null ? TaskType.CONTROL_ROUTE : taskType;
        role = role == null ? Role.FREE : role;
        arrivalRadius = Math.max(0.0, arrivalRadius);
        maxSoundResponseDistance = Math.max(0.0, maxSoundResponseDistance);
        taskId = taskId == null || taskId.isBlank() ? null : taskId;
        posture = posture == null ? Posture.STEALTH_ADVANCE : posture;
        postureCommitUntil = Math.max(0L, postureCommitUntil);
        locomotionDirective = locomotionDirective == null
                ? inferLocomotionDirective(taskType, routeId, movementTarget, preserveMapRoute)
                : locomotionDirective;
    }

    /** Compatibility constructor preserving the former canonical signature. */
    public TacticalOrder(String agentId, TaskType taskType, Role role, String routeId,
            String supportTargetId, TeamTacticalSnapshot.Vec2 movementTarget, boolean preserveMapRoute,
            double arrivalRadius, Set<String> allowedSoundTargetIds, double maxSoundResponseDistance,
            double routeRisk, long expiresAt, String taskId, Posture posture, long postureCommitUntil) {
        this(agentId, taskType, role, routeId, supportTargetId, movementTarget, preserveMapRoute,
                arrivalRadius, allowedSoundTargetIds, maxSoundResponseDistance, routeRisk, expiresAt,
                taskId, posture, postureCommitUntil, null);
    }

    /** Compatibility constructor used by existing coordinators and tests. */
    public TacticalOrder(String agentId, TaskType taskType, Role role, String routeId,
            String supportTargetId, TeamTacticalSnapshot.Vec2 movementTarget, boolean preserveMapRoute,
            double arrivalRadius, Set<String> allowedSoundTargetIds, double maxSoundResponseDistance,
            double routeRisk, long expiresAt, String taskId) {
        this(agentId, taskType, role, routeId, supportTargetId, movementTarget, preserveMapRoute,
                arrivalRadius, allowedSoundTargetIds, maxSoundResponseDistance, routeRisk, expiresAt,
                taskId, defaultPosture(), 0L, null);
    }

    /** Compatibility constructor for order-only coordinators. */
    public TacticalOrder(String agentId, TaskType taskType, Role role, String routeId,
            String supportTargetId, TeamTacticalSnapshot.Vec2 movementTarget, boolean preserveMapRoute,
            double arrivalRadius, Set<String> allowedSoundTargetIds, double maxSoundResponseDistance,
            double routeRisk, long expiresAt) {
        this(agentId, taskType, role, routeId, supportTargetId, movementTarget, preserveMapRoute,
                arrivalRadius, allowedSoundTargetIds, maxSoundResponseDistance, routeRisk, expiresAt,
                null, defaultPosture(), 0L, null);
    }

    public TacticalOrder withTaskId(String newTaskId) {
        return new TacticalOrder(agentId, taskType, role, routeId, supportTargetId, movementTarget,
                preserveMapRoute, arrivalRadius, allowedSoundTargetIds, maxSoundResponseDistance,
                routeRisk, expiresAt, newTaskId, posture, postureCommitUntil, locomotionDirective);
    }

    public TacticalOrder withPosture(Posture newPosture, long commitUntil) {
        return new TacticalOrder(agentId, taskType, role, routeId, supportTargetId, movementTarget,
                preserveMapRoute, arrivalRadius, allowedSoundTargetIds, maxSoundResponseDistance,
                routeRisk, expiresAt, taskId, newPosture, commitUntil, locomotionDirective);
    }

    public TacticalOrder withMovementTarget(TeamTacticalSnapshot.Vec2 newMovementTarget) {
        return new TacticalOrder(agentId, taskType, role, routeId, supportTargetId, newMovementTarget,
                preserveMapRoute, arrivalRadius, allowedSoundTargetIds, maxSoundResponseDistance,
                routeRisk, expiresAt, taskId, posture, postureCommitUntil,
                inferLocomotionDirective(taskType, routeId, newMovementTarget, preserveMapRoute));
    }

    public TacticalOrder withExpiry(long newExpiresAt) {
        return new TacticalOrder(agentId, taskType, role, routeId, supportTargetId, movementTarget,
                preserveMapRoute, arrivalRadius, allowedSoundTargetIds, maxSoundResponseDistance,
                routeRisk, newExpiresAt, taskId, posture, postureCommitUntil, locomotionDirective);
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

    /** Mutually-exclusive team posture. Local direct sight may temporarily promote it to ENGAGE. */
    public enum Posture {
        STEALTH_ADVANCE,
        RUSH,
        AMBUSH,
        ENGAGE
    }

    public enum LocomotionDirective {
        FOLLOW_AUTHORED_ROUTE,
        MOVE_TO_TARGET,
        FREE_PATROL,
        HOLD_POSITION
    }

    static LocomotionDirective inferLocomotionDirective(TaskType taskType, String routeId,
            TeamTacticalSnapshot.Vec2 movementTarget, boolean preserveMapRoute) {
        if (movementTarget != null) {
            return preserveMapRoute && routeId != null && !routeId.isBlank()
                    ? LocomotionDirective.FOLLOW_AUTHORED_ROUTE
                    : LocomotionDirective.MOVE_TO_TARGET;
        }
        if (preserveMapRoute && routeId != null && !routeId.isBlank())
            return LocomotionDirective.FOLLOW_AUTHORED_ROUTE;
        return taskType == TaskType.CONTROL_ROUTE && (routeId == null || routeId.isBlank())
                ? LocomotionDirective.FREE_PATROL
                : LocomotionDirective.HOLD_POSITION;
    }

    private static Posture defaultPosture() {
        return Posture.STEALTH_ADVANCE;
    }
}
