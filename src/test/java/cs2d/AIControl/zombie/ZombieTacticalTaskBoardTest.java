package cs2d.AIControl.zombie;

import cs2d.AIControl.zombie.ZombieTacticalOrder.Task;
import cs2d.AIControl.zombie.ZombieTacticalSnapshot.Vec;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ZombieTacticalTaskBoardTest {
    @Test void switchingTargetsDoesNotRestartTheSortieTimeout() {
        ZombieTacticalTaskBoard board = new ZombieTacticalTaskBoard();
        Vec home = new Vec(500, 500);
        var first = board.assign("a", Task.CLEAR_THREAT, "z1", home, home, 1000);
        var second = board.assign("a", Task.CLEAR_THREAT, "z2", home, home, 1500);
        assertNotEquals(first.generation(), second.generation());
        assertEquals(first.startedAt(), second.startedAt());
        assertFalse(board.canClear("a", 9000));
    }

    @Test void movingObjectiveRefreshesWithoutRestartingContinuousWork() {
        ZombieTacticalTaskBoard board = new ZombieTacticalTaskBoard();
        Vec home = new Vec(500, 500);
        var first = board.assign("a", Task.COVER_RELOAD, "z", home, home, 1000);
        var next = board.assign("a", Task.COVER_RELOAD, "z", new Vec(600, 500), home, 1250);
        assertEquals(first.generation(), next.generation());
        assertEquals(new Vec(600, 500), next.destination());
    }
}
