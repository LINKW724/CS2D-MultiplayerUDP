package cs2d.AIControl.A;

import cs2d.playerAndAi.Weapon.WeaponType;
import cs2d.playerAndAi.Player;
import cs2d.AIControl.zombie.ZombieHostilityPolicy;

/** Mode-specific firing constraints. The legacy entry point always uses STANDARD. */
public enum AttackExecutionPolicy {
    STANDARD, ZOMBIE_SURVIVOR;

    public boolean allowsBlindFire() { return this == STANDARD; }
    public boolean acceptsTarget(Player owner, Player target) {
        return this == STANDARD || owner != null && owner.team == Player.Team.CT
                && ZombieHostilityPolicy.canTarget(owner, target);
    }
    public boolean requiresStop(WeaponType weapon, boolean knownPosition, boolean wantsFire, boolean directSight) {
        if (this == ZOMBIE_SURVIVOR) return !directSight || weapon == WeaponType.SNIPER;
        return knownPosition && wantsFire || weapon != null && (weapon.isLongRange() || weapon.isPistol());
    }
    public boolean continuousFire(WeaponType weapon) {
        return this == ZOMBIE_SURVIVOR
                && (weapon == WeaponType.LMG || weapon == WeaponType.SMG || weapon == WeaponType.RIFLE);
    }
}
