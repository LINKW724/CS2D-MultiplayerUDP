package cs2d.AIControl.zombie;

import cs2d.AIControl.zombie.ZombieTacticalSnapshot.Vec;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ZombieDefenseCorridorPlannerTest {
    private final ZombieDefenseCorridorPlanner planner = new ZombieDefenseCorridorPlanner();

    @Test void nearbyParallelLanesBecomeOneThreeLayerCorridor() {
        List<Vec> first = IntStream.rangeClosed(0, 40)
                .mapToObj(i -> new Vec(1_000 - i * 32, 400)).toList();
        List<Vec> second = IntStream.rangeClosed(0, 40)
                .mapToObj(i -> new Vec(1_000 - i * 32, 460)).toList();
        ZombieAttackLane a = lane("a", first, new Vec(650, 400), 4);
        ZombieAttackLane b = lane("b", second, new Vec(650, 460), 3);
        var corridors = planner.plan(List.of(a, b), List.of(), 1_000, ignored -> true);
        assertEquals(1, corridors.size());
        ZombieDefenseCorridor corridor = corridors.get(0);
        assertEquals(7, corridor.pressure());
        assertTrue(corridor.anchor(ZombieDefenseCorridor.Line.FORWARD)
                .distance(corridor.anchor(ZombieDefenseCorridor.Line.FALLBACK_ONE)) >= 160);
        assertTrue(corridor.anchor(ZombieDefenseCorridor.Line.FALLBACK_ONE)
                .distance(corridor.anchor(ZombieDefenseCorridor.Line.FALLBACK_TWO)) >= 160);
    }

    private static ZombieAttackLane lane(String id, List<Vec> route, Vec defense, double pressure) {
        return new ZombieAttackLane(id, route.get(0), route.get(route.size() - 1), defense,
                route.get(1), (int) pressure, pressure, 8_000, route);
    }
}
