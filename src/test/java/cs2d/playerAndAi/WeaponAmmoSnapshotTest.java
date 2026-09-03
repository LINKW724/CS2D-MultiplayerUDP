package cs2d.playerAndAi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.geom.Point2D;

import org.junit.jupiter.api.Test;

import cs2d.server.GameMode;

class WeaponAmmoSnapshotTest {
    @Test
    void selectedPrimaryUsesLiveAmmoInsteadOfStaleSlotCache() {
        Player player = new Player("bot", "Bot", new Point2D.Double(), Player.Team.T,
                false, GameMode.TEAM_DEATHMATCH, null, null, message -> { });
        player.setWeapon(Weapon.AK47, GameMode.TEAM_DEATHMATCH);
        player.primary_currentAmmo = Weapon.AK47.magazineSize;
        player.primary_reserveAmmo = 90;
        player.currentSlot = 1;
        player.currentAmmo = 7;
        player.reserveAmmo = 41;

        WeaponAmmoSnapshot snapshot = player.snapshotWeaponSlot(1).orElseThrow();

        assertEquals(Weapon.AK47, snapshot.weapon());
        assertEquals(7, snapshot.currentAmmo());
        assertEquals(41, snapshot.reserveAmmo());
    }

    @Test
    void holsteredPrimaryUsesStoredSlotAmmo() {
        Player player = new Player("player", "Player", new Point2D.Double(), Player.Team.CT,
                false, GameMode.TEAM_DEATHMATCH, null, null, message -> { });
        player.primaryWeapon = Weapon.M4A4;
        player.primary_currentAmmo = 12;
        player.primary_reserveAmmo = 38;
        player.currentSlot = 2;
        player.currentAmmo = 5;
        player.reserveAmmo = 20;

        WeaponAmmoSnapshot snapshot = player.snapshotWeaponSlot(1).orElseThrow();

        assertEquals(Weapon.M4A4, snapshot.weapon());
        assertEquals(12, snapshot.currentAmmo());
        assertEquals(38, snapshot.reserveAmmo());
        assertTrue(player.snapshotWeaponSlot(3).isEmpty());
    }
}
