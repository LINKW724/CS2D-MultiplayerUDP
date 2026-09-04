package cs2d.client;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class DamageNumberClassifierTest {
    @Test
    void appliesPerspectiveAndSemanticPrecedence() {
        assertEquals(DamageNumberType.HEALING,
                DamageNumberClassifier.classify("HEALING", true, true));
        assertEquals(DamageNumberType.INCOMING,
                DamageNumberClassifier.classify("MELEE", true, true));
        assertEquals(DamageNumberType.HEADSHOT,
                DamageNumberClassifier.classify("NORMAL", true, false));
        assertEquals(DamageNumberType.EXPLOSIVE,
                DamageNumberClassifier.classify("EXPLOSIVE", false, false));
        assertEquals(DamageNumberType.EXPLOSIVE,
                DamageNumberClassifier.classify("FIRE", false, false));
        assertEquals(DamageNumberType.MELEE,
                DamageNumberClassifier.classify("MELEE", false, false));
        assertEquals(DamageNumberType.NORMAL,
                DamageNumberClassifier.classify("UNKNOWN", false, false));
        assertEquals(DamageNumberType.NORMAL,
                DamageNumberClassifier.classify(null, false, false));
    }
}
