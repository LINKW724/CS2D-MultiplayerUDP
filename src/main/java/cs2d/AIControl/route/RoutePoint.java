package cs2d.AIControl.route;

/** Immutable world-space point used by the map-route domain. */
public record RoutePoint(double x, double y) {

    public double distance(RoutePoint other) {
        if (other == null) {
            return Double.POSITIVE_INFINITY;
        }
        return Math.hypot(x - other.x, y - other.y);
    }
}
