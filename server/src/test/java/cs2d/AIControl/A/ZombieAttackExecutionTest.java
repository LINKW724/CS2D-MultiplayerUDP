package cs2d.AIControl.A;

import cs2d.playerAndAi.Player;
import cs2d.playerAndAi.Weapon;
import cs2d.server.AIDifficulty;
import cs2d.server.GameMode;
import org.junit.jupiter.api.Test;
import java.awt.geom.Point2D;
import static org.junit.jupiter.api.Assertions.*;

class ZombieAttackExecutionTest {
    private static class Shooter extends Player {
        private final PathfindingModule path;
        Shooter() {
            super("ct", "ct", new Point2D.Double(100, 100), Team.CT, false,
                    GameMode.ZOMBIE_MODE, null, AIDifficulty.REALISTIC, ignored -> {});
            path = new PathfindingModule(this) {
                @Override public boolean hasLineOfSight(Point2D.Double p) { return true; }
            };
            primaryWeapon = Weapon.NEGEV;
            currentSlot = 1;
            currentAmmo = 200;
            currentSpread = 0;
            weaponFixedRecoil = null;
            vx = 5;
        }
        @Override public PathfindingModule getPathfindingModule() { return path; }
    }
    private Player enemy() {
        return new Player("z", "z", new Point2D.Double(900, 100), Player.Team.ZOMBIE,
                false, GameMode.ZOMBIE_MODE, null, AIDifficulty.REALISTIC, ignored -> {});
    }

    @Test void movingSurvivorCanFireBeyondOldSixHundredRangeAndPastHundredShotBurst() {
        Shooter owner = new Shooter();
        Player zombie = enemy();
        AttackModule attack = new AttackModule(owner, AIDifficulty.REALISTIC);
        int shots = 0;
        for (int i = 0; i < 150; i++) {
            long now = 10_000L + i * 200L;
            if (attack.update(zombie, zombie.position, now, AttackExecutionPolicy.ZOMBIE_SURVIVOR).shooting()) {
                shots++;
                owner.currentAmmo--;
                owner.lastShotTime = now;
            }
        }
        assertTrue(shots >= 140, "continuous moving fire was suppressed: " + shots);
    }
    @Test void legacyAttackEntryPointStillRequiresLongGunAccuracyStop() {
        Shooter owner = new Shooter();
        Player zombie = enemy();
        AttackModule attack = new AttackModule(owner, AIDifficulty.REALISTIC);
        for (int i = 0; i < 50; i++)
            assertFalse(attack.update(zombie, zombie.position, 10_000L + i * 200L).shooting());
    }
    @Test void conversionOrNoTargetSuppressesFireEvenWithOldKnownPosition() {
        Shooter owner = new Shooter();
        Player zombie = enemy();
        AttackModule attack = new AttackModule(owner, AIDifficulty.REALISTIC);
        attack.update(zombie, zombie.position, 10_000L, AttackExecutionPolicy.ZOMBIE_SURVIVOR);
        zombie.team = Player.Team.CT;
        for (int i = 0; i < 20; i++) {
            assertFalse(attack.update(zombie, zombie.position, 11_000L + i * 100L,
                    AttackExecutionPolicy.ZOMBIE_SURVIVOR).shooting());
            assertFalse(attack.update(null, zombie.position, 11_000L + i * 100L,
                    AttackExecutionPolicy.ZOMBIE_SURVIVOR).shooting());
        }
    }

    @Test void switchingTargetsInsideOneCrowdPreservesReactionAndRecoilState() {
        Shooter owner = new Shooter();
        Player first = enemy();
        Player second = new Player("z2", "z2", new Point2D.Double(900, 130), Player.Team.ZOMBIE,
                false, GameMode.ZOMBIE_MODE, null, AIDifficulty.REALISTIC, ignored -> {});
        AttackModule attack = new AttackModule(owner, AIDifficulty.REALISTIC);
        attack.update(first, first.position, 10_000L, AttackExecutionPolicy.ZOMBIE_SURVIVOR, "crowd-1");
        owner.shootTimeIndex = 20;
        assertTrue(attack.update(second, second.position, 10_100L,
                AttackExecutionPolicy.ZOMBIE_SURVIVOR, "crowd-1").shooting());
        assertEquals(20, owner.shootTimeIndex);
        assertFalse(attack.update(first, first.position, 10_150L,
                AttackExecutionPolicy.ZOMBIE_SURVIVOR, "crowd-2").shooting());
        assertEquals(0, owner.shootTimeIndex);
    }
}
