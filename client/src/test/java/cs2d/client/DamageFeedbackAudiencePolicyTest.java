package cs2d.client;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class DamageFeedbackAudiencePolicyTest {
    @Test
    void detachedSpectatorReceivesAllZombieFeedbackOnlyAtAllScope() {
        assertTrue(accepts(DamageNumberVisibility.ALL, DamageNumberModePolicy.ZOMBIE_MODE,
                DamageFeedbackViewerContext.DETACHED_SPECTATOR, "CT", "", false));
        assertTrue(accepts(DamageNumberVisibility.ALL, DamageNumberModePolicy.ZOMBIE_MODE,
                DamageFeedbackViewerContext.DETACHED_SPECTATOR, "T", "", false));

        assertFalse(accepts(DamageNumberVisibility.TEAMMATES, DamageNumberModePolicy.ZOMBIE_MODE,
                DamageFeedbackViewerContext.DETACHED_SPECTATOR, "CT", "", false));
        assertFalse(accepts(DamageNumberVisibility.ALL, "TEAM_DEATHMATCH",
                DamageFeedbackViewerContext.DETACHED_SPECTATOR, "CT", "", false));
    }

    @Test
    void teamViewerStillRequiresMatchingTeamAndNonLocalAttacker() {
        assertTrue(accepts(DamageNumberVisibility.ALL, DamageNumberModePolicy.ZOMBIE_MODE,
                DamageFeedbackViewerContext.ACTIVE_PLAYER, "CT", "CT", false));
        assertFalse(accepts(DamageNumberVisibility.ALL, DamageNumberModePolicy.ZOMBIE_MODE,
                DamageFeedbackViewerContext.ACTIVE_PLAYER, "T", "CT", false));
        assertFalse(accepts(DamageNumberVisibility.ALL, DamageNumberModePolicy.ZOMBIE_MODE,
                DamageFeedbackViewerContext.ACTIVE_PLAYER, "CT", "CT", true));
        assertFalse(accepts(DamageNumberVisibility.ALL, DamageNumberModePolicy.ZOMBIE_MODE,
                DamageFeedbackViewerContext.ACTIVE_PLAYER, "CT", "CT", false, true));
        assertFalse(accepts(DamageNumberVisibility.OWN, DamageNumberModePolicy.ZOMBIE_MODE,
                DamageFeedbackViewerContext.ACTIVE_PLAYER, "CT", "CT", false));
    }

    @Test
    void attachedDeadSpectatorUsesTargetTeamForTeamScopeAndGlobalAudienceForAll() {
        assertTrue(accepts(DamageNumberVisibility.TEAMMATES, DamageNumberModePolicy.ZOMBIE_MODE,
                DamageFeedbackViewerContext.ATTACHED_SPECTATOR, "CT", "CT", false));
        assertFalse(accepts(DamageNumberVisibility.TEAMMATES, DamageNumberModePolicy.ZOMBIE_MODE,
                DamageFeedbackViewerContext.ATTACHED_SPECTATOR, "ZOMBIE", "CT", false));
        assertTrue(accepts(DamageNumberVisibility.ALL, DamageNumberModePolicy.ZOMBIE_MODE,
                DamageFeedbackViewerContext.ATTACHED_SPECTATOR, "ZOMBIE", "CT", false));
    }

    private static boolean accepts(DamageNumberVisibility visibility, String mode,
            DamageFeedbackViewerContext viewerContext, String feedbackTeam,
            String localTeam, boolean localAttacker) {
        return accepts(visibility, mode, viewerContext, feedbackTeam, localTeam, localAttacker, false);
    }

    private static boolean accepts(DamageNumberVisibility visibility, String mode,
            DamageFeedbackViewerContext viewerContext, String feedbackTeam, String localTeam,
            boolean localAttacker, boolean localVictim) {
        return DamageFeedbackAudiencePolicy.acceptsTeamEvent(
                visibility, mode, viewerContext, feedbackTeam, localTeam, localAttacker, localVictim);
    }
}
