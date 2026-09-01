package cs2d.AIControl.BG;

import cs2d.AIControl.team.TacticalOrder;
import cs2d.AIControl.team.TeamTacticalSnapshot.Vec2;

/** Keeps non-visual pursuit stable and bounded without affecting sight combat. */
final class SoundPursuitGate {

    static final long TARGET_LOCK_MS = 1_600L;
    static final long MAX_PURSUIT_MS = 4_000L;

    private String responseZoneKey;
    private long responseStartedAt;
    private String lockedTargetId;
    private long targetLockUntil;

    void updateOrder(TacticalOrder order, long now) {
        String nextKey = responseZoneKey(order);
        if (nextKey == null) {
            reset();
            return;
        }
        if (!nextKey.equals(responseZoneKey)) {
            responseZoneKey = nextKey;
            responseStartedAt = now;
            lockedTargetId = null;
            targetLockUntil = 0L;
        }
    }

    boolean canPursue(TacticalOrder order, long now) {
        String key = responseZoneKey(order);
        return key == null || (key.equals(responseZoneKey) && now - responseStartedAt <= MAX_PURSUIT_MS);
    }

    boolean acceptsTarget(String enemyId, long now) {
        return enemyId != null
                && (lockedTargetId == null || enemyId.equals(lockedTargetId) || now >= targetLockUntil);
    }

    void lockTarget(String enemyId, long now) {
        if (enemyId == null) {
            return;
        }
        if (!enemyId.equals(lockedTargetId) || now >= targetLockUntil) {
            lockedTargetId = enemyId;
            targetLockUntil = now + TARGET_LOCK_MS;
        }
    }

    String lockedTargetId(long now) {
        return now < targetLockUntil ? lockedTargetId : null;
    }

    void clearTargetLock() {
        lockedTargetId = null;
        targetLockUntil = 0L;
    }

    void reset() {
        responseZoneKey = null;
        responseStartedAt = 0L;
        clearTargetLock();
    }

    private static String responseZoneKey(TacticalOrder order) {
        if (order == null || order.taskType() != TacticalOrder.TaskType.RESPOND_TO_CONTACT) {
            return null;
        }
        Vec2 target = order.movementTarget();
        if (target != null) {
            return Double.doubleToLongBits(target.x()) + ":" + Double.doubleToLongBits(target.y());
        }
        return order.taskId();
    }
}
