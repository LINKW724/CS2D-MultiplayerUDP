package cs2d.AIControl.zombie;

import cs2d.playerAndAi.Weapon;

/** Pure survivor mobility state: immediate escape and heavy-weapon stow decisions. */
public final class ZombieSurvivorMobilityPolicy {
    static final double EMERGENCY_DISTANCE = 190;
    static final double LMG_STOW_DISTANCE = 270;
    static final double LMG_RESTORE_DISTANCE = 390;
    public static final long DAMAGE_ESCAPE_MS = 1_200;

    public Decision decide(Weapon primary, Weapon secondary, double nearestThreat,
            boolean inHazard, long lastDamagedAt, long now, boolean wasKiting) {
        boolean recentlyDamaged = lastDamagedAt > 0 && now >= lastDamagedAt
                && now - lastDamagedAt <= DAMAGE_ESCAPE_MS;
        boolean immediateDanger = inHazard || recentlyDamaged || nearestThreat < EMERGENCY_DISTANCE;
        boolean hasLmgAndPistol = primary != null && secondary != null
                && primary.getWeaponType() == Weapon.WeaponType.LMG;
        boolean kiting = hasLmgAndPistol && (wasKiting
                ? inHazard || recentlyDamaged || nearestThreat < LMG_RESTORE_DISTANCE
                : inHazard || recentlyDamaged || nearestThreat < LMG_STOW_DISTANCE);
        int desiredWeaponSlot = hasLmgAndPistol ? (kiting ? 2 : 1) : 0;
        return new Decision(immediateDanger || kiting, recentlyDamaged, kiting, desiredWeaponSlot);
    }

    public record Decision(boolean emergency, boolean recentlyDamaged,
            boolean kiting, int desiredWeaponSlot) {}
}
