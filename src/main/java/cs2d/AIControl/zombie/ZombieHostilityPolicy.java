package cs2d.AIControl.zombie;

import cs2d.playerAndAi.Player;
import java.awt.geom.Line2D;
import java.util.Collection;

/** One hostility boundary for perception, orders and final weapon execution. */
public final class ZombieHostilityPolicy {
    private ZombieHostilityPolicy() {}

    public static boolean hostile(Player.Team observer, Player.Team target) {
        return observer == Player.Team.CT && target == Player.Team.ZOMBIE
                || observer == Player.Team.ZOMBIE && target == Player.Team.CT;
    }

    public static boolean canTarget(Player observer, Player target) {
        return observer != null && target != null && observer != target
                && !observer.id.equals(target.id) && observer.isAlive() && target.isAlive()
                && target.position != null && hostile(observer.team, target.team);
    }

    /** Do not shoot through a living teammate standing between muzzle and target. */
    public static boolean clearShot(Player owner, Player target, Collection<Player> characters) {
        if (!canTarget(owner, target) || owner.team != Player.Team.CT || owner.position == null)
            return false;
        double targetDistance = owner.position.distanceSq(target.position);
        for (Player other : characters) {
            if (other == null || other == owner || !other.isAlive() || other.team != owner.team
                    || other.position == null || owner.position.distanceSq(other.position) >= targetDistance)
                continue;
            if (Line2D.ptSegDistSq(owner.position.x, owner.position.y,
                    target.position.x, target.position.y, other.position.x, other.position.y)
                    < Player.SIZE * Player.SIZE * 0.65 * 0.65)
                return false;
        }
        return true;
    }
}
