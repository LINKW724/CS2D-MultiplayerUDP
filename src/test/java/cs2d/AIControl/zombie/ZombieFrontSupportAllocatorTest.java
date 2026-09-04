package cs2d.AIControl.zombie;

import cs2d.AIControl.zombie.ZombieFrontSupportAllocator.Role;
import cs2d.AIControl.zombie.ZombieTacticalSnapshot.Unit;
import cs2d.AIControl.zombie.ZombieTacticalSnapshot.Vec;
import cs2d.playerAndAi.Player.Team;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ZombieFrontSupportAllocatorTest {
    private final ZombieFrontSupportAllocator allocator = new ZombieFrontSupportAllocator();

    @Test void holdsCurrentShooterAndSelectsSlightlyFartherLmgForSupport() {
        List<Unit> agents = List.of(agent("engaged", 800, 1.0), agent("lmg", 600, 1.8),
                agent("near", 700, 1.0), agent("reserve", 0, 1.0));
        ZombieCombatFront front = new ZombieCombatFront("front", new Vec(1_000, 500),
                new Vec(800, 500), new Vec(1_000, 500), 4, 2, Set.of("engaged"), 1_000);
        var result = allocator.allocate(agents, List.of(front));
        assertEquals(Role.HOLD, result.get("engaged").role());
        assertEquals(Role.SUPPORT, result.get("lmg").role());
        assertFalse(result.containsKey("near"));
    }

    @Test void criticalUnderstaffedFrontMayConsumeTheNormalReserve() {
        List<Unit> agents = List.of(agent("engaged", 800, 1.0), agent("b", 600, 1.0),
                agent("c", 500, 1.0), agent("d", 400, 1.0));
        ZombieCombatFront front = new ZombieCombatFront("critical", new Vec(1_000, 500),
                new Vec(800, 500), new Vec(1_000, 500), 8, 4, Set.of("engaged"), 1_000);
        assertEquals(4, allocator.allocate(agents, List.of(front)).size());
    }

    private static Unit agent(String id, double x, double firepower) {
        return new Unit(id, Team.CT, new Vec(x, 500), 100, 30, false, true, firepower);
    }
}
