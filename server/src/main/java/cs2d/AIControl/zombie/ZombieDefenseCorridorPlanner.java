package cs2d.AIControl.zombie;

import cs2d.AIControl.zombie.ZombieTacticalSnapshot.Vec;
import java.util.*;
import java.util.function.Predicate;

/** Merges duplicate predicted lanes and expands every real choke into a three-line corridor. */
public final class ZombieDefenseCorridorPlanner {
    private static final double MERGE_DISTANCE = 210;

    public List<ZombieDefenseCorridor> plan(List<ZombieAttackLane> lanes,
            List<ZombieAreaHazard> hazards, long now, Predicate<Vec> walkable) {
        if (lanes == null || lanes.isEmpty()) return List.of();
        List<List<ZombieAttackLane>> groups = new ArrayList<>();
        for (ZombieAttackLane lane : lanes.stream()
                .sorted(Comparator.comparingDouble(ZombieAttackLane::pressure).reversed()).toList()) {
            List<ZombieAttackLane> group = groups.stream()
                    .filter(existing -> compatible(existing.get(0), lane)).findFirst().orElse(null);
            if (group == null) {
                group = new ArrayList<>();
                groups.add(group);
            }
            group.add(lane);
        }
        return groups.stream().map(group -> corridor(group, hazards, now, walkable))
                .filter(Objects::nonNull)
                .sorted(Comparator.comparingDouble(ZombieDefenseCorridor::pressure).reversed()
                        .thenComparing(ZombieDefenseCorridor::id)).toList();
    }

    private static boolean compatible(ZombieAttackLane a, ZombieAttackLane b) {
        if (a.defensePoint().distance(b.defensePoint()) > MERGE_DISTANCE) return false;
        double ax = a.target().x() - a.source().x();
        double ay = a.target().y() - a.source().y();
        double bx = b.target().x() - b.source().x();
        double by = b.target().y() - b.source().y();
        return (ax * bx + ay * by) / Math.max(1, Math.hypot(ax, ay) * Math.hypot(bx, by)) > 0.25;
    }

    private ZombieDefenseCorridor corridor(List<ZombieAttackLane> group,
            List<ZombieAreaHazard> hazards, long now, Predicate<Vec> walkable) {
        ZombieAttackLane routeLane = group.stream().max(Comparator.comparingDouble(ZombieAttackLane::pressure))
                .orElseThrow();
        List<Vec> route = routeLane.route();
        int chokeIndex = nearestIndex(route, routeLane.defensePoint());
        Vec forward = pointTowardSurvivors(route, chokeIndex, 128, walkable, hazards, now);
        Vec fallbackOne = pointTowardSurvivors(route, chokeIndex, 360, walkable, hazards, now);
        Vec fallbackTwo = pointTowardSurvivors(route, chokeIndex, 640, walkable, hazards, now);
        if (forward == null) return null;
        if (fallbackOne == null) fallbackOne = forward;
        if (fallbackTwo == null) fallbackTwo = fallbackOne;
        Vec direction = direction(route, chokeIndex);
        List<Vec> forwardStations = stations(forward, direction, walkable, hazards, now);
        List<Vec> fallbackOneStations = stations(fallbackOne, direction, walkable, hazards, now);
        List<Vec> fallbackTwoStations = stations(fallbackTwo, direction, walkable, hazards, now);
        double pressure = group.stream().mapToDouble(ZombieAttackLane::pressure).sum();
        int zombies = group.stream().mapToInt(ZombieAttackLane::zombieCount).sum();
        long eta = group.stream().mapToLong(ZombieAttackLane::etaMillis).min().orElse(0);
        long gx = Math.round(routeLane.defensePoint().x() / 200);
        long gy = Math.round(routeLane.defensePoint().y() / 200);
        String id = "choke:" + gx + ":" + gy;
        return new ZombieDefenseCorridor(id, routeLane.watchPoint(), pressure, zombies, eta,
                forwardStations, fallbackOneStations, fallbackTwoStations);
    }

    private static List<Vec> stations(Vec anchor, Vec direction, Predicate<Vec> walkable,
            List<ZombieAreaHazard> hazards, long now) {
        double length = Math.max(1, Math.hypot(direction.x(), direction.y()));
        double px = -direction.y() / length;
        double py = direction.x() / length;
        List<Vec> result = new ArrayList<>();
        for (double offset : new double[] { 0, -48, 48, -96, 96, -144, 144 }) {
            Vec candidate = new Vec(anchor.x() + px * offset, anchor.y() + py * offset);
            if (walkable.test(candidate) && clearLine(anchor, candidate, walkable)
                    && !hazardous(candidate, hazards, now)) result.add(candidate);
        }
        return result.isEmpty() ? List.of(anchor) : List.copyOf(result);
    }

    private static Vec pointTowardSurvivors(List<Vec> route, int start, double distance,
            Predicate<Vec> walkable, List<ZombieAreaHazard> hazards, long now) {
        double travelled = 0;
        Vec lastValid = null;
        for (int i = start; i < route.size(); i++) {
            if (i > start) travelled += route.get(i - 1).distance(route.get(i));
            Vec point = route.get(i);
            if (walkable.test(point) && !hazardous(point, hazards, now)) lastValid = point;
            if (travelled >= distance && lastValid != null) return lastValid;
        }
        return lastValid;
    }

    private static boolean clearLine(Vec from, Vec to, Predicate<Vec> walkable) {
        int samples = Math.max(1, (int) Math.ceil(from.distance(to) / 24.0));
        for (int i = 1; i < samples; i++) {
            double t = i / (double) samples;
            if (!walkable.test(new Vec(from.x() + (to.x() - from.x()) * t,
                    from.y() + (to.y() - from.y()) * t))) return false;
        }
        return true;
    }

    private static int nearestIndex(List<Vec> route, Vec point) {
        int best = 0;
        for (int i = 1; i < route.size(); i++)
            if (route.get(i).distance(point) < route.get(best).distance(point)) best = i;
        return best;
    }

    private static Vec direction(List<Vec> route, int index) {
        Vec from = route.get(Math.max(0, index - 1));
        Vec to = route.get(Math.min(route.size() - 1, index + 1));
        return new Vec(to.x() - from.x(), to.y() - from.y());
    }

    private static boolean hazardous(Vec point, List<ZombieAreaHazard> hazards, long now) {
        return hazards != null && hazards.stream().filter(Objects::nonNull)
                .anyMatch(hazard -> hazard.active(now) && hazard.contains(point, 40));
    }
}
