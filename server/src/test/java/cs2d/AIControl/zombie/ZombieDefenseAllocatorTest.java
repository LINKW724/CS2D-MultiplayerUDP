package cs2d.AIControl.zombie;

import cs2d.AIControl.zombie.ZombieDefenseAllocator.Role;
import cs2d.AIControl.zombie.ZombieDefenseCorridor.Line;
import cs2d.AIControl.zombie.ZombieDefenseStateBoard.ActiveCorridor;
import cs2d.AIControl.zombie.ZombieTacticalSnapshot.Unit;
import cs2d.AIControl.zombie.ZombieTacticalSnapshot.Vec;
import cs2d.playerAndAi.Player.Team;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ZombieDefenseAllocatorTest {
    private final ZombieDefenseAllocator allocator = new ZombieDefenseAllocator();

    @Test void pressureGetsBoundedDefendersAndLeavesMobileReserve() {
        List<Unit> agents = List.of(agent("a", 0), agent("b", 50), agent("c", 100),
                agent("d", 150), agent("e", 200), agent("f", 250));
        ActiveCorridor urgent = corridor("urgent", 300, 6, 2_000);
        ActiveCorridor later = corridor("later", 1_000, 2, 12_000);
        var assignments = allocator.allocate(agents, List.of(later, urgent));
        assertEquals(5, assignments.size());
        assertEquals(4, assignments.values().stream()
                .filter(a -> a.laneId().equals("urgent")).count());
        assertEquals(1, assignments.values().stream()
                .filter(a -> a.role() == Role.RESERVE).count());
    }

    @Test void highPressureCorridorUsesMostAgentsButPreservesReserveCapacity() {
        List<Unit> agents = java.util.stream.IntStream.range(0, 8)
                .mapToObj(i -> agent("a" + i, i * 40)).toList();
        var assignments = allocator.allocate(agents, List.of(corridor("horde", 500, 30, 1_000)));
        assertEquals(6, assignments.values().stream()
                .filter(a -> a.laneId().equals("horde")).count());
    }

    @Test void fallbackLeavesOneCoveringShooterOnThePreviousLine() {
        List<Unit> agents = List.of(agent("a", 0), agent("b", 50), agent("c", 100),
                agent("d", 150), agent("e", 200), agent("f", 250));
        ActiveCorridor base = corridor("door", 500, 10, 1_000);
        ActiveCorridor fallback = new ActiveCorridor(base.corridor(), Line.FALLBACK_ONE, true);
        var assignments = allocator.allocate(agents, List.of(fallback));
        assertEquals(1, assignments.values().stream()
                .filter(a -> a.role() == Role.COVER_WITHDRAWAL).count());
        assertTrue(assignments.values().stream().anyMatch(a -> a.role() == Role.FALL_BACK_ONE));
    }

    private static Unit agent(String id, double x) {
        return new Unit(id, Team.CT, new Vec(x, 500), 100, 30, false, true, 1.8);
    }

    private static ActiveCorridor corridor(String id, double x, double pressure, long eta) {
        Vec defense = new Vec(x, 500);
        List<Vec> stations = java.util.stream.IntStream.range(0, 7)
                .mapToObj(i -> new Vec(x, 500 + (i - 3) * 48)).toList();
        ZombieDefenseCorridor corridor = new ZombieDefenseCorridor(id, new Vec(x + 100, 500),
                pressure, (int) pressure, eta, stations, stations, stations);
        return new ActiveCorridor(corridor, Line.FORWARD, true);
    }
}
