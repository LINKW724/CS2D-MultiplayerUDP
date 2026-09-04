package cs2d.client;

/** Pure policy for deciding whether a viewer may consume a team-audience damage event. */
final class DamageFeedbackAudiencePolicy {
    private DamageFeedbackAudiencePolicy() {
    }

    static boolean acceptsTeamEvent(
            DamageNumberVisibility visibility,
            String gameMode,
            boolean detachedSpectator,
            String feedbackTeam,
            String localTeam,
            boolean localAttacker,
            boolean localVictim) {
        if (visibility == null || !visibility.showsTeammateDamage()
                || feedbackTeam == null || feedbackTeam.isBlank() || localAttacker || localVictim) {
            return false;
        }
        if (detachedSpectator) {
            return visibility == DamageNumberVisibility.ALL
                    && DamageNumberModePolicy.ZOMBIE_MODE.equals(gameMode);
        }
        return feedbackTeam.equals(localTeam);
    }
}
