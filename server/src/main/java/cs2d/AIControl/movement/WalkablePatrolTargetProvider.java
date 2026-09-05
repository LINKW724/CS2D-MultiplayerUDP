package cs2d.AIControl.movement;

import java.awt.geom.Point2D;
import java.util.Optional;
import java.util.SplittableRandom;
import java.util.concurrent.atomic.AtomicLong;

/** Deterministic, bounded and walkability-aware fallback for maps without authored routes. */
public final class WalkablePatrolTargetProvider implements PatrolTargetProvider {
    static final int MAX_ATTEMPTS = 32;
    static final double MIN_PATROL_DISTANCE = 160.0;

    private final long agentSeed;
    private final AtomicLong objectiveSequence = new AtomicLong();

    public WalkablePatrolTargetProvider(String agentId) {
        this.agentSeed = mix(agentId == null ? 0L : agentId.hashCode());
    }

    @Override
    public Optional<Point2D.Double> nextTarget(PatrolContext context) {
        if (context == null || context.walkable() == null || context.mapWidth() <= 0 || context.mapHeight() <= 0)
            return Optional.empty();
        long sequence = objectiveSequence.getAndIncrement();
        SplittableRandom random = new SplittableRandom(mix(agentSeed + sequence));
        double minimumDistanceSq = MIN_PATROL_DISTANCE * MIN_PATROL_DISTANCE;
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            Point2D.Double candidate = new Point2D.Double(
                    random.nextDouble(context.mapWidth()), random.nextDouble(context.mapHeight()));
            if (context.origin() != null && context.origin().distanceSq(candidate) < minimumDistanceSq)
                continue;
            if (context.walkable().test(candidate))
                return Optional.of(candidate);
        }
        return Optional.empty();
    }

    private static long mix(long value) {
        value ^= value >>> 33;
        value *= 0xff51afd7ed558ccdl;
        value ^= value >>> 33;
        value *= 0xc4ceb9fe1a85ec53l;
        return value ^ (value >>> 33);
    }
}
