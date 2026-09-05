package cs2d.AIControl.zombie;

import cs2d.AIControl.zombie.ZombieTacticalSnapshot.Vec;
import java.awt.geom.Line2D;
import java.util.*;
import java.util.function.BiPredicate;
import java.util.function.Predicate;

/** Plans a committed primary escape route plus spatially distinct fallbacks. */
public final class ZombieEscapeRoutePlanner {
    private static final double STEP = 32;
    private static final int MAX_DEPTH = 18;
    private static final double SURVIVOR_SPEED = 240;
    private static final double ZOMBIE_SPEED = 180;
    private static final int[][] DIRECTIONS = {
            { 1, 0 }, { 1, 1 }, { 0, 1 }, { -1, 1 },
            { -1, 0 }, { -1, -1 }, { 0, -1 }, { 1, -1 }
    };

    public EscapePlan plan(Vec origin, List<Vec> threats, List<Vec> teammates,
            List<ZombieAreaHazard> hazards, long now, Predicate<Vec> walkable,
            BiPredicate<Vec, Vec> traversable) {
        if (origin == null) return EscapePlan.EMPTY;
        List<Vec> knownThreats = threats == null ? List.of() : threats;
        if (knownThreats.isEmpty() && !hazardous(origin, hazards, now)) return EscapePlan.EMPTY;
        Map<Cell, SearchNode> reached = new LinkedHashMap<>();
        ArrayDeque<SearchNode> open = new ArrayDeque<>();
        SearchNode start = new SearchNode(new Cell(0, 0), origin, null, 0);
        reached.put(start.cell(), start);
        open.add(start);
        while (!open.isEmpty()) {
            SearchNode node = open.removeFirst();
            if (node.depth() >= MAX_DEPTH) continue;
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

        List<ScoredRoute> candidates = reached.values().stream()
                .filter(node -> node.depth() >= 4)
                .map(node -> score(origin, node, knownThreats, teammates, hazards, now, walkable, traversable))
                .filter(route -> Double.isFinite(route.score()))
                .sorted(Comparator.comparingDouble(ScoredRoute::score).reversed())
                .toList();
        List<EscapeRoute> selected = new ArrayList<>();
        for (ScoredRoute candidate : candidates) {
            if (selected.stream().anyMatch(existing -> overlap(existing.waypoints(), candidate.route()) > 0.68
                    && existing.destination().distance(candidate.route().get(candidate.route().size() - 1)) < 160)) {
                continue;
            }
            selected.add(new EscapeRoute(candidate.route(), candidate.score()));
            if (selected.size() == 3) break;
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
            BiPredicate<Vec, Vec> traversable) {
        List<Vec> route = route(node);
        Vec destination = node.point();
        double clearance = threats.stream().mapToDouble(destination::distance).min().orElse(0);
        double allyClearance = teammates == null ? 120 : teammates.stream()
                .mapToDouble(destination::distance).min().orElse(120);
        if (allyClearance < 36) return new ScoredRoute(route, Double.NEGATIVE_INFINITY);
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
        double routeClearance = route.stream().skip(2)
                .flatMapToDouble(point -> threats.stream().mapToDouble(point::distance)).min().orElse(0);
        double score = Math.min(650, clearance) * 3.6
                + Math.min(500, routeClearance) * 1.2
                + exits * 90 + openSpace * 75
                + Math.min(180, allyClearance) * 0.25
                + Math.min(576, origin.distance(destination)) * 0.35
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
    private record SearchNode(Cell cell, Vec point, SearchNode parent, int depth) {}
    private record Cell(int x, int y) {}
    private record ScoredRoute(List<Vec> route, double score) {}
}
