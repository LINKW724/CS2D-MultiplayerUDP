package cs2d.AIControl.BG;

import cs2d.AIControl.B.PerceptionType;
import cs2d.playerAndAi.Player;
import cs2d.server.GameMode;
import org.junit.jupiter.api.Test;

import java.awt.geom.Point2D;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TeamSoundResponsePolicyTest {

    @Test
    void capsLargeTeamGunshotResponseAtFourNearestAi() {
        List<Player> team = lineOfPlayers(25);
        Point2D.Double sound = new Point2D.Double(0, 0);

        for (int i = 0; i < team.size(); i++) {
            assertEquals(i < 4,
                    TeamSoundResponsePolicy.shouldRespond(team.get(i), sound, PerceptionType.GUNSHOT, team));
        }
    }

    @Test
    void usesSmallerSquadsForSmallTeamsAndFootsteps() {
        assertEquals(1, TeamSoundResponsePolicy.responseLimit(PerceptionType.GUNSHOT, 5));
        assertEquals(2, TeamSoundResponsePolicy.responseLimit(PerceptionType.GUNSHOT, 10));
        assertEquals(4, TeamSoundResponsePolicy.responseLimit(PerceptionType.GUNSHOT, 25));
        assertEquals(2, TeamSoundResponsePolicy.responseLimit(PerceptionType.FOOTSTEP, 25));
    }

    @Test
    void breaksDistanceTiesDeterministicallyByPlayerId() {
        Player botB = player("bot-b", 100, 0);
        Player botA = player("bot-a", -100, 0);
        List<Player> team = List.of(botB, botA);
        Point2D.Double sound = new Point2D.Double(0, 0);

        assertTrue(TeamSoundResponsePolicy.shouldRespond(botA, sound, PerceptionType.GUNSHOT, team));
        assertFalse(TeamSoundResponsePolicy.shouldRespond(botB, sound, PerceptionType.GUNSHOT, team));
    }

    @Test
    void neverAppliesSoundQuotaToSightInformation() {
        List<Player> team = lineOfPlayers(5);

        assertEquals(0, TeamSoundResponsePolicy.responseLimit(PerceptionType.SIGHT, team.size()));
        assertFalse(TeamSoundResponsePolicy.shouldRespond(team.get(0), new Point2D.Double(),
                PerceptionType.SIGHT, team));
    }

    private static List<Player> lineOfPlayers(int count) {
        List<Player> players = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            players.add(player(String.format("bot-%02d", i), i * 10.0, 0));
        }
        return players;
    }

    private static Player player(String id, double x, double y) {
        return new Player(id, id, new Point2D.Double(x, y), Player.Team.CT, false,
                GameMode.TEAM_DEATHMATCH, null, null, null);
    }
}
