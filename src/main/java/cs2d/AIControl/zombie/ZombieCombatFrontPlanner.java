package cs2d.AIControl.zombie;

import cs2d.AIControl.zombie.ZombieTacticalSnapshot.*;
import java.util.*;
import java.util.function.Predicate;

/** Converts fresh shared sightings into explicit, pressure-sized combat fronts. */
public final class ZombieCombatFrontPlanner {
    private static final long ACTIVE_REPORT_MS = ZombieIntelBoard.MEMORY_MS;
    private static final long ACTIVE_FIRE_MS = 1_400;
    private static final double GROUP_DISTANCE = 480;

    public List<ZombieCombatFront> detect(ZombieTacticalSnapshot snapshot, Predicate<Vec> walkable) {
        List<Contact> fresh = snapshot.contacts().stream()
                .filter(c -> c.observerId() != null && snapshot.now() - c.observedAt() >= 0
                        && snapshot.now() - c.observedAt() <= ACTIVE_REPORT_MS)
                .sorted(Comparator.comparing(Contact::id)).toList();
        if (fresh.isEmpty()) return List.of();
        List<List<Contact>> groups = connectedGroups(fresh);
        List<ZombieCombatFront> fronts = new ArrayList<>();
        for (List<Contact> group : groups) {
            Vec center = center(group.stream().map(Contact::position).toList());
            ZombieAttackLane lane = snapshot.attackLanes().stream()
                    .filter(l -> l.source().distance(center) <= 720)
                    .min(Comparator.comparingDouble(l -> l.source().distance(center))).orElse(null);
            double visiblePressure = group.stream()
                    .mapToDouble(c -> Math.max(0.75, c.health() / 500.0)).sum();
            double pressure = Math.max(visiblePressure, lane == null ? 0 : lane.pressure());
            Set<String> engaged = new HashSet<>();
            group.stream().map(Contact::observerId).filter(Objects::nonNull).forEach(engaged::add);
            snapshot.allies().stream().filter(Unit::independentAi)
                    .filter(a -> a.lastShotAt() > 0 && snapshot.now() - a.lastShotAt() >= 0
                            && snapshot.now() - a.lastShotAt() <= ACTIVE_FIRE_MS
                            && a.position().distance(center) <= 900)
                    .map(Unit::id).forEach(engaged::add);
            int required = Math.min(6, Math.max(2, (int) Math.ceil(pressure / 1.75)));
            List<Vec> engagedPositions = snapshot.allies().stream()
                    .filter(a -> engaged.contains(a.id())).map(Unit::position).toList();
            Vec anchorCenter = center(engagedPositions.isEmpty()
                    ? snapshot.allies().stream().map(Unit::position).toList() : engagedPositions);
            Vec desired = lane != null ? lane.defensePoint() : toward(center, anchorCenter, 340);
            Vec rally = nearestWalkable(desired, center, walkable);
            long observedAt = group.stream().mapToLong(Contact::observedAt).max().orElse(snapshot.now());
            String id = "front:" + group.stream().map(Contact::id).min(String::compareTo).orElse("unknown");
            fronts.add(new ZombieCombatFront(id, center, rally, center, pressure, required, engaged, observedAt));
        }
        return fronts.stream().sorted(Comparator
                .comparingInt(ZombieCombatFront::supportNeeded).reversed()
                .thenComparing(Comparator.comparingDouble(ZombieCombatFront::pressure).reversed())
                .thenComparing(ZombieCombatFront::id)).toList();
    }

    private static List<List<Contact>> connectedGroups(List<Contact> contacts) {
        List<List<Contact>> result = new ArrayList<>();
        Set<String> remaining = new LinkedHashSet<>();
        contacts.forEach(c -> remaining.add(c.id()));
        Map<String, Contact> byId = new HashMap<>();
        contacts.forEach(c -> byId.put(c.id(), c));
        while (!remaining.isEmpty()) {
            String first = remaining.iterator().next();
            remaining.remove(first);
            List<Contact> group = new ArrayList<>();
            ArrayDeque<Contact> queue = new ArrayDeque<>();
            queue.add(byId.get(first));
            while (!queue.isEmpty()) {
                Contact contact = queue.removeFirst();
                group.add(contact);
                List<String> connected = remaining.stream().filter(id ->
                        byId.get(id).position().distance(contact.position()) <= GROUP_DISTANCE).toList();
                connected.forEach(id -> { remaining.remove(id); queue.addLast(byId.get(id)); });
            }
            result.add(List.copyOf(group));
        }
        return result;
    }

    private static Vec center(List<Vec> points) {
        if (points.isEmpty()) return new Vec(0, 0);
        return new Vec(points.stream().mapToDouble(Vec::x).average().orElse(0),
                points.stream().mapToDouble(Vec::y).average().orElse(0));
    }

    private static Vec toward(Vec from, Vec to, double distance) {
        double dx = to.x() - from.x(), dy = to.y() - from.y();
        double length = Math.max(0.001, Math.hypot(dx, dy));
        return new Vec(from.x() + dx / length * Math.min(distance, length * 0.7),
                from.y() + dy / length * Math.min(distance, length * 0.7));
    }

    private static Vec nearestWalkable(Vec desired, Vec threatCenter, Predicate<Vec> walkable) {
        if (walkable.test(desired) && desired.distance(threatCenter) >= 150) return desired;
        Vec best = null;
        double bestDistance = Double.POSITIVE_INFINITY;
        for (double radius : new double[] { 180, 260, 340, 440 }) {
            for (int i = 0; i < 16; i++) {
                double angle = i * Math.PI / 8;
                Vec candidate = new Vec(threatCenter.x() + Math.cos(angle) * radius,
                        threatCenter.y() + Math.sin(angle) * radius);
                double score = candidate.distance(desired);
                if (walkable.test(candidate) && score < bestDistance) {
                    best = candidate;
                    bestDistance = score;
                }
            }
        }
        return best == null ? desired : best;
    }
}
