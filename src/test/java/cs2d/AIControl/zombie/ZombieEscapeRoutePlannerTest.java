package cs2d.AIControl.zombie;

import cs2d.AIControl.zombie.ZombieTacticalSnapshot.Vec;
import cs2d.AIControl.zombie.ZombieAreaHazard.Type;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ZombieEscapeRoutePlannerTest {
    private final ZombieEscapeRoutePlanner planner = new ZombieEscapeRoutePlanner();

    @Test void rejectsDeepPocketAndKeepsSeveralOpenAreaRoutes() {
        Vec origin = new Vec(160, 192);
        var plan = planner.plan(origin, List.of(new Vec(96, 192)), List.of(), List.of(), 1_000,
                p -> p.x() >= 32 && p.y() >= 32 && p.x() <= 800 && p.y() <= 600
                        && !(p.x() >= 256 && p.x() <= 448 && p.y() < 176),
                (from, to) -> true);
        assertFalse(plan.empty());
        assertTrue(plan.routes().size() >= 2);
        assertTrue(plan.routes().get(0).destination().y() >= 176,
                "primary route must avoid the upper dead-end pocket");
    }

    @Test void fallbackRoutesDoNotAllReuseTheSameOpening() {
        Vec origin = new Vec(320, 320);
        var plan = planner.plan(origin, List.of(new Vec(320, 410)), List.of(), List.of(), 1_000,
                p -> p.x() >= 32 && p.y() >= 32 && p.x() <= 700 && p.y() <= 700,
                (from, to) -> true);
        assertTrue(plan.routes().size() >= 2);
        Vec first = plan.routes().get(0).waypoints().get(3);
        Vec second = plan.routes().get(1).waypoints().get(3);
        assertTrue(first.distance(second) > 32);
    }

    @Test void routeSafetyRejectsAProjectedInterception() {
        var route = new ZombieEscapeRoutePlanner.EscapeRoute(List.of(
                new Vec(100, 100), new Vec(132, 100), new Vec(164, 100),
                new Vec(196, 100), new Vec(228, 100), new Vec(260, 100)), 1);
        assertFalse(planner.routeStillSafe(route, 0, List.of(new Vec(220, 130)), List.of(), 1_000));
    }

    @Test void survivorCanLeaveFireEvenWithoutAVisibleZombie() {
        ZombieAreaHazard fire = new ZombieAreaHazard(new Vec(100, 100), 70, 2_000, Type.ACTIVE_FIRE);
        var plan = planner.plan(new Vec(100, 100), List.of(), List.of(), List.of(fire), 1_000,
                p -> p.x() >= 0 && p.y() >= 0 && p.x() <= 500 && p.y() <= 500,
                (from, to) -> true);
        assertFalse(plan.empty());
        assertFalse(fire.contains(plan.routes().get(0).destination(), 20));
    }

    @Test void routeBudgetScalesWithMapDiagonalButRemainsBounded() {
        var small = ZombieEscapeRoutePlanner.SearchProfile.forMap(1_000, 800);
        var large = ZombieEscapeRoutePlanner.SearchProfile.forMap(5_000, 4_000);
        var enormous = ZombieEscapeRoutePlanner.SearchProfile.forMap(50_000, 50_000);
        assertEquals(576, small.routeRadius());
        assertTrue(large.routeRadius() > small.routeRadius());
        assertEquals(1_280, enormous.routeRadius());
    }

    @Test void largeMapSearchCanSeePastALongCorridorIntoOpenSpace() {
        Vec origin = new Vec(128, 128);
        var profile = ZombieEscapeRoutePlanner.SearchProfile.forMap(4_000, 3_000);
        var plan = planner.plan(origin, List.of(new Vec(64, 128)), List.of(), List.of(), 1_000,
                p -> p.x() >= 32 && p.y() >= 32 && p.x() <= 3_968 && p.y() <= 2_968
                        && (p.x() >= 896 || Math.abs(p.y() - 128) < 16),
                (from, to) -> true, profile);
        assertFalse(plan.empty());
        assertTrue(plan.routes().get(0).destination().x() >= 896,
                "scaled search must prefer the open region beyond the local corridor");
    }
}
