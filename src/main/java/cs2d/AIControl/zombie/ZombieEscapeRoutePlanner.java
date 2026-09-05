package cs2d.AIControl.zombie;

import cs2d.AIControl.zombie.ZombieTacticalSnapshot.Vec;
import java.awt.geom.Line2D;
import java.util.*;
import java.util.function.BiPredicate;
import java.util.function.Predicate;

/** Plans a committed primary escape route plus spatially distinct fallbacks. */
public final class ZombieEscapeRoutePlanner {
    private static final double STEP = 32;
    private static final int DEFAULT_MAX_DEPTH = 18;
    private static final double SURVIVOR_SPEED = 240;
    private static final double ZOMBIE_SPEED = 180;
    private static final int[][] DIRECTIONS = {
            { 1, 0 }, { 1, 1 }, { 0, 1 }, { -1, 1 },
            { -1, 0 }, { -1, -1 }, { 0, -1 }, { 1, -1 }
    };

    public EscapePlan plan(Vec origin, List<Vec> threats, List<Vec> teammates,
            List<ZombieAreaHazard> hazards, long now, Predicate<Vec> walkable,
            BiPredicate<Vec, Vec> traversable) {
        return plan(origin, threats, teammates, hazards, now, walkable, traversable,
                SearchProfile.DEFAULT);
    }

    public EscapePlan plan(Vec origin, List<Vec> threats, List<Vec> teammates,
            List<ZombieAreaHazard> hazards, long now, Predicate<Vec> walkable,
            BiPredicate<Vec, Vec> traversable, SearchProfile profile) {
        if (origin == null) return EscapePlan.EMPTY;
        SearchProfile search = profile == null ? SearchProfile.DEFAULT : profile;
        List<Vec> knownThreats = threats == null ? List.of() : threats;
        if (knownThreats.isEmpty() && !hazardous(origin, hazards, now)) return EscapePlan.EMPTY;
        Map<Cell, SearchNode> reached = new LinkedHashMap<>();
        ArrayDeque<SearchNode> open = new ArrayDeque<>();
        SearchNode start = new SearchNode(new Cell(0, 0), origin, null, 0);
        reached.put(start.cell(), start);
        open.add(start);
        while (!open.isEmpty()) {
            SearchNode node = open.removeFirst();
            if (node.depth() >= search.explorationDepth()) continue;
            for (int[] direction : DIRECTIONS) {
                Cell cell = new Cell(node.cell().x() + direction[0], node.cell().y() + direction[1]);
                if (reached.containsKey(cell)) continue;
                Vec point = new Vec(origin.x() + cell.x() * STEP, origin.y() + cell.y() * STEP);
                if (!walkable.test(point) || !traversable.test(node.point(), point)
                        || hazardous(point, hazards, now) && !hazardous(node.point(), hazards, now)
                        || crossesThreat(node.point(), point, knownThreats)) continue;
                SearchNode next = new SearchNode(cell, point, node, node.depth() + 1);
                reached.put(cell, next);
                open.addLast(next);
            }
        }

        TopologyField topology = TopologyField.from(reached.keySet());
        List<ScoredRoute> candidates = reached.values().stream()
                .filter(node -> node.depth() >= 4 && node.depth() <= search.routeDepth())
                .map(node -> score(origin, node, knownThreats, teammates, hazards, now,
                        walkable, traversable, search, topology))
                .filter(route -> Double.isFinite(route.score()))
                .sorted(Comparator.comparingDouble(ScoredRoute::score).reversed())
                .toList();
        List<EscapeRoute> selected = new ArrayList<>();
        for (ScoredRoute candidate : candidates) {
            if (selected.stream().anyMatch(existing -> overlap(existing.waypoints(), candidate.route()) > 0.68
                    && existing.destination().distance(candidate.route().get(candidate.route().size() - 1)) < 160
                    || departure(existing.waypoints()).distance(departure(candidate.route())) <= STEP * 1.25)) {
                continue;
            }
            selected.add(new EscapeRoute(candidate.route(), candidate.score()));
            if (selected.size() == 3) break;
        }
        // A one-exit room may force a shared opening; retain later-diverging backups in a second pass.
        if (selected.size() < 3) {
            for (ScoredRoute candidate : candidates) {
                if (selected.stream().anyMatch(existing -> overlap(existing.waypoints(), candidate.route()) > 0.86
                        || existing.destination().distance(candidate.route().get(candidate.route().size() - 1)) < 96))
                    continue;
                selected.add(new EscapeRoute(candidate.route(), candidate.score()));
                if (selected.size() == 3) break;
            }
        }
        if (selected.isEmpty() && !candidates.isEmpty())
            selected.add(new EscapeRoute(candidates.get(0).route(), candidates.get(0).score()));
        return new EscapePlan(selected);
    }

    public boolean routeStillSafe(EscapeRoute route, int fromIndex, List<Vec> threats,
            List<ZombieAreaHazard> hazards, long now) {
        if (route == null || route.waypoints().isEmpty()) return false;
        List<Vec> remaining = route.waypoints().subList(Math.max(0, Math.min(fromIndex,
                route.waypoints().size() - 1)), route.waypoints().size());
        return remaining.stream().noneMatch(point -> hazardous(point, hazards, now))
                && interceptionPenalty(remaining, threats) < 520;
    }

    private ScoredRoute score(Vec origin, SearchNode node, List<Vec> threats, List<Vec> teammates,
            List<ZombieAreaHazard> hazards, long now, Predicate<Vec> walkable,
            BiPredicate<Vec, Vec> traversable, SearchProfile profile, TopologyField topology) {
        List<Vec> route = route(node);
        Vec destination = node.point();
        double clearance = threats.stream().mapToDouble(destination::distance).min().orElse(0);
        double allyClearance = teammates == null ? 120 : teammates.stream()
                .mapToDouble(destination::distance).min().orElse(120);
        if (allyClearance < 36) return new ScoredRoute(route, Double.NEGATIVE_INFINITY);
        double allySupport = teammateSupport(allyClearance, teammates);
        int exits = 0;
        int openSpace = 0;
        for (int[] direction : DIRECTIONS) {
            Vec near = new Vec(destination.x() + direction[0] * STEP,
                    destination.y() + direction[1] * STEP);
            if (walkable.test(near) && traversable.test(destination, near)) exits++;
            Vec far = new Vec(destination.x() + direction[0] * STEP * 3,
                    destination.y() + direction[1] * STEP * 3);
            if (walkable.test(far) && traversable.test(destination, far)) openSpace++;
        }
        double deadEndPenalty = exits <= 2 ? 1_100 : exits == 3 ? 320 : 0;
        if (openSpace <= 2) deadEndPenalty += 700;
        int regionalCells = topology.count(node.cell(), profile.topologyRadiusCells());
        int regionalCapacity = square(profile.topologyRadiusCells() * 2 + 1);
        double regionalFreedom = regionalCells / (double) regionalCapacity;
        if (regionalFreedom < 0.42) deadEndPenalty += (0.42 - regionalFreedom) * 2_800;
        double routeClearance = route.stream().skip(2)
                .flatMapToDouble(point -> threats.stream().mapToDouble(point::distance)).min().orElse(0);
        double edgeClearance = Math.min(Math.min(destination.x(), destination.y()),
                Math.min(profile.mapWidth() - destination.x(), profile.mapHeight() - destination.y()));
        if (edgeClearance < 220) deadEndPenalty += (220 - Math.max(0, edgeClearance)) * 3.5;
        double travel = origin.distance(destination);
        double travelValue = Math.min(profile.preferredTravelDistance(), travel) * 0.35
                - Math.max(0, travel - profile.preferredTravelDistance()) * 0.08;
        double score = Math.min(650, clearance) * 3.6
                + Math.min(500, routeClearance) * 1.2
                + exits * 90 + openSpace * 75
                + regionalFreedom * 1_250
                + Math.min(280, Math.max(0, edgeClearance)) * 0.35
                + allySupport
                + travelValue
                - deadEndPenalty - interceptionPenalty(route, threats);
        return new ScoredRoute(route, score);
    }

    private static double interceptionPenalty(List<Vec> route, List<Vec> threats) {
        if (threats == null || threats.isEmpty()) return 0;
        double travelled = 0;
        double penalty = 0;
        for (int i = 1; i < route.size(); i++) {
            travelled += route.get(i - 1).distance(route.get(i));
            if (i < 3) continue;
            double survivorEta = travelled / SURVIVOR_SPEED;
            double zombieEta = threats.stream().mapToDouble(route.get(i)::distance).min().orElse(10_000)
                    / ZOMBIE_SPEED;
            if (zombieEta <= survivorEta + 0.12) penalty += 260;
        }
        return penalty;
    }

    private static List<Vec> route(SearchNode node) {
        LinkedList<Vec> route = new LinkedList<>();
        for (SearchNode current = node; current != null; current = current.parent())
            route.addFirst(current.point());
        return List.copyOf(route);
    }

    private static double overlap(List<Vec> a, List<Vec> b) {
        int limit = Math.min(a.size(), b.size());
        if (limit <= 1) return 0;
        int shared = 0;
        for (int i = 1; i < limit; i++) if (a.get(i).distance(b.get(i)) <= STEP * 1.5) shared++;
        return shared / (double) (limit - 1);
    }

    private static Vec departure(List<Vec> route) {
        return route.get(Math.min(3, route.size() - 1));
    }

    private static int square(int value) { return value * value; }

    private static double teammateSupport(double nearestAlly, List<Vec> teammates) {
        if (teammates == null || teammates.isEmpty()) return 0;
        if (nearestAlly <= 420) return 260 - Math.abs(nearestAlly - 220) * 0.55;
        return -Math.min(520, (nearestAlly - 420) * 0.65);
    }

    private static boolean hazardous(Vec point, List<ZombieAreaHazard> hazards, long now) {
        return hazards != null && hazards.stream().filter(Objects::nonNull)
                .anyMatch(hazard -> hazard.active(now) && hazard.contains(point, 20));
    }

    private static boolean crossesThreat(Vec from, Vec to, List<Vec> threats) {
        return threats.stream().anyMatch(threat -> Line2D.ptSegDist(from.x(), from.y(), to.x(), to.y(),
                threat.x(), threat.y()) < 28 && to.distance(threat) <= from.distance(threat) + 2);
    }

    public record EscapePlan(List<EscapeRoute> routes) {
        public static final EscapePlan EMPTY = new EscapePlan(List.of());
        public EscapePlan { routes = List.copyOf(routes); }
        public boolean empty() { return routes.isEmpty(); }
    }
    public record EscapeRoute(List<Vec> waypoints, double score) {
        public EscapeRoute { waypoints = List.copyOf(waypoints); }
        public Vec destination() { return waypoints.get(waypoints.size() - 1); }
    }

    /** Map-sized search budget; the topology halo lets boundary candidates see beyond their endpoint. */
    public record SearchProfile(int routeDepth, int topologyRadiusCells,
            double preferredTravelDistance, double mapWidth, double mapHeight) {
        public static final SearchProfile DEFAULT = new SearchProfile(
                DEFAULT_MAX_DEPTH, 5, 480, 2_000, 2_000);

        public SearchProfile {
            routeDepth = Math.max(DEFAULT_MAX_DEPTH, Math.min(40, routeDepth));
            topologyRadiusCells = Math.max(4, Math.min(9, topologyRadiusCells));
            preferredTravelDistance = Math.max(320, preferredTravelDistance);
            mapWidth = Math.max(STEP * 2, mapWidth);
            mapHeight = Math.max(STEP * 2, mapHeight);
        }

        public static SearchProfile forMap(double mapWidth, double mapHeight) {
            double diagonal = Math.hypot(Math.max(1, mapWidth), Math.max(1, mapHeight));
            double radius = Math.max(DEFAULT_MAX_DEPTH * STEP, Math.min(1_280, diagonal * 0.28));
            int depth = (int) Math.ceil(radius / STEP);
            int topologyRadius = Math.max(5, Math.min(9, depth / 5));
            return new SearchProfile(depth, topologyRadius, radius * 0.72, mapWidth, mapHeight);
        }

        int explorationDepth() { return routeDepth + topologyRadiusCells; }
        public double routeRadius() { return routeDepth * STEP; }
    }

    private static final class TopologyField {
        private final int minX;
        private final int minY;
        private final int[][] prefix;

        private TopologyField(int minX, int minY, int[][] prefix) {
            this.minX = minX;
            this.minY = minY;
            this.prefix = prefix;
        }

        static TopologyField from(Set<Cell> cells) {
            int minX = cells.stream().mapToInt(Cell::x).min().orElse(0);
            int maxX = cells.stream().mapToInt(Cell::x).max().orElse(0);
            int minY = cells.stream().mapToInt(Cell::y).min().orElse(0);
            int maxY = cells.stream().mapToInt(Cell::y).max().orElse(0);
            int[][] prefix = new int[maxX - minX + 2][maxY - minY + 2];
            for (Cell cell : cells) prefix[cell.x() - minX + 1][cell.y() - minY + 1] = 1;
            for (int x = 1; x < prefix.length; x++) {
                for (int y = 1; y < prefix[x].length; y++) {
                    prefix[x][y] += prefix[x - 1][y] + prefix[x][y - 1] - prefix[x - 1][y - 1];
                }
            }
            return new TopologyField(minX, minY, prefix);
        }

        int count(Cell center, int radius) {
            int x1 = Math.max(0, center.x() - radius - minX);
            int y1 = Math.max(0, center.y() - radius - minY);
            int x2 = Math.min(prefix.length - 2, center.x() + radius - minX);
            int y2 = Math.min(prefix[0].length - 2, center.y() + radius - minY);
            if (x1 > x2 || y1 > y2) return 0;
            return prefix[x2 + 1][y2 + 1] - prefix[x1][y2 + 1]
                    - prefix[x2 + 1][y1] + prefix[x1][y1];
        }
    }
    private record SearchNode(Cell cell, Vec point, SearchNode parent, int depth) {}
    private record Cell(int x, int y) {}
    private record ScoredRoute(List<Vec> route, double score) {}
}
