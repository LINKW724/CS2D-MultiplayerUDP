package cs2d.AIControl.zombie;

import cs2d.AIControl.zombie.ZombieTacticalSnapshot.Vec;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ZombiePositionPlannerTest {
    private final ZombiePositionPlanner planner = new ZombiePositionPlanner();
    @Test void walkableEndpointAcrossWallIsNotASafeRoute() {
        assertFalse(planner.safeSegment(new Vec(100, 100), new Vec(300, 100), List.of(),
                p -> p.x() < 180 || p.x() > 220));
    }
    @Test void escapeMayNotRunThroughZombieEvenIfDestinationIsFartherAway() {
        assertFalse(planner.safeSegment(new Vec(100, 100), new Vec(400, 100),
                List.of(new Vec(200, 100)), p -> true));
    }
    @Test void closeThreatProducesSafeEscapeAndNotTheSameCrowdedRallyPoint() {
        Vec origin = new Vec(500, 500), threat = new Vec(600, 500);
        Vec chosen = planner.choose(origin, origin, List.of(threat), List.of(new Vec(400, 500)), p -> true);
        assertTrue(chosen.distance(threat) > origin.distance(threat));
        assertTrue(chosen.distance(new Vec(400, 500)) >= 45);
        assertTrue(planner.safeSegment(origin, chosen, List.of(threat), p -> true));
    }
    @Test void noSafeDestinationHoldsRatherThanReturningAnInvalidPoint() {
        Vec origin = new Vec(100, 100);
        assertEquals(origin, planner.choose(origin, new Vec(500, 500), List.of(), List.of(),
                p -> p.equals(origin)));
    }
    @Test void blockedFiringLaneSelectsASidePosition() {
        Vec origin = new Vec(500, 500), enemy = new Vec(1000, 500), teammate = new Vec(650, 500);
        Vec chosen = planner.choose(origin, origin, List.of(enemy), List.of(teammate), p -> true, enemy);
        assertTrue(java.awt.geom.Line2D.ptSegDist(chosen.x(), chosen.y(), enemy.x(), enemy.y(),
                teammate.x(), teammate.y()) >= 30);
    }

    @Test void routeMayNotCrossActiveFire() {
        ZombieAreaHazard fire = new ZombieAreaHazard(new Vec(200, 100), 40, 5_000,
                ZombieAreaHazard.Type.ACTIVE_FIRE);
        assertFalse(planner.safeSegment(new Vec(100, 100), new Vec(300, 100), List.of(),
                List.of(fire), 1_000, p -> true));
    }

    @Test void unitAlreadyInFireCanEscapeOutward() {
        ZombieAreaHazard fire = new ZombieAreaHazard(new Vec(100, 100), 40, 5_000,
                ZombieAreaHazard.Type.ACTIVE_FIRE);
        Vec origin = new Vec(110, 100);
        Vec chosen = planner.choose(origin, origin, List.of(), List.of(), List.of(fire), 1_000,
                p -> true, null);
        assertTrue(chosen.distance(fire.center()) >= fire.radius() + 24);
        assertTrue(planner.safeSegment(origin, chosen, List.of(), List.of(fire), 1_000, p -> true));
    }

    @Test void expiredFireDoesNotBlockRoute() {
        ZombieAreaHazard fire = new ZombieAreaHazard(new Vec(200, 100), 40, 999,
                ZombieAreaHazard.Type.ACTIVE_FIRE);
        assertTrue(planner.safeSegment(new Vec(100, 100), new Vec(300, 100), List.of(),
                List.of(fire), 1_000, p -> true));
    }

    @Test void distantGoalAcrossWallDelegatesDetourToAStar() {
        assertFalse(planner.requiresLocalDetour(new Vec(100, 100), new Vec(700, 100), List.of(),
                List.of(), 1_000, p -> p.x() < 180 || p.x() > 220));
        assertTrue(planner.requiresLocalDetour(new Vec(100, 100), new Vec(300, 100), List.of(),
                List.of(), 1_000, p -> p.x() < 180 || p.x() > 220));
    }
}
