package cs2d.server;

import cs2d.playerAndAi.Player;

/** Keeps generic hit reactions separate from mode-specific melee mobility. */
public final class DamageMovementPolicy {
    private static final long DEFAULT_SLOW_MS = 300;

    private DamageMovementPolicy() {}

    public static long slowDurationMillis(GameMode mode, Player.Team attackerTeam, String damageSource) {
        boolean zombieMelee = mode == GameMode.ZOMBIE_MODE && attackerTeam == Player.Team.ZOMBIE
                && ("KNIFE".equals(damageSource) || "ZOMBIE_CLAW".equals(damageSource));
        return zombieMelee ? 0 : DEFAULT_SLOW_MS;
    }
}
