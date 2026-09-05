package cs2d.server;

/** Single mode boundary for spawn invulnerability. */
public final class SpawnProtectionPolicy {
    private SpawnProtectionPolicy() {}

    public static boolean enabled(GameMode mode) {
        return mode != GameMode.ZOMBIE_MODE;
    }
}
