package cs2d.server;

import cs2d.AIControl.A.PathfindingModule;
import cs2d.AIControl.zombie.*;
import cs2d.AIControl.zombie.ZombieTacticalSnapshot.*;
import cs2d.playerAndAi.Player;
import cs2d.playerAndAi.Weapon;
import java.util.*;

/** 4 Hz survivor command loop, fed by actual per-agent perception rather than global enemy locations. */
final class ZombieTacticalRuntime {
    private final GameState gameState;
    private final ZombieIntelBoard intel = new ZombieIntelBoard();
    private final ZombieTacticalCoordinator coordinator = new ZombieTacticalCoordinator();
    private final ZombieTacticalTaskBoard tasks = new ZombieTacticalTaskBoard();
    private volatile Map<String, ZombieTacticalOrder> orders = Map.of();
    private long lastPlanAt;
    private long clearedAt;

    ZombieTacticalRuntime(GameState gameState) { this.gameState = gameState; }

    synchronized void report(Player observer, long now) {
        if (now < clearedAt || gameState.shouldFreezeAi() || !observer.isAlive()
                || observer.getPerceptionModule() == null) return;
        Unit source = unit(observer);
        observer.getPerceptionModule().getAllPerceivedEnemies().values().stream()
                .filter(info -> info != null && info.isCurrentlyVisible()
                        && info.type() == cs2d.AIControl.B.PerceptionType.SIGHT)
                .forEach(info -> {
                    Player target = gameState.getPlayerById(info.enemyId());
                    if (ZombieHostilityPolicy.canTarget(observer, target)
                            && observer.position.distanceSq(target.position) <= 1_500.0 * 1_500.0)
                        intel.report(source, unit(target), now);
                });
    }

    synchronized void update(long now, List<Player> independentAis) {
        Set<String> alive = independentAis.stream().filter(p -> p.isAlive() && p.team == Player.Team.CT)
                .map(p -> p.id).collect(java.util.stream.Collectors.toSet());
        tasks.retain(alive);
        orders = orders.entrySet().stream().filter(e -> alive.contains(e.getKey()))
                .collect(java.util.stream.Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));
        if (now - lastPlanAt < 250) return;
        lastPlanAt = now;
        Map<String, Unit> units = new HashMap<>();
        for (Player p : gameState.getAllCharacters()) {
            if (p != null && p.position != null) units.put(p.id, unit(p));
        }
        List<Unit> allies = units.values().stream().filter(p -> p.team() == Player.Team.CT && p.health() > 0)
                .sorted(Comparator.comparing(Unit::id)).toList();
        List<ZombieAreaHazard> hazards = gameState.getFirePatches().stream()
                .filter(fire -> fire != null && !fire.isExpired())
                .map(fire -> new ZombieAreaHazard(Vec.of(fire.position), GameState.FirePatch.RADIUS,
                        fire.creationTime + GameState.FirePatch.DURATION_MS,
                        ZombieAreaHazard.Type.ACTIVE_FIRE))
                .toList();
        PathfindingModule path = independentAis.stream().filter(p -> p.team == Player.Team.CT)
                .map(Player::getPathfindingModule).filter(Objects::nonNull).findFirst().orElse(null);
        ZombieTacticalSnapshot snapshot = new ZombieTacticalSnapshot(now, allies,
                intel.contacts(Player.Team.CT, units, now), hazards);
        orders = coordinator.plan(snapshot, tasks, point -> path != null
                && point.x() >= Player.SIZE && point.y() >= Player.SIZE
                && point.x() < gameState.getMapWidth() - Player.SIZE
                && point.y() < gameState.getMapHeight() - Player.SIZE
                && path.isWalkable(point.point()));
    }

    ZombieTacticalOrder orderFor(Player player, long now) {
        if (player == null || !player.isAlive() || player.team != Player.Team.CT) return null;
        ZombieTacticalOrder order = orders.get(player.id);
        return order != null && order.active(now) ? order : null;
    }

    synchronized void clear() {
        intel.clear();
        tasks.clear();
        orders = Map.of();
        lastPlanAt = 0;
        clearedAt = System.currentTimeMillis();
    }

    private static Unit unit(Player p) {
        Weapon weapon = p.getCurrentWeapon();
        double power = weapon == null ? 0 : switch (weapon.getWeaponType()) {
            case LMG -> 1.8;
            case RIFLE -> 1.5;
            case SMG -> 1.3;
            case SHOTGUN -> 1.1;
            default -> 0.8;
        };
        return new Unit(p.id, p.team, Vec.of(p.position), p.health, p.currentAmmo, p.isReloading,
                p.isAI && !p.isControlledByPlayer(), power);
    }
}
