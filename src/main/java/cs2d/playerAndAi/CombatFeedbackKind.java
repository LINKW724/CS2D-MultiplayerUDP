package cs2d.playerAndAi;

import java.util.Locale;

/** Server-authoritative source category for combat feedback rendering. */
public enum CombatFeedbackKind {
    NORMAL,
    EXPLOSIVE,
    FIRE,
    MELEE,
    HEALING;

    public static CombatFeedbackKind fromWeaponName(String weaponName) {
        String normalized = weaponName == null ? "" : weaponName.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "HE_GRENADE", "C4" -> EXPLOSIVE;
            case "MOLOTOV", "INCENDIARY" -> FIRE;
            case "KNIFE", "FISTS", "ZOMBIE_CLAW" -> MELEE;
            default -> NORMAL;
        };
    }
}
