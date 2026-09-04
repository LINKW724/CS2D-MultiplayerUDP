package cs2d.AIControl.zombie;

import cs2d.AIControl.zombie.ZombieTacticalSnapshot.Vec;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ZombieEscapePlannerTest {
    private final ZombieEscapePlanner planner = new ZombieEscapePlanner();

    @Test
    void boundedSearchCanTurnThroughTheOnlyExitFromACorner() {
        Vec origin = new Vec(100, 100);
        Vec threat = new Vec(60, 100);
        Vec escape = planner.choose(origin, List.of(threat), List.of(), List.of(), 1_000,
                p -> p.x() >= 0 && p.y() >= 0 && p.x() <= 400 && p.y() <= 400
                        && (p.x() <= 100 || p.y() >= 164),
                (from, to) -> true);

        assertTrue(escape.x() > 100);
        assertTrue(escape.y() >= 164);
        assertTrue(escape.distance(threat) > origin.distance(threat));
    }

    @Test
    void teammateOccupiedCellsAreNotChosenAsEscapeGoals() {
        Vec origin = new Vec(100, 100);
        Vec teammate = new Vec(356, 100);
        Vec escape = planner.choose(origin, List.of(new Vec(60, 100)), List.of(teammate),
                List.of(), 1_000, p -> p.x() >= 0 && p.y() >= 0 && p.x() <= 400 && p.y() <= 400,
                (from, to) -> true);
        assertTrue(escape.distance(teammate) >= 36);
    }

    @Test
    void escapePathDoesNotCrossThroughTheThreat() {
        Vec origin = new Vec(100, 100);
        Vec threat = new Vec(164, 100);
        Vec escape = planner.choose(origin, List.of(threat), List.of(), List.of(), 1_000,
                p -> p.x() >= 0 && p.y() >= 0 && p.x() <= 400 && p.y() <= 400,
                (from, to) -> true);
        assertTrue(escape.x() < threat.x() || Math.abs(escape.y() - threat.y()) >= 30);
        assertTrue(escape.distance(threat) > origin.distance(threat));
    }
}
