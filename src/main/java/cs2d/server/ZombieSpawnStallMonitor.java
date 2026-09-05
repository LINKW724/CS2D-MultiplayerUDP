package cs2d.server;

import java.awt.geom.Point2D;
import java.util.*;

/** Tracks only never-moved, never-damaged wave zombies during their first seconds of life. */
final class ZombieSpawnStallMonitor {
    static final long MIN_RECYCLE_DELAY_MS = 5_000;
    static final long MAX_RECYCLE_DELAY_MS = 10_000;
    static final double MOVEMENT_THRESHOLD = 30;
    private final Map<String, SpawnState> states = new HashMap<>();

    void register(String id, Point2D.Double position, int health, long now) {
        if (id == null || position == null) return;
        states.put(id, new SpawnState(new Point2D.Double(position.x, position.y), health,
                now + recycleDelayMillis(id)));
    }

    List<String> findRecyclable(Collection<Sample> samples, long now) {
        Map<String, Sample> current = new HashMap<>();
        if (samples != null) {
            for (Sample sample : samples) if (sample != null && sample.id() != null)
                current.put(sample.id(), sample);
        }
        states.keySet().removeIf(id -> !current.containsKey(id) || !current.get(id).alive());
        List<String> recyclable = new ArrayList<>();
        Iterator<Map.Entry<String, SpawnState>> iterator = states.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, SpawnState> entry = iterator.next();
            Sample sample = current.get(entry.getKey());
            SpawnState state = entry.getValue();
            if (sample.health() != state.initialHealth()
                    || sample.position() == null
                    || sample.position().distanceSq(state.spawnPosition())
                            >= MOVEMENT_THRESHOLD * MOVEMENT_THRESHOLD) {
                iterator.remove(); // Once it has participated, later stalls are ordinary pathing problems.
            } else if (now >= state.recycleAt()) {
                recyclable.add(entry.getKey());
                iterator.remove();
            }
        }
        return List.copyOf(recyclable);
    }

    void clear() { states.clear(); }

    static long recycleDelayMillis(String id) {
        int spread = (int) (MAX_RECYCLE_DELAY_MS - MIN_RECYCLE_DELAY_MS + 1);
        return MIN_RECYCLE_DELAY_MS + Math.floorMod(Objects.hashCode(id), spread);
    }

    record Sample(String id, Point2D.Double position, int health, boolean alive) {}
    private record SpawnState(Point2D.Double spawnPosition, int initialHealth, long recycleAt) {}
}
