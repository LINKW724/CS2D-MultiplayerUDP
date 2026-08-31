package cs2d.AIControl.A;

import cs2d.AIControl.BW.MapViz.DynamicPathfinderVisualizer.PathType;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Coordinates preset-route selection between AI instances.
 *
 * <p>Each map/path-type pair advances through all available routes before a
 * route is reused. This preserves the map author's topology-aware routes while
 * preventing a large spawn wave from independently choosing the same route.</p>
 */
final class PresetRouteAllocator {

    private static final ConcurrentMap<RouteGroup, AtomicLong> NEXT_TICKETS = new ConcurrentHashMap<>();

    private PresetRouteAllocator() {
    }

    static int nextRouteIndex(String mapName, PathType pathType, int routeCount) {
        if (routeCount <= 0) {
            throw new IllegalArgumentException("routeCount must be positive");
        }

        RouteGroup group = new RouteGroup(normalizeMapName(mapName),
                Objects.requireNonNull(pathType, "pathType"), routeCount);
        AtomicLong nextTicket = NEXT_TICKETS.computeIfAbsent(group,
                key -> new AtomicLong(Math.floorMod(key.hashCode(), routeCount)));
        return (int) Math.floorMod(nextTicket.getAndIncrement(), routeCount);
    }

    static void resetForTests() {
        NEXT_TICKETS.clear();
    }

    private static String normalizeMapName(String mapName) {
        return mapName == null ? "" : mapName.trim();
    }

    private record RouteGroup(String mapName, PathType pathType, int routeCount) {
    }
}
