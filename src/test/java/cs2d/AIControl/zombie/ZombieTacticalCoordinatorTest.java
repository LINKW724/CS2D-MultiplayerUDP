package cs2d.AIControl.zombie;

import cs2d.AIControl.zombie.ZombieTacticalSnapshot.*;
import cs2d.AIControl.zombie.ZombieTacticalOrder.Task;
import cs2d.playerAndAi.Player.Team;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ZombieTacticalCoordinatorTest {
    private final ZombieTacticalCoordinator commander = new ZombieTacticalCoordinator();
    private final ZombieTacticalTaskBoard board = new ZombieTacticalTaskBoard();
    private Unit agent(String id, double x, double y) {
        return new Unit(id, Team.CT, new Vec(x, y), 100, 50, false, true, 1.8);
    }
    private List<Unit> squad() {
        return List.of(agent("a", 500, 500), agent("b", 500, 580), agent("c", 500, 660),
                agent("d", 580, 500), agent("e", 580, 580), agent("f", 580, 660));
    }
    private Map<String, ZombieTacticalOrder> plan(long now, List<Unit> allies, List<Contact> contacts) {
        return commander.plan(new ZombieTacticalSnapshot(now, allies, contacts), board,
                p -> p.x() > 0 && p.y() > 0 && p.x() < 2000 && p.y() < 2000);
    }
    private Map<String, ZombieTacticalOrder> cleanupPlan(long now, int remaining, List<Contact> leads) {
        return commander.plan(new ZombieTacticalSnapshot(now, squad(), List.of(), List.of(), remaining, leads),
                board, p -> p.x() > 0 && p.y() > 0 && p.x() < 2000 && p.y() < 2000);
    }
    @Test void noEnemiesMeansGuardAndNoInventedTargets() {
        var result = plan(1000, squad(), List.of());
        assertEquals(6, result.size());
        assertTrue(result.values().stream().allMatch(o -> o.task() == Task.GUARD_AREA && o.targetId() == null));
    }
    @Test void localAdvantageSendsOnlySmallClearingGroup() {
        var result = plan(1000, squad(), List.of(new Contact("z", new Vec(980, 580), 100, 1000)));
        long clearing = result.values().stream().filter(o -> o.task() == Task.CLEAR_THREAT).count();
        assertTrue(clearing > 0 && clearing <= 2);
        assertTrue(result.values().stream().filter(o -> o.task() == Task.GUARD_AREA).count() >= 4);
    }
    @Test void growingHordeCancelsClearingInsteadOfPullingWholeTeamForward() {
        plan(1000, squad(), List.of(new Contact("z", new Vec(980, 580), 100, 1000)));
        List<Contact> horde = new ArrayList<>();
        for (int i = 0; i < 5; i++) horde.add(new Contact("z" + i, new Vec(980, 500 + i * 25), 100, 1250));
        assertTrue(plan(1250, squad(), horde).values().stream().noneMatch(o -> o.task() == Task.CLEAR_THREAT));
    }
    @Test void lowAmmoOrReloadingAgentsDoNotLeadSorties() {
        Unit reload = new Unit("a", Team.CT, new Vec(500, 500), 100, 0, true, true, 1.8);
        var result = plan(1000, List.of(reload), List.of(new Contact("z", new Vec(850, 500), 100, 1000)));
        assertNotEquals(Task.CLEAR_THREAT, result.get("a").task());
    }
    @Test void soleReadyDefenderCoversReloadInsteadOfLeavingAllyBehind() {
        Unit reload = new Unit("b", Team.CT, new Vec(500, 600), 100, 0, true, true, 1.8);
        var result = plan(1000, List.of(agent("a", 500, 500), reload),
                List.of(new Contact("z", new Vec(900, 500), 100, 1000)));
        assertEquals(Task.COVER_RELOAD, result.get("a").task());
        assertEquals(result.get("a").guardPoint(), result.get("a").destination());
    }
    @Test void fewButVeryStrongZombiesDoNotCountAsAnAdvantage() {
        var result = plan(1000, List.of(agent("a", 500, 500)),
                List.of(new Contact("boss", new Vec(900, 500), 5000, 1000)));
        assertNotEquals(Task.CLEAR_THREAT, result.get("a").task());
    }
    @Test void timedOutOrFinishedSortieReturnsAndNewTaskHasNewGeneration() {
        List<Contact> contacts = List.of(new Contact("z", new Vec(950, 500), 100, 1000));
        Unit solo = agent("a", 500, 500);
        ZombieTacticalOrder initial = plan(1000, List.of(solo), contacts).get("a");
        assertEquals(Task.CLEAR_THREAT, initial.task());
        Unit advanced = agent("a", 700, 500);
        ZombieTacticalOrder returning = plan(9100, List.of(advanced),
                List.of(new Contact("z", new Vec(1000, 500), 100, 9100))).get("a");
        assertEquals(Task.RETURN_TO_GUARD, returning.task());
        assertTrue(returning.generation() > initial.generation());
        assertEquals(initial.guardPoint(), returning.destination());
        assertNotEquals(Task.CLEAR_THREAT, plan(9300, List.of(advanced), contacts).get("a").task());
        ZombieTacticalOrder next = plan(15000, List.of(solo), contacts).get("a");
        assertEquals(Task.CLEAR_THREAT, next.task());
        assertTrue(next.generation() > returning.generation());
    }
    @Test void lossOfTargetAndRespawnReleaseOldTaskAndHome() {
        var first = plan(1000, List.of(agent("a", 500, 500)),
                List.of(new Contact("z", new Vec(950, 500), 100, 1000))).get("a");
        var returning = plan(1250, List.of(agent("a", 750, 500)), List.of()).get("a");
        assertEquals(Task.RETURN_TO_GUARD, returning.task());
        plan(1500, List.of(), List.of());
        var respawn = plan(1750, List.of(agent("a", 1200, 1200)), List.of()).get("a");
        assertNotEquals(first.guardPoint(), respawn.guardPoint());
        assertTrue(respawn.generation() > first.generation());
    }
    @Test void closeThreatForcesLocalRepositionNotGlobalRetreat() {
        var result = plan(1000, squad(), List.of(new Contact("z", new Vec(600, 500), 100, 1000)));
        assertEquals(Task.REPOSITION, result.get("a").task());
        assertTrue(result.get("a").destination().distance(new Vec(600, 500)) >= 100);
    }

    @Test void lastFewZombiesAssignLimitedCleanupPairs() {
        List<Contact> leads = List.of(new Contact("z1", new Vec(1500, 500), 100, 1000),
                new Contact("z2", new Vec(1500, 900), 100, 1000));
        var result = cleanupPlan(1000, 2, leads);
        assertEquals(4, result.values().stream().filter(o -> o.task() == Task.HUNT_REMAINDER).count());
        assertEquals(2, result.values().stream().filter(o -> o.task() == Task.GUARD_AREA).count());
        assertTrue(result.values().stream().filter(o -> o.task() == Task.HUNT_REMAINDER)
                .allMatch(o -> o.targetId().equals("z1") || o.targetId().equals("z2")));
    }

    @Test void normalWaveSizeDoesNotRevealCleanupLocations() {
        var result = cleanupPlan(1000, 4,
                List.of(new Contact("z", new Vec(1500, 500), 100, 1000)));
        assertTrue(result.values().stream().noneMatch(o -> o.task() == Task.HUNT_REMAINDER));
    }
}
