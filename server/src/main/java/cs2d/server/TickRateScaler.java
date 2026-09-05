package cs2d.server;

/** Converts legacy 120Hz per-tick motion constants to another simulation rate. */
final class TickRateScaler {
    static final double LEGACY_TICK_RATE = 120.0;

    private TickRateScaler() {
    }

    static double tickScale(double targetTickRate) {
        if (!Double.isFinite(targetTickRate) || targetTickRate <= 0)
            throw new IllegalArgumentException("targetTickRate must be positive");
        return LEGACY_TICK_RATE / targetTickRate;
    }

    static double friction(double legacyFriction, double targetTickRate) {
        return Math.pow(legacyFriction, tickScale(targetTickRate));
    }

    /**
     * Equivalent acceleration before applying the combined friction step. This
     * exactly matches N legacy updates when the rate ratio N is integral and no
     * speed clamp is reached.
     */
    static double acceleration(double legacyAcceleration, double legacyFriction, double targetTickRate) {
        double scale = tickScale(targetTickRate);
        double combinedFriction = Math.pow(legacyFriction, scale);
        if (Math.abs(1.0 - legacyFriction) < 1e-12)
            return legacyAcceleration * scale;
        return legacyAcceleration * legacyFriction * (1.0 - combinedFriction)
                / ((1.0 - legacyFriction) * combinedFriction);
    }

    static double displacementScale(double targetTickRate) {
        return tickScale(targetTickRate);
    }

    static int integralLegacySubsteps(double targetTickRate) {
        double scale = tickScale(targetTickRate);
        int rounded = (int) Math.round(scale);
        if (rounded < 1 || Math.abs(scale - rounded) > 1e-9)
            throw new IllegalArgumentException("targetTickRate must divide the legacy 120Hz rate");
        return rounded;
    }
}
