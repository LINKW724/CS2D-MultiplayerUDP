package cs2d.AIControl.zombie;

import cs2d.AIControl.zombie.ZombiePursuitPlanner.Neighbor;
import cs2d.AIControl.zombie.ZombieTacticalSnapshot.Vec;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.stream.IntStream;
import static org.junit.jupiter.api.Assertions.*;

class ZombiePursuitPlannerTest {
    private final ZombiePursuitPlanner planner = new ZombiePursuitPlanner();

    @Test void pursuersReceiveDifferentSurroundSlots() {
        Vec target = new Vec(500, 500);
        List<Neighbor> pack = List.of(new Neighbor("a", new Vec(100, 100)),
                new Neighbor("b", new Vec(100, 100)));
        Vec a = planner.engagementPoint("a", target, pack, p -> true);
        Vec b = planner.engagementPoint("b", target, pack, p -> true);
        assertNotEquals(a, b);
        assertEquals(30, a.distance(target), 0.001);
        assertEquals(30, b.distance(target), 0.001);
    }

    @Test void exactlyOverlappingPursuersDetourInDifferentDirections() {
        Vec origin = new Vec(100, 100);
        List<Neighbor> pack = List.of(new Neighbor("a", origin), new Neighbor("b", origin));
        Vec a = planner.detour("a", origin, new Vec(500, 100), pack, p -> true);
        Vec b = planner.detour("b", origin, new Vec(500, 100), pack, p -> true);
        assertTrue(a.distance(b) > 100);
    }

    @Test void blockedPreferredSlotUsesAnotherWalkableSlot() {
        Vec target = new Vec(500, 500);
        List<Neighbor> pack = List.of(new Neighbor("a", new Vec(100, 100)),
                new Neighbor("b", new Vec(100, 100)));
        Vec chosen = planner.engagementPoint("a", target, pack, p -> p.x() < 500);
        assertTrue(chosen.x() < 500);
    }

    @Test void largePackUsesMultipleRingsInsteadOfCompressingOneCircle() {
        Vec target = new Vec(500, 500);
        List<Neighbor> pack = IntStream.range(0, 18)
                .mapToObj(i -> new Neighbor(String.format("z%02d", i), new Vec(100, 100))).toList();
        List<Vec> slots = pack.stream()
                .map(z -> planner.engagementPoint(z.id(), target, pack, p -> true)).toList();
        assertEquals(18, slots.stream().distinct().count());
        assertTrue(slots.stream().mapToDouble(target::distance).max().orElseThrow() > 80);
    }

    @Test void progressTimeoutCreatesLateralDetourEvenWithoutCrowding() {
        Vec origin = new Vec(100, 100);
        Vec detour = planner.detour("solo", origin, new Vec(500, 100),
                List.of(new Neighbor("solo", origin)), p -> true);
        assertNotEquals(origin.y(), detour.y(), 0.001);
    }
}
