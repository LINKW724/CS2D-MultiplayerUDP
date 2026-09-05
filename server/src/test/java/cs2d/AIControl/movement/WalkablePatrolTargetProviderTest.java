package cs2d.AIControl.movement;

import org.junit.jupiter.api.Test;

import java.awt.geom.Point2D;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WalkablePatrolTargetProviderTest {
    @Test
    void returnsOnlyWalkableTargetsFarEnoughFromOrigin() {
        WalkablePatrolTargetProvider provider = new WalkablePatrolTargetProvider("bot-a");
        Point2D.Double origin = new Point2D.Double(10, 10);
        PatrolTargetProvider.PatrolContext context = new PatrolTargetProvider.PatrolContext(
                "bot-a", origin, 1_000, 1_000, point -> point.x > 500);

        Point2D.Double target = provider.nextTarget(context).orElseThrow();

        assertTrue(target.x > 500);
        assertTrue(origin.distance(target) >= WalkablePatrolTargetProvider.MIN_PATROL_DISTANCE);
    }

    @Test
    void differentAgentsDoNotShareTheSameDeterministicSequence() {
        PatrolTargetProvider.PatrolContext context = new PatrolTargetProvider.PatrolContext(
                "bot", new Point2D.Double(0, 0), 1_000, 1_000, point -> true);
        Point2D.Double first = new WalkablePatrolTargetProvider("bot-a").nextTarget(context).orElseThrow();
        Point2D.Double second = new WalkablePatrolTargetProvider("bot-b").nextTarget(context).orElseThrow();
        assertNotEquals(first, second);
    }

    @Test
    void returnsEmptyInsteadOfIssuingAnUnwalkableTarget() {
        WalkablePatrolTargetProvider provider = new WalkablePatrolTargetProvider("bot-a");
        PatrolTargetProvider.PatrolContext context = new PatrolTargetProvider.PatrolContext(
                "bot-a", new Point2D.Double(0, 0), 1_000, 1_000, point -> false);
        assertTrue(provider.nextTarget(context).isEmpty());
    }
}
