package cs2d.AIControl.zombie;

import cs2d.AIControl.zombie.ZombieDefenseLinePlanner.DefensePoint;
import cs2d.AIControl.zombie.ZombieTacticalSnapshot.Unit;
import cs2d.AIControl.zombie.ZombieTacticalSnapshot.Vec;
import java.util.*;
import java.util.function.Predicate;

/** Aggregates zombie groups and previews their likely navigation routes toward survivor sectors. */
public final class ZombieFlowForecaster {
    private static final double SOURCE_CELL = 360;
    private static final double TARGET_CELL = 480;
    private static final double ESTIMATED_ZOMBIE_SPEED_PER_SECOND = 180;
    private static final int MAX_LANES = 8;
    private final ZombieDefenseLinePlanner defenseLines = new ZombieDefenseLinePlanner();

    public List<ZombieAttackLane> forecast(long now, List<Unit> zombies, List<Unit> survivors,
            List<ZombieAreaHazard> hazards, RouteProvider routes, Predicate<Vec> walkable) {
        if (zombies == null || zombies.isEmpty() || survivors == null || survivors.isEmpty()) return List.of();
        List<Group> sources = group(zombies, SOURCE_CELL);
        List<Group> targets = group(survivors, TARGET_CELL);
        List<ZombieAttackLane> lanes = new ArrayList<>();
        sources.stream().sorted(Comparator.comparingDouble(Group::pressure).reversed())
                .limit(MAX_LANES).forEach(source -> {
                    Group target = targets.stream().min(Comparator.comparingDouble(t ->
                            t.center().distance(source.center()))).orElse(null);
                    if (target == null) return;
                    List<Vec> route = routes.find(source.center(), target.center());
                    if (route == null || route.size() < 2) return;
                    DefensePoint defense = defenseLines.choose(route, hazards, now, walkable);
                    if (defense == null) return;
                    double length = 0;
                    for (int i = 1; i < route.size(); i++) length += route.get(i - 1).distance(route.get(i));
                    long eta = Math.round(length / ESTIMATED_ZOMBIE_SPEED_PER_SECOND * 1_000);
                    String id = source.key() + "->" + target.key();
                    lanes.add(new ZombieAttackLane(id, source.center(), target.center(), defense.position(),
                            defense.watchPoint(), source.count(), source.pressure(), eta, route));
                });
        return lanes.stream().sorted(Comparator.comparingDouble(ZombieAttackLane::pressure).reversed()
                .thenComparingLong(ZombieAttackLane::etaMillis).thenComparing(ZombieAttackLane::id)).toList();
    }

    private static List<Group> group(List<Unit> units, double cellSize) {
        Map<String, List<Unit>> cells = new TreeMap<>();
        for (Unit unit : units) {
            long x = (long) Math.floor(unit.position().x() / cellSize);
            long y = (long) Math.floor(unit.position().y() / cellSize);
            cells.computeIfAbsent(x + ":" + y, ignored -> new ArrayList<>()).add(unit);
        }
        return cells.entrySet().stream().map(entry -> {
            List<Unit> members = entry.getValue();
            Vec center = new Vec(members.stream().mapToDouble(u -> u.position().x()).average().orElse(0),
                    members.stream().mapToDouble(u -> u.position().y()).average().orElse(0));
            double pressure = members.stream().mapToDouble(u -> Math.max(1.0, u.health() / 500.0)).sum();
            return new Group(entry.getKey(), center, members.size(), pressure);
        }).toList();
    }

    @FunctionalInterface
    public interface RouteProvider { List<Vec> find(Vec source, Vec target); }
    private record Group(String key, Vec center, int count, double pressure) {}
}
