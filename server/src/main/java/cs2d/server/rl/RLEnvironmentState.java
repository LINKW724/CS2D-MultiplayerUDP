package cs2d.server.rl;

import java.util.List;
import java.util.Map;
import cs2d.server.AIService.PerceivedPlayer;

/**
 * The observation space state returned by the CS2D backend to the Python RLlib
 * training loop.
 */
public record RLEnvironmentState(
        int teamMoney, // Shared team economy representation for commander sync
        int lossBonus, // Round loss bonus info

        int hp, // Agent health
        int armor, // Agent armor
        boolean hasHelmet, // Agent helmet status
        String currentWeaponId, // Standardized String ID (e.g., "AK47", "AWP")

        int kills, // Agent session kills
        int deaths, // Agent session deaths
        int damageDealt, // Agent session damage dealt
        boolean isAlive, // Agent alive status
        int score, // Agent objective score

        String team, // "CT" or "T"
        double x, double y, // Agent position
        double bsaX, double bsaY, // Bomb Site A center
        double bsbX, double bsbY, // Bomb Site B center
        double c4X, double c4Y, // C4 position (depends on state)
        String c4State, // "CARRIED", "DROPPED", "PLANTED", "DEFUSED", "EXPLODED", "NONE"
        boolean hasBomb, // whether this specfic agent is carrying it
        String roundWinner, // "CT", "T", or null

        List<PerceivedPlayer> radarEntities, // Masked perception module data
        List<RadioSignal> teamRadioComms // A pool of past radio messages shared among teammates
) {
    public record RadioSignal(String authorId, String message, java.awt.geom.Point2D.Double target) {
    }
}
