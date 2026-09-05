package cs2d.playerAndAi;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class CombatFeedbackKindTest {
    @Test
    void classifiesAuthoritativeDamageSources() {
        assertEquals(CombatFeedbackKind.EXPLOSIVE, CombatFeedbackKind.fromWeaponName("HE_GRENADE"));
        assertEquals(CombatFeedbackKind.EXPLOSIVE, CombatFeedbackKind.fromWeaponName("c4"));
        assertEquals(CombatFeedbackKind.FIRE, CombatFeedbackKind.fromWeaponName("MOLOTOV"));
        assertEquals(CombatFeedbackKind.FIRE, CombatFeedbackKind.fromWeaponName("INCENDIARY"));
        assertEquals(CombatFeedbackKind.MELEE, CombatFeedbackKind.fromWeaponName("KNIFE"));
        assertEquals(CombatFeedbackKind.MELEE, CombatFeedbackKind.fromWeaponName("FISTS"));
        assertEquals(CombatFeedbackKind.MELEE, CombatFeedbackKind.fromWeaponName("ZOMBIE_CLAW"));
        assertEquals(CombatFeedbackKind.NORMAL, CombatFeedbackKind.fromWeaponName("NEGEV"));
        assertEquals(CombatFeedbackKind.NORMAL, CombatFeedbackKind.fromWeaponName(null));
    }
}
