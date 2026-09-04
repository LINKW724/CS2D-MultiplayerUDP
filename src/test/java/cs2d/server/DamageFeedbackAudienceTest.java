package cs2d.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

import cs2d.playerAndAi.Player;

class DamageFeedbackAudienceTest {
    @Test
    void sharesOnlySurvivorDamageAgainstZombiesInZombieMode() {
        assertEquals("CT", GameState.damageFeedbackTeam(
                GameMode.ZOMBIE_MODE, Player.Team.CT, Player.Team.ZOMBIE));
        assertEquals("T", GameState.damageFeedbackTeam(
                GameMode.ZOMBIE_MODE, Player.Team.T, Player.Team.ZOMBIE));

        assertNull(GameState.damageFeedbackTeam(
                GameMode.ZOMBIE_MODE, Player.Team.ZOMBIE, Player.Team.CT));
        assertNull(GameState.damageFeedbackTeam(
                GameMode.ZOMBIE_MODE, Player.Team.CT, Player.Team.CT));
        assertNull(GameState.damageFeedbackTeam(
                GameMode.TEAM_DEATHMATCH, Player.Team.CT, Player.Team.ZOMBIE));
    }
}
