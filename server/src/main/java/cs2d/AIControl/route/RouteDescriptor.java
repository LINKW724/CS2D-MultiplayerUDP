package cs2d.AIControl.route;

import java.util.List;
import java.util.Set;

/** Immutable metadata for one complete map-authored route. */
public record RouteDescriptor(
        String routeId,
        RouteType type,
        int routeIndex,
        List<RoutePoint> keyPoints,
        double length,
        int suggestedCapacity,
        Set<String> overlappingRouteIds) {

    public RouteDescriptor {
        if (routeId == null || routeId.isBlank()) {
            throw new IllegalArgumentException("routeId must not be blank");
        }
        if (type == null) {
            throw new IllegalArgumentException("type must not be null");
        }
        keyPoints = keyPoints == null ? List.of() : List.copyOf(keyPoints);
        overlappingRouteIds = overlappingRouteIds == null ? Set.of() : Set.copyOf(overlappingRouteIds);
        length = Math.max(0.0, length);
        suggestedCapacity = Math.max(0, suggestedCapacity);
    }
}
