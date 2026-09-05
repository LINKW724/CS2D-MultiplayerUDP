package cs2d.server;

import cs2d.playerAndAi.Weapon;
import org.junit.jupiter.api.Test;

import java.awt.geom.Point2D;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DroppedWeaponPickupCooldownTest {
    @Test
    void onlyDropperIsBlockedForTwoSeconds() {
        GameState.DroppedItem item = new GameState.DroppedItem(
                Weapon.AK47, new Point2D.Double(100, 200), 17, 55, "player-a");
        item.dropTime = 10_000L;

        assertFalse(GameState.canPlayerPickupDroppedWeapon(item, "player-a", 10_000L));
        assertFalse(GameState.canPlayerPickupDroppedWeapon(item, "player-a", 11_999L));
        assertTrue(GameState.canPlayerPickupDroppedWeapon(item, "player-a", 12_000L));
        assertTrue(GameState.canPlayerPickupDroppedWeapon(item, "player-b", 10_000L));
    }

    @Test
    void deathDroppedWeaponHasNoOwnerCooldown() {
        GameState.DroppedItem item = new GameState.DroppedItem(
                Weapon.M4A4, new Point2D.Double(100, 200), 20, 60);
        item.dropTime = 10_000L;

        assertTrue(GameState.canPlayerPickupDroppedWeapon(item, "player-a", 10_000L));
    }

    @Test
    void bombsAreNotAcceptedByWeaponPickupRule() {
        GameState.DroppedItem bomb = new GameState.DroppedItem(new Point2D.Double(100, 200));

        assertFalse(GameState.canPlayerPickupDroppedWeapon(bomb, "player-a", bomb.dropTime + 10_000L));
    }
}
