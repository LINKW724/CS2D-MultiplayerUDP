package cs2d.server;

import java.awt.geom.Point2D;

/** Pure circle-circle separation math, including the coincident-centre case. */
public final class PlayerPairSeparation {
    private static final double ZERO_EPSILON_SQ = 1.0e-12;

    private PlayerPairSeparation() {}

    public static Correction calculate(String firstId, Point2D.Double first,
            String secondId, Point2D.Double second, double totalRadius, double margin) {
        double dx = first.x - second.x;
        double dy = first.y - second.y;
        double distanceSq = dx * dx + dy * dy;
        if (distanceSq >= totalRadius * totalRadius) return null;
        double nx;
        double ny;
        double distance;
        if (distanceSq <= ZERO_EPSILON_SQ) {
            int direction = firstId.compareTo(secondId) <= 0 ? 1 : -1;
            long hash = stablePairHash(firstId, secondId);
            double angle = (hash & 0xffffL) * (Math.PI * 2.0 / 65_536.0);
            nx = Math.cos(angle) * direction;
            ny = Math.sin(angle) * direction;
            distance = 0;
        } else {
            distance = Math.sqrt(distanceSq);
            nx = dx / distance;
            ny = dy / distance;
        }
        return new Correction(nx, ny, (totalRadius - distance + margin) / 2.0);
    }

    private static long stablePairHash(String firstId, String secondId) {
        String low = firstId.compareTo(secondId) <= 0 ? firstId : secondId;
        String high = firstId.compareTo(secondId) <= 0 ? secondId : firstId;
        return 31L * low.hashCode() + high.hashCode();
    }

    public record Correction(double nx, double ny, double pushPerPlayer) {}
}
