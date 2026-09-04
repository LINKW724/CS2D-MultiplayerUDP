package cs2d.AIControl.zombie;

import cs2d.AIControl.zombie.ZombieTacticalSnapshot.Vec;

/** Tactical task, locomotion and engagement are independent; a moving order may still fire. */
public record ZombieTacticalOrder(String agentId, long generation, Task task, String targetId,
                                  Vec destination, Vec guardPoint, Vec watchPoint,
                                  long startedAt, long expiresAt) {
    public enum Task { GUARD_AREA, FORTIFY_LANE, REINFORCE_LANE, MOBILE_RESERVE,
        CLEAR_THREAT, HUNT_REMAINDER, COVER_RELOAD, REPOSITION, RETURN_TO_GUARD }
    public boolean active(long now) { return now <= expiresAt; }
    public double watchAngle() { return Math.floorMod(agentId.hashCode(), 12) * Math.PI / 6; }
    public ZombieTacticalOrder(String agentId, long generation, Task task, String targetId,
            Vec destination, Vec guardPoint, long startedAt, long expiresAt) {
        this(agentId, generation, task, targetId, destination, guardPoint, null, startedAt, expiresAt);
    }
}
