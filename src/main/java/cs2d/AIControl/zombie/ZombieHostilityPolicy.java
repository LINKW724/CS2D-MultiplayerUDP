package cs2d.AIControl.zombie;

import cs2d.playerAndAi.Player;

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

    /** Bullets neither damage nor collide with teammates in zombie mode. */
    public static boolean bulletFireAllowed(Player owner, Player target) {
        return owner != null && owner.team == Player.Team.CT && canTarget(owner, target);
    }
}
