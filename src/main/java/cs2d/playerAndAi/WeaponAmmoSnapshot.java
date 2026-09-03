package cs2d.playerAndAi;

/** Immutable weapon and ammunition state captured at one point in time. */
public record WeaponAmmoSnapshot(Weapon weapon, int currentAmmo, int reserveAmmo) {
    public WeaponAmmoSnapshot {
        if (weapon == null)
            throw new IllegalArgumentException("Weapon is required");
        currentAmmo = Math.max(0, currentAmmo);
        reserveAmmo = Math.max(0, reserveAmmo);
    }
}
