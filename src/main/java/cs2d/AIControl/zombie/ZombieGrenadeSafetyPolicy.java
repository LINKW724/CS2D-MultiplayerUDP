package cs2d.AIControl.zombie;

import cs2d.AIControl.zombie.ZombieTacticalSnapshot.Vec;
import cs2d.server.Item;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/** Pure tactical policy. Ballistics remain the responsibility of GrenadeModule. */
public final class ZombieGrenadeSafetyPolicy {
    private static final double HE_FRIENDLY_CLEARANCE = 380;
    private static final double FIRE_FRIENDLY_CLEARANCE = 170;

    public Optional<Vec> chooseTarget(Item item, Vec thrower, List<Vec> enemies,
            List<Vec> allies, List<ZombieAreaHazard> hazards) {
        if (!supported(item) || enemies == null || enemies.size() < 3) return Optional.empty();
        List<Vec> candidates = new ArrayList<>(enemies);
        candidates.add(new Vec(enemies.stream().mapToDouble(Vec::x).average().orElse(0),
                enemies.stream().mapToDouble(Vec::y).average().orElse(0)));
        return candidates.stream()
                .filter(point -> isSafeImpact(item, point, allies, hazards))
                .filter(point -> affectedEnemies(item, point, enemies) >= 3)
                .min(Comparator.<Vec>comparingInt(point -> -affectedEnemies(item, point, enemies))
                        .thenComparingDouble(point -> thrower == null ? 0 : thrower.distance(point)));
    }

    public boolean isSafeImpact(Item item, Vec impact, List<Vec> allies,
            List<ZombieAreaHazard> hazards) {
        if (!supported(item) || impact == null) return false;
        double clearance = friendlyClearance(item);
        if (allies != null && allies.stream().anyMatch(ally -> ally != null && ally.distance(impact) < clearance))
            return false;
        // Do not waste persistent fire on an area that is already burning.
        return !isFire(item) || hazards == null || hazards.stream()
                .filter(h -> h != null && h.type() == ZombieAreaHazard.Type.ACTIVE_FIRE)
                .noneMatch(h -> h.center().distance(impact) < h.radius() + 80);
    }

    int affectedEnemies(Item item, Vec impact, List<Vec> enemies) {
        double radius = isFire(item) ? 145 : 350;
        return (int) enemies.stream().filter(enemy -> enemy != null && enemy.distance(impact) < radius).count();
    }

    private static double friendlyClearance(Item item) {
        return isFire(item) ? FIRE_FRIENDLY_CLEARANCE : HE_FRIENDLY_CLEARANCE;
    }

    private static boolean isFire(Item item) {
        return item == Item.MOLOTOV || item == Item.INCENDIARY;
    }

    private static boolean supported(Item item) {
        return item == Item.HE_GRENADE || isFire(item);
    }
}
