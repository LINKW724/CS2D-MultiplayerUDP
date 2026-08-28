package cs2d.server.rl;

import java.awt.geom.Point2D;
import cs2d.server.Item;

/**
 * The unified action space input fed from the Python RLlib agent to the Java
 * CS2D backend.
 * Represents optimal commands mapped exactly to the 7-Dimensional tactical
 * space (plus Economy & Comm signals).
 */
public record RLMacroCommand(
        Point2D.Double navTarget, // 1. Navigation Coordinate (X, Y)
        boolean stealthMode, // 2. Movement mode (true = walk, false = run)
        Item requestGrenade, // 3. Grenade selection (Item.SMOKE, FLASHBANG, HE, FIRE, or NULL)
        Point2D.Double grenadeTarget, // 4. Grenade explosion target (X, Y)
        boolean interact, // 5. Interact action (Plant/Defuse C4, open doors)
        boolean dropItem, // 6. Tactical drop (Drop C4, drop current weapon)
        Point2D.Double holdAngleTarget, // 7. Holding Angle Target for instant pre-aim/firing (X, Y)

        // Communication & Economics Layer
        String buyCommand, // E.g., "ECO", "FORCE_BUY", "FULL_BUY", "DROP_AWP"
        String radioMessage, // Discrete latent ID or message to team for synchronization
        Point2D.Double radioTarget // Associated coordinate for the signal
) {
}
