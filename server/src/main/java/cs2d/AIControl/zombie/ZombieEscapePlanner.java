package cs2d.AIControl.zombie;

import cs2d.AIControl.zombie.ZombieTacticalSnapshot.Vec;
import java.util.List;
import java.util.function.BiPredicate;
import java.util.function.Predicate;

/** Compatibility facade for callers that only need the primary escape destination. */
public final class ZombieEscapePlanner {
    private final ZombieEscapeRoutePlanner routes = new ZombieEscapeRoutePlanner();

    public Vec choose(Vec origin, List<Vec> threats, List<Vec> teammates,
            List<ZombieAreaHazard> hazards, long now, Predicate<Vec> walkable,
            BiPredicate<Vec, Vec> traversable) {
        ZombieEscapeRoutePlanner.EscapePlan plan = routes.plan(origin, threats, teammates,
                hazards, now, walkable, traversable);
        return plan.empty() ? origin : plan.routes().get(0).destination();
    }

}
