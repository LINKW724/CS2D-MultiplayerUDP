package cs2d.AIControl.zombie;

import cs2d.AIControl.zombie.ZombieTacticalSnapshot.Vec;
import cs2d.server.Item;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ZombieGrenadeSafetyPolicyTest {
    private final ZombieGrenadeSafetyPolicy policy = new ZombieGrenadeSafetyPolicy();
    private final List<Vec> cluster = List.of(new Vec(500, 500), new Vec(525, 500), new Vec(510, 525));

    @Test void teammateInsideHeBlastAreaRejectsCluster() {
        assertTrue(policy.chooseTarget(Item.HE_GRENADE, new Vec(0, 0), cluster,
                List.of(new Vec(520, 510)), List.of()).isEmpty());
    }

    @Test void isolatedZombieClusterProducesSafeTarget() {
        Vec target = policy.chooseTarget(Item.HE_GRENADE, new Vec(0, 0), cluster,
                List.of(new Vec(0, 0), new Vec(1_000, 1_000)), List.of()).orElseThrow();
        assertEquals(3, policy.affectedEnemies(Item.HE_GRENADE, target, cluster));
    }

    @Test void predictedLandingIsCheckedInsteadOfOnlyRequestedTarget() {
        List<Vec> allies = List.of(new Vec(800, 500));
        assertTrue(policy.isSafeImpact(Item.HE_GRENADE, new Vec(300, 500), allies, List.of()));
        assertFalse(policy.isSafeImpact(Item.HE_GRENADE, new Vec(650, 500), allies, List.of()));
    }

    @Test void fireGrenadeIsNotStackedOnExistingFire() {
        ZombieAreaHazard fire = new ZombieAreaHazard(new Vec(500, 500), 40, 10_000,
                ZombieAreaHazard.Type.ACTIVE_FIRE);
        assertFalse(policy.isSafeImpact(Item.MOLOTOV, new Vec(550, 500), List.of(), List.of(fire)));
    }
}
