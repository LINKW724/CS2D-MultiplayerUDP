package cs2d.client;

import java.util.Locale;

/** Converts optional server source metadata and local perspective into a visual type. */
final class DamageNumberClassifier {
    private DamageNumberClassifier() {
    }

    static DamageNumberType classify(String feedbackKind, boolean headshot, boolean localVictim) {
        String kind = feedbackKind == null ? "" : feedbackKind.trim().toUpperCase(Locale.ROOT);
        if ("HEALING".equals(kind)) {
            return DamageNumberType.HEALING;
        }
        if (localVictim) {
            return DamageNumberType.INCOMING;
        }
        if (headshot) {
            return DamageNumberType.HEADSHOT;
        }
        return switch (kind) {
            case "EXPLOSIVE", "FIRE" -> DamageNumberType.EXPLOSIVE;
            case "MELEE" -> DamageNumberType.MELEE;
            default -> DamageNumberType.NORMAL;
        };
    }
}
