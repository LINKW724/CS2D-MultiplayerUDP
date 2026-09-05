package cs2d.AIControl.zombie;

import cs2d.AIControl.zombie.ZombieTacticalSnapshot.Vec;
import java.util.List;

/** A merged choke with forward, first-fallback and second-fallback firing positions. */
public record ZombieDefenseCorridor(String id, Vec watchPoint, double pressure, int zombieCount,
        long etaMillis, List<Vec> forwardStations, List<Vec> fallbackOneStations,
        List<Vec> fallbackTwoStations) {
    public ZombieDefenseCorridor {
        forwardStations = List.copyOf(forwardStations);
        fallbackOneStations = List.copyOf(fallbackOneStations);
        fallbackTwoStations = List.copyOf(fallbackTwoStations);
    }

    public List<Vec> stations(Line line) {
        return switch (line) {
            case FORWARD -> forwardStations;
            case FALLBACK_ONE -> fallbackOneStations;
            case FALLBACK_TWO -> fallbackTwoStations;
        };
    }

    public Vec anchor(Line line) {
        List<Vec> stations = stations(line);
        return stations.isEmpty() ? watchPoint : stations.get(0);
    }

    public enum Line { FORWARD, FALLBACK_ONE, FALLBACK_TWO }
}
