package cs2d.playerAndAi;

import cs2d.server.GameMode;
import org.junit.jupiter.api.Test;

import java.awt.geom.Point2D;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PlayerRespawnTest {

    @Test
    void respawnRefillsPistolHeldAtDeathWithoutStaleAmmoOverwritingIt() {
        Player player = new Player(
                "player-1",
                "Tester",
                new Point2D.Double(0, 0),
                Player.Team.CT,
                false,
                GameMode.TEAM_DEATHMATCH,
                null,
                null,
                ignored -> {
                });

        player.primaryWeapon = Weapon.M4A4;
        player.selectedPrimaryName = Weapon.M4A4.name();
        player.selectedSecondaryName = Weapon.USPS.name();
        player.switchToSlot(2);
        player.currentAmmo = 1;
        player.reserveAmmo = 2;
        player.secondary_currentAmmo = 1;
        player.secondary_reserveAmmo = 2;

        player.respawn(new Point2D.Double(100, 200), GameMode.TEAM_DEATHMATCH);
        player.switchToSlot(2);

        assertEquals(Weapon.USPS.magazineSize, player.currentAmmo);
        assertEquals(Weapon.USPS.magazineSize * 4, player.reserveAmmo);
        assertEquals(Weapon.USPS.magazineSize, player.secondary_currentAmmo);
        assertEquals(Weapon.USPS.magazineSize * 4, player.secondary_reserveAmmo);
    }
}
