package cs2d.AIControl.zombie;

import cs2d.AIControl.zombie.ZombieDefenseAllocator.Role;
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
        ZombieAttackLane urgent = lane("urgent", 300, 6, 2_000);
        ZombieAttackLane later = lane("later", 1_000, 2, 12_000);
        var assignments = allocator.allocate(agents, List.of(later, urgent));
        assertEquals(5, assignments.size());
        assertEquals(3, assignments.values().stream()
                .filter(a -> a.laneId().equals("urgent")).count());
        assertEquals(1, assignments.values().stream()
                .filter(a -> a.laneId().equals("later") && a.role() == Role.FORTIFY).count());
        assertEquals(1, assignments.values().stream()
                .filter(a -> a.role() == Role.RESERVE).count());
    }

    @Test void laneNeverPullsMoreThanFourAgents() {
        List<Unit> agents = java.util.stream.IntStream.range(0, 8)
                .mapToObj(i -> agent("a" + i, i * 40)).toList();
        var assignments = allocator.allocate(agents, List.of(lane("horde", 500, 30, 1_000)));
        assertEquals(4, assignments.values().stream()
                .filter(a -> a.laneId().equals("horde")).count());
    }

    private static Unit agent(String id, double x) {
        return new Unit(id, Team.CT, new Vec(x, 500), 100, 30, false, true, 1.8);
    }

    private static ZombieAttackLane lane(String id, double x, double pressure, long eta) {
        Vec defense = new Vec(x, 500);
        return new ZombieAttackLane(id, new Vec(x + 500, 500), new Vec(0, 500), defense,
                new Vec(x + 100, 500), (int) pressure, pressure, eta,
                List.of(new Vec(x + 500, 500), defense, new Vec(0, 500)));
    }
}
