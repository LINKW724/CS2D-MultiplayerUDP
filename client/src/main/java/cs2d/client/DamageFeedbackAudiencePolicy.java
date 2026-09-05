package cs2d.client;

/** Pure policy for deciding whether a viewer may consume a team-audience damage event. */
final class DamageFeedbackAudiencePolicy {
    private DamageFeedbackAudiencePolicy() {
    }

    static boolean acceptsTeamEvent(
            DamageNumberVisibility visibility,
            String gameMode,
            DamageFeedbackViewerContext viewerContext,
            String feedbackTeam,
            String localTeam,
            boolean localAttacker,
            boolean localVictim) {
        if (visibility == null || !visibility.showsTeammateDamage()
                || feedbackTeam == null || feedbackTeam.isBlank() || localAttacker || localVictim) {
            return false;
        }
        if (viewerContext == DamageFeedbackViewerContext.DETACHED_SPECTATOR) {
            return visibility == DamageNumberVisibility.ALL
                    && DamageNumberModePolicy.ZOMBIE_MODE.equals(gameMode);
        }
        if (viewerContext == DamageFeedbackViewerContext.ATTACHED_SPECTATOR
                && visibility == DamageNumberVisibility.ALL
                && DamageNumberModePolicy.ZOMBIE_MODE.equals(gameMode)) {
            return true;
        }
        return feedbackTeam.equals(localTeam);
    }
}
