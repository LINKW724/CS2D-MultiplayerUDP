package cs2d.AIControl.zombie;

import cs2d.AIControl.zombie.ZombieTacticalSnapshot.Unit;
import cs2d.AIControl.zombie.ZombieTacticalSnapshot.Vec;
import cs2d.playerAndAi.Player.Team;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ZombieFlowForecasterTest {
    private final ZombieFlowForecaster forecaster = new ZombieFlowForecaster();

    @Test void nearbyZombiesShareOneForecastInsteadOfRequestingIndividualRoutes() {
        List<Unit> zombies = List.of(unit("z1", Team.ZOMBIE, 100, 100, 500),
                unit("z2", Team.ZOMBIE, 140, 120, 1_000));
        List<Unit> survivors = List.of(unit("s1", Team.CT, 1_000, 100, 100));
        int[] routeRequests = { 0 };
        var lanes = forecaster.forecast(1_000, zombies, survivors, List.of(), (source, target) -> {
            routeRequests[0]++;
            return List.of(source, new Vec(500, 100), target);
        }, ignored -> true);
        assertEquals(1, lanes.size());
        assertEquals(1, routeRequests[0]);
        assertEquals(2, lanes.get(0).zombieCount());
        assertEquals(3.0, lanes.get(0).pressure());
    }

    @Test void noSurvivorsProducesNoInventedDefenseLane() {
        assertTrue(forecaster.forecast(1_000,
                List.of(unit("z", Team.ZOMBIE, 100, 100, 500)), List.of(), List.of(),
                (source, target) -> fail("route must not be requested"), ignored -> true).isEmpty());
    }

    private static Unit unit(String id, Team team, double x, double y, int health) {
        return new Unit(id, team, new Vec(x, y), health, 30, false, true, 1.0);
    }
}
