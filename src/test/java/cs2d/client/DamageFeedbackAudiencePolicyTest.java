package cs2d.client;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class DamageFeedbackAudiencePolicyTest {
    @Test
    void detachedSpectatorReceivesAllZombieFeedbackOnlyAtAllScope() {
        assertTrue(accepts(DamageNumberVisibility.ALL, DamageNumberModePolicy.ZOMBIE_MODE,
                true, "CT", "", false));
        assertTrue(accepts(DamageNumberVisibility.ALL, DamageNumberModePolicy.ZOMBIE_MODE,
                true, "T", "", false));

        assertFalse(accepts(DamageNumberVisibility.TEAMMATES, DamageNumberModePolicy.ZOMBIE_MODE,
                true, "CT", "", false));
        assertFalse(accepts(DamageNumberVisibility.ALL, "TEAM_DEATHMATCH",
                true, "CT", "", false));
    }

    @Test
    void teamViewerStillRequiresMatchingTeamAndNonLocalAttacker() {
        assertTrue(accepts(DamageNumberVisibility.ALL, DamageNumberModePolicy.ZOMBIE_MODE,
                false, "CT", "CT", false));
        assertFalse(accepts(DamageNumberVisibility.ALL, DamageNumberModePolicy.ZOMBIE_MODE,
                false, "T", "CT", false));
        assertFalse(accepts(DamageNumberVisibility.ALL, DamageNumberModePolicy.ZOMBIE_MODE,
                false, "CT", "CT", true));
        assertFalse(accepts(DamageNumberVisibility.OWN, DamageNumberModePolicy.ZOMBIE_MODE,
                false, "CT", "CT", false));
    }

    private static boolean accepts(DamageNumberVisibility visibility, String mode,
            boolean detachedSpectator, String feedbackTeam, String localTeam, boolean localAttacker) {
        return DamageFeedbackAudiencePolicy.acceptsTeamEvent(
                visibility, mode, detachedSpectator, feedbackTeam, localTeam, localAttacker);
    }
}
