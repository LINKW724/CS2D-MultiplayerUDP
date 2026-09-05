package cs2d.AIControl.zombie;

import cs2d.AIControl.zombie.ZombieCrowdFirePlanner.Target;
import cs2d.AIControl.zombie.ZombieTacticalSnapshot.Vec;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ZombieCrowdFirePlannerTest {
    private final ZombieCrowdFirePlanner planner = new ZombieCrowdFirePlanner();
    private final Vec origin = new Vec(0, 0);

    @Test void choosesRayThatCanPenetrateSeveralZombiesOverCloserIsolatedTarget() {
        var decision = planner.select(origin, List.of(target("front", 300, 0),
                target("middle", 500, 4), target("rear", 700, -4),
                target("isolated", 240, 180)), 1_000);
        assertEquals("front", decision.targetId());
        assertEquals(3, decision.rayHits());
    }

    @Test void immediateZombieOverridesHigherYieldDistantRay() {
        var decision = planner.select(origin, List.of(target("urgent", 100, 80),
                target("front", 400, 0), target("rear", 650, 0)), 1_000);
        assertEquals("urgent", decision.targetId());
    }

    @Test void wideCrowdSweepsTargetsWithoutStartingANewEngagement() {
        List<Target> fan = List.of(target("left", 500, -120), target("middle", 500, 0),
                target("right", 500, 120));
        var first = planner.select(origin, fan, 1_000);
        var held = planner.select(origin, fan, 1_300);
        var swept = planner.select(origin, fan, 1_600);
        assertEquals(first.targetId(), held.targetId());
        assertNotEquals(held.targetId(), swept.targetId());
        assertEquals(first.engagementId(), swept.engagementId());
    }

    @Test void losingEveryVisibleMemberStartsANewEngagement() {
        var first = planner.select(origin, List.of(target("a", 500, 0)), 1_000);
        var next = planner.select(origin, List.of(target("b", 500, 0)), 1_100);
        assertNotEquals(first.engagementId(), next.engagementId());
    }

    @Test void weaponInterruptionCannotReuseAStaleEngagementKey() {
        var first = planner.select(origin, List.of(target("a", 500, 0)), 1_000);
        planner.reset();
        var resumed = planner.select(origin, List.of(target("a", 500, 0)), 1_100);
        assertNotEquals(first.engagementId(), resumed.engagementId());
    }

    private static Target target(String id, double x, double y) {
        return new Target(id, new Vec(x, y), 500);
    }
}
