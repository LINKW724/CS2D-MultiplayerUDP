package cs2d.AIControl.zombie;

import cs2d.AIControl.zombie.ZombieTacticalSnapshot.*;
import java.util.List;

/** Local advantage, not global enemy count, decides whether a small clearing sortie is safe. */
public final class ZombieThreatEvaluator {
    public Assessment assess(Vec position, ZombieTacticalSnapshot snapshot) {
        List<Contact> threats = snapshot.contacts().stream()
                .filter(c -> c.position().distance(position) <= 900).toList();
        List<Unit> nearby = snapshot.allies().stream()
                .filter(u -> u.position().distance(position) <= 900).toList();
        double friendlyPower = nearby.stream().mapToDouble(Unit::strength).sum();
        double threatPower = threats.stream().mapToDouble(c -> Math.max(0.75, c.health() / 500.0)).sum();
        double nearest = threats.stream().mapToDouble(c -> c.position().distance(position))
                .min().orElse(Double.POSITIVE_INFINITY);
        boolean advantage = !threats.isEmpty() && threats.size() <= 2
                && nearby.stream().filter(Unit::ready).count() >= threats.size()
                && friendlyPower >= threatPower * 1.15 && nearest >= 170;
        return new Assessment(threats, advantage, nearest < 170);
    }
    public record Assessment(List<Contact> threats, boolean canAdvance, boolean imminent) {}
}
