package cs2d.AIControl.zombie;

import cs2d.AIControl.zombie.ZombieTacticalSnapshot.*;
import cs2d.playerAndAi.Player.Team;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ZombieCombatFrontPlannerTest {
    private final ZombieCombatFrontPlanner planner = new ZombieCombatFrontPlanner();

    @Test void sharedSightingsBecomeOnePressureSizedFront() {
        List<Unit> allies = List.of(agent("spotter", 500, 500, 1.0, 900),
                agent("idle", 200, 500, 1.8, 0));
        List<Contact> contacts = List.of(contact("z1", 1_000, 500, "spotter"),
                contact("z2", 1_080, 520, "spotter"));
        ZombieAttackLane lane = new ZombieAttackLane("lane", new Vec(1_040, 510),
                new Vec(300, 500), new Vec(760, 500), new Vec(1_000, 500),
                7, 7, 3_000, List.of(new Vec(1_040, 510), new Vec(760, 500), new Vec(300, 500)));
        var fronts = planner.detect(new ZombieTacticalSnapshot(1_000, allies, contacts, List.of(),
                7, List.of(), List.of(lane)), ignored -> true);
        assertEquals(1, fronts.size());
        assertEquals(4, fronts.get(0).requiredDefenders());
        assertEquals(new Vec(760, 500), fronts.get(0).rallyPoint());
        assertTrue(fronts.get(0).engagedAgentIds().contains("spotter"));
    }

    @Test void staleOrAnonymousIntelDoesNotCreateAFalseActiveFront() {
        List<Unit> allies = List.of(agent("a", 500, 500, 1.0, 0));
        assertTrue(planner.detect(new ZombieTacticalSnapshot(4_001, allies,
                List.of(contact("old", 900, 500, "a"))), ignored -> true).isEmpty());
        assertTrue(planner.detect(new ZombieTacticalSnapshot(1_000, allies,
                List.of(new Contact("anonymous", new Vec(900, 500), 500, 1_000))),
                ignored -> true).isEmpty());
    }

    private static Unit agent(String id, double x, double y, double firepower, long lastShotAt) {
        return new Unit(id, Team.CT, new Vec(x, y), 100, 30, false, true, firepower, lastShotAt);
    }

    private static Contact contact(String id, double x, double y, String observer) {
        return new Contact(id, new Vec(x, y), 500, 1_000, observer);
    }
}
