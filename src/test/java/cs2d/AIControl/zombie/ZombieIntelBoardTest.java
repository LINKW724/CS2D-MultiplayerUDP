package cs2d.AIControl.zombie;

import cs2d.AIControl.zombie.ZombieTacticalSnapshot.*;
import cs2d.playerAndAi.Player.Team;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ZombieIntelBoardTest {
    private Unit unit(String id, Team team) {
        return new Unit(id, team, new Vec(500, 500), 100, 30, false, true, 1.5);
    }

    @Test void factionsNeverConsumeEachOthersEnemyLists() {
        ZombieIntelBoard board = new ZombieIntelBoard();
        Unit ct = unit("ct", Team.CT), zombie = unit("z", Team.ZOMBIE);
        Map<String, Unit> current = Map.of(ct.id(), ct, zombie.id(), zombie);
        board.report(ct, zombie, 1000);
        board.report(zombie, ct, 1000);
        board.report(ct, ct, 1000);
        assertEquals(List.of("z"), board.contacts(Team.CT, current, 1001).stream().map(Contact::id).toList());
        assertEquals(List.of("ct"), board.contacts(Team.ZOMBIE, current, 1001).stream().map(Contact::id).toList());
    }

    @Test void expiredDeadAndConvertedTargetsAreRemoved() {
        ZombieIntelBoard board = new ZombieIntelBoard();
        Unit ct = unit("ct", Team.CT), zombie = unit("z", Team.ZOMBIE);
        board.report(ct, zombie, 1000);
        assertTrue(board.contacts(Team.CT, Map.of("ct", ct, "z", zombie), 4001).isEmpty());
        board.report(ct, zombie, 5000);
        assertTrue(board.contacts(Team.CT, Map.of("ct", ct, "z", unit("z", Team.CT)), 5001).isEmpty());
        board.report(ct, zombie, 6000);
        assertTrue(board.contacts(Team.CT, Map.of("ct", ct), 6001).isEmpty());
    }

    @Test void convertedObserverAndRoundResetCannotLeakOldIntel() {
        ZombieIntelBoard board = new ZombieIntelBoard();
        Unit ct = unit("ct", Team.CT), zombie = unit("z", Team.ZOMBIE);
        board.report(ct, zombie, 1000);
        assertTrue(board.contacts(Team.CT, Map.of("ct", unit("ct", Team.ZOMBIE), "z", zombie), 1001).isEmpty());
        board.report(ct, zombie, 2000);
        board.clear();
        assertTrue(board.contacts(Team.CT, Map.of("ct", ct, "z", zombie), 2001).isEmpty());
    }

    @Test void lateWorkerCannotReplaceNewerObservation() {
        ZombieIntelBoard board = new ZombieIntelBoard();
        Unit ct = unit("ct", Team.CT), zombie = unit("z", Team.ZOMBIE);
        board.report(ct, zombie, 2000);
        board.report(ct, zombie, 1000);
        assertEquals(2000, board.contacts(Team.CT, Map.of("ct", ct, "z", zombie), 2100).get(0).observedAt());
    }
}
