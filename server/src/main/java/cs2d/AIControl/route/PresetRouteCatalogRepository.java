package cs2d.AIControl.route;

import com.google.gson.Gson;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Loads and caches immutable map route catalogs. */
public final class PresetRouteCatalogRepository {

    public static final String ROUTE_DIRECTORY_PROPERTY = "cs2d.prePath.dir";
    private static final Path LEGACY_ROUTE_DIRECTORY =
            Paths.get("O:\\java\\games\\CS2D-MultiplayerUDP\\maps\\prePath");
    private static final double OVERLAP_DISTANCE = 180.0;
    private static final double OVERLAP_RATIO = 0.35;
    private static final ConcurrentMap<String, RouteCatalog> CACHE = new ConcurrentHashMap<>();
    private static final Object LOAD_LOCK = new Object();

    private PresetRouteCatalogRepository() {
    }

    public static RouteCatalog load(String mapName) {
        String normalized = normalizeMapName(mapName);
        RouteCatalog cached = CACHE.get(normalized);
        if (cached != null) {
            return cached;
        }

        synchronized (LOAD_LOCK) {
            cached = CACHE.get(normalized);
            if (cached != null) {
                return cached;
            }
            Path file = routeDirectory().resolve(normalized + "_prePath.json");
            if (!Files.isRegularFile(file)) {
                return new ImmutableRouteCatalog(normalized, List.of());
            }
            try (Reader reader = Files.newBufferedReader(file)) {
                RouteCatalog loaded = read(normalized, reader);
                CACHE.put(normalized, loaded);
                logLoaded(loaded);
                return loaded;
            } catch (IOException | RuntimeException exception) {
                return new ImmutableRouteCatalog(normalized, List.of());
            }
        }
    }

    static RouteCatalog read(String mapName, Reader reader) {
        PresetPathCollection collection = new Gson().fromJson(reader, PresetPathCollection.class);
        if (collection == null) {
            return new ImmutableRouteCatalog(normalizeMapName(mapName), List.of());
        }

        List<RouteSeed> seeds = new ArrayList<>();
        addSeeds(seeds, RouteType.TDM_T_CT, collection.presetPathsTtoCT);
        addSeeds(seeds, RouteType.TDM_CT_T, collection.presetPathsCTtoT);
        addSeeds(seeds, RouteType.DEMO_CT_A, collection.presetPathsCTtoA);
        addSeeds(seeds, RouteType.DEMO_CT_B, collection.presetPathsCTtoB);
        addSeeds(seeds, RouteType.DEMO_T_A, collection.presetPathsTtoA);
        addSeeds(seeds, RouteType.DEMO_T_B, collection.presetPathsTtoB);

        Map<String, Set<String>> overlaps = calculateOverlaps(seeds);
        List<RouteDescriptor> descriptors = seeds.stream()
                .map(seed -> new RouteDescriptor(seed.routeId(), seed.type(), seed.routeIndex(), seed.keyPoints(),
                        routeLength(seed.keyPoints()), 0, overlaps.getOrDefault(seed.routeId(), Set.of())))
                .toList();
        return new ImmutableRouteCatalog(normalizeMapName(mapName), descriptors);
    }

    static void clearForTests() {
        CACHE.clear();
    }

    private static void addSeeds(List<RouteSeed> destination, RouteType type, List<List<PointDto>> source) {
        if (source == null) {
            return;
        }
        for (int index = 0; index < source.size(); index++) {
            List<PointDto> rawPoints = source.get(index);
            if (rawPoints == null) {
                continue;
            }
            List<RoutePoint> points = rawPoints.stream()
                    .filter(point -> point != null && Double.isFinite(point.x) && Double.isFinite(point.y))
                    .map(point -> new RoutePoint(point.x, point.y))
                    .toList();
            if (!points.isEmpty()) {
                destination.add(new RouteSeed(type.name() + ':' + index, type, index, points));
            }
        }
    }

    private static Map<String, Set<String>> calculateOverlaps(List<RouteSeed> routes) {
        Map<String, Set<String>> overlaps = new HashMap<>();
        for (int i = 0; i < routes.size(); i++) {
            RouteSeed first = routes.get(i);
            for (int j = i + 1; j < routes.size(); j++) {
                RouteSeed second = routes.get(j);
                if (!first.type().teamId().equals(second.type().teamId())
                        || first.type().isTdm() != second.type().isTdm()
                        || !overlaps(first, second)) {
                    continue;
                }
                overlaps.computeIfAbsent(first.routeId(), ignored -> new HashSet<>()).add(second.routeId());
                overlaps.computeIfAbsent(second.routeId(), ignored -> new HashSet<>()).add(first.routeId());
            }
        }
        return overlaps;
    }

    private static boolean overlaps(RouteSeed first, RouteSeed second) {
        double firstRatio = proximityRatio(first.keyPoints(), second.keyPoints());
        double secondRatio = proximityRatio(second.keyPoints(), first.keyPoints());
        return (firstRatio + secondRatio) * 0.5 >= OVERLAP_RATIO;
    }

    private static double proximityRatio(List<RoutePoint> samples, List<RoutePoint> path) {
        if (samples.isEmpty() || path.isEmpty()) {
            return 0.0;
        }
        double maxDistanceSq = OVERLAP_DISTANCE * OVERLAP_DISTANCE;
        long nearCount = samples.stream()
                .filter(point -> distanceToPolylineSq(point, path) <= maxDistanceSq)
                .count();
        return (double) nearCount / samples.size();
    }

    private static double distanceToPolylineSq(RoutePoint point, List<RoutePoint> path) {
        double best = Double.POSITIVE_INFINITY;
        for (int index = 0; index < path.size(); index++) {
            best = Math.min(best, distanceSq(point, path.get(index)));
            if (index > 0) {
                best = Math.min(best, pointToSegmentDistanceSq(point, path.get(index - 1), path.get(index)));
            }
        }
        return best;
    }

    private static double pointToSegmentDistanceSq(RoutePoint point, RoutePoint start, RoutePoint end) {
        double dx = end.x() - start.x();
        double dy = end.y() - start.y();
        double lengthSq = dx * dx + dy * dy;
        if (lengthSq <= 1.0e-9) {
            return distanceSq(point, start);
        }
        double ratio = ((point.x() - start.x()) * dx + (point.y() - start.y()) * dy) / lengthSq;
        ratio = Math.max(0.0, Math.min(1.0, ratio));
        double nearestX = start.x() + ratio * dx;
        double nearestY = start.y() + ratio * dy;
        double pointDx = point.x() - nearestX;
        double pointDy = point.y() - nearestY;
        return pointDx * pointDx + pointDy * pointDy;
    }

    private static double routeLength(List<RoutePoint> points) {
        double total = 0.0;
        for (int index = 1; index < points.size(); index++) {
            total += points.get(index - 1).distance(points.get(index));
        }
        return total;
    }

    private static double distanceSq(RoutePoint first, RoutePoint second) {
        double dx = first.x() - second.x();
        double dy = first.y() - second.y();
        return dx * dx + dy * dy;
    }

    private static Path routeDirectory() {
        String override = System.getProperty(ROUTE_DIRECTORY_PROPERTY);
        if (override != null && !override.isBlank()) {
            return Paths.get(override);
        }
        Path relative = Paths.get("maps", "prePath");
        return Files.isDirectory(relative) ? relative : LEGACY_ROUTE_DIRECTORY;
    }

    private static String normalizeMapName(String mapName) {
        String normalized = mapName == null ? "" : mapName.trim();
        return normalized.endsWith(".json")
                ? normalized.substring(0, normalized.length() - ".json".length())
                : normalized;
    }

    private static void logLoaded(RouteCatalog catalog) {
        System.out.println("PresetRouteCatalog: loaded map " + catalog.mapName() + " ("
                + catalog.routes().size() + " routes)");
        for (RouteType type : RouteType.values()) {
            System.out.println("  - " + type + ": " + catalog.routesFor(type).size());
        }
    }

    private static final class ImmutableRouteCatalog implements RouteCatalog {
        private final String mapName;
        private final List<RouteDescriptor> routes;
        private final Map<RouteType, List<RouteDescriptor>> byType;
        private final Map<String, RouteDescriptor> byId;

        private ImmutableRouteCatalog(String mapName, List<RouteDescriptor> routes) {
            this.mapName = mapName;
            this.routes = List.copyOf(routes);
            Map<RouteType, List<RouteDescriptor>> grouped = new EnumMap<>(RouteType.class);
            Map<String, RouteDescriptor> indexed = new LinkedHashMap<>();
            for (RouteDescriptor route : routes) {
                grouped.computeIfAbsent(route.type(), ignored -> new ArrayList<>()).add(route);
                indexed.put(route.routeId(), route);
            }
            grouped.replaceAll((type, values) -> List.copyOf(values));
            this.byType = Collections.unmodifiableMap(grouped);
            this.byId = Collections.unmodifiableMap(indexed);
        }

        @Override
        public String mapName() {
            return mapName;
        }

        @Override
        public List<RouteDescriptor> routes() {
            return routes;
        }

        @Override
        public List<RouteDescriptor> routesFor(RouteType type) {
            return type == null ? List.of() : byType.getOrDefault(type, List.of());
        }

        @Override
        public Optional<RouteDescriptor> find(String routeId) {
            return Optional.ofNullable(byId.get(routeId));
        }
    }

    private record RouteSeed(String routeId, RouteType type, int routeIndex, List<RoutePoint> keyPoints) {
        private RouteSeed {
            keyPoints = List.copyOf(keyPoints);
        }
    }

    private static final class PointDto {
        double x;
        double y;
    }

    private static final class PresetPathCollection {
        List<List<PointDto>> presetPathsTtoCT;
        List<List<PointDto>> presetPathsCTtoT;
        List<List<PointDto>> presetPathsCTtoA;
        List<List<PointDto>> presetPathsCTtoB;
        List<List<PointDto>> presetPathsTtoA;
        List<List<PointDto>> presetPathsTtoB;
    }
}
