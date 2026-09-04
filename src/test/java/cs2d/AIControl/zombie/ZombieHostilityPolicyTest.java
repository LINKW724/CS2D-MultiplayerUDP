package cs2d.AIControl.zombie;

import cs2d.playerAndAi.Player;
import cs2d.server.GameMode;
import org.junit.jupiter.api.Test;
import java.awt.geom.Point2D;
import static org.junit.jupiter.api.Assertions.*;

class ZombieHostilityPolicyTest {
    private Player player(String id, Player.Team team, double x, double y) {
        return new Player(id, id, new Point2D.Double(x, y), team, false,
                GameMode.ZOMBIE_MODE, null, null, ignored -> {});
    }
    @Test void neverTargetsSelfTeammatesDeadOrConvertedEnemies() {
        Player ct = player("ct", Player.Team.CT, 100, 100);
        Player ally = player("ally", Player.Team.CT, 200, 100);
        Player z = player("z", Player.Team.ZOMBIE, 400, 100);
        assertFalse(ZombieHostilityPolicy.canTarget(ct, ct));
        assertFalse(ZombieHostilityPolicy.canTarget(ct, ally));
        assertTrue(ZombieHostilityPolicy.canTarget(ct, z));
        z.team = Player.Team.CT;
        assertFalse(ZombieHostilityPolicy.canTarget(ct, z));
        z.team = Player.Team.ZOMBIE;
        z.health = 0;
        assertFalse(ZombieHostilityPolicy.canTarget(ct, z));
    }
    @Test void bulletFireIgnoresTeammateGeometryButRejectsZombieGunfire() {
        Player ct = player("ct", Player.Team.CT, 100, 100);
        Player ally = player("ally", Player.Team.CT, 200, 100);
        Player z = player("z", Player.Team.ZOMBIE, 400, 100);
        assertFalse(ZombieHostilityPolicy.bulletFireAllowed(ct, ally));
        // The gate accepts only shooter and target, so an intervening teammate cannot block bullets.
        assertTrue(ZombieHostilityPolicy.bulletFireAllowed(ct, z));
        assertFalse(ZombieHostilityPolicy.bulletFireAllowed(z, ct));
    }
}
