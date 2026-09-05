package cs2d.AIControl.zombie;

import cs2d.AIControl.zombie.ZombieAreaHazard.Type;
import cs2d.AIControl.zombie.ZombieTacticalSnapshot.Vec;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ZombieDefenseLinePlannerTest {
    private final ZombieDefenseLinePlanner planner = new ZombieDefenseLinePlanner();
    private final List<Vec> route = IntStream.rangeClosed(0, 10)
            .mapToObj(i -> new Vec(i * 100, 500)).toList();

    @Test void selectsNarrowWalkablePartOfPredictedRoute() {
        var result = planner.choose(route, List.of(), 1_000,
                p -> p.y() == 500 || p.x() < 450 || p.x() > 550);
        assertNotNull(result);
        assertEquals(new Vec(500, 500), result.position());
        assertEquals(new Vec(300, 500), result.watchPoint());
    }

    @Test void activeHazardRejectsOtherwiseBestChoke() {
        var fire = new ZombieAreaHazard(new Vec(500, 500), 70, 2_000, Type.ACTIVE_FIRE);
        var result = planner.choose(route, List.of(fire), 1_000,
                p -> p.y() == 500 || p.x() < 450 || p.x() > 550);
        assertNotNull(result);
        assertNotEquals(new Vec(500, 500), result.position());
        assertFalse(fire.contains(result.position(), 40));
    }
}
