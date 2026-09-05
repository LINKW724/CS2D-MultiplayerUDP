package cs2d.AIControl.zombie;

import cs2d.AIControl.zombie.ZombieDefenseCorridor.Line;
import cs2d.AIControl.zombie.ZombieTacticalSnapshot.Unit;
import cs2d.AIControl.zombie.ZombieTacticalSnapshot.Vec;
import cs2d.playerAndAi.Player.Team;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ZombieDefenseStateBoardTest {
    @Test void corridorSurvivesBriefForecastLossAndRetreatDoesNotOscillateForward() {
        ZombieDefenseStateBoard board = new ZombieDefenseStateBoard();
        List<Unit> squad = List.of(new Unit("a", Team.CT, new Vec(0, 0), 100, 30,
                false, true, 1.8));
        ZombieDefenseCorridor critical = corridor(10, 1_000);
        assertEquals(Line.FALLBACK_TWO, board.update(List.of(critical), squad, 1_000).get(0).line());
        ZombieDefenseCorridor calm = corridor(0.2, 12_000);
        assertEquals(Line.FALLBACK_TWO, board.update(List.of(calm), squad, 2_000).get(0).line());
        var retained = board.update(List.of(), squad, 3_000);
        assertEquals(1, retained.size());
        assertFalse(retained.get(0).observedNow());
    }

    @Test void coveringUnitIsReleasedAfterFallbackLineBecomesOccupied() {
        ZombieDefenseStateBoard board = new ZombieDefenseStateBoard();
        ZombieDefenseCorridor critical = corridor(10, 1_000);
        Unit far = new Unit("a", Team.CT, new Vec(0, 0), 100, 30, false, true, 1.8);
        assertTrue(board.update(List.of(critical), List.of(far), 1_000).get(0).coverRequired());
        Unit arrived = new Unit("a", Team.CT, new Vec(200, 500), 100, 30, false, true, 1.8);
        assertFalse(board.update(List.of(critical), List.of(arrived), 1_500).get(0).coverRequired());
    }

    private static ZombieDefenseCorridor corridor(double pressure, long eta) {
        Vec point = new Vec(500, 500);
        return new ZombieDefenseCorridor("door", new Vec(600, 500), pressure, 4, eta,
                List.of(point), List.of(new Vec(350, 500)), List.of(new Vec(200, 500)));
    }
}
