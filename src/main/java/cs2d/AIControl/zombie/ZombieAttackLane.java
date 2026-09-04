package cs2d.AIControl.zombie;

import cs2d.AIControl.zombie.ZombieTacticalSnapshot.Vec;
import java.util.List;

/** One aggregated predicted zombie route and its recommended interception point. */
public record ZombieAttackLane(String id, Vec source, Vec target, Vec defensePoint, Vec watchPoint,
        int zombieCount, double pressure, long etaMillis, List<Vec> route) {
    public ZombieAttackLane {
        route = List.copyOf(route);
        zombieCount = Math.max(1, zombieCount);
        pressure = Math.max(0, pressure);
        etaMillis = Math.max(0, etaMillis);
    }
}
