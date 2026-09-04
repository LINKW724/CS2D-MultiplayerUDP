package cs2d.server;

import cs2d.AIControl.A.PathfindingModule;
import cs2d.AIControl.A.PathfindingModule.Node;
import cs2d.AIControl.A.PathfindingModule.Pathfinder;
import cs2d.AIControl.zombie.*;
import cs2d.AIControl.zombie.ZombieTacticalSnapshot.*;
import cs2d.playerAndAi.Player;
import cs2d.playerAndAi.Weapon;
import java.util.*;

/** 4 Hz survivor command loop with 1.3 Hz aggregated route forecasting. */
final class ZombieTacticalRuntime {
    private final GameState gameState;
    private final ZombieIntelBoard intel = new ZombieIntelBoard();
    private final ZombieTacticalCoordinator coordinator = new ZombieTacticalCoordinator();
    private final ZombieTacticalTaskBoard tasks = new ZombieTacticalTaskBoard();
    private final ZombieFlowForecaster flowForecaster = new ZombieFlowForecaster();
    private final Pathfinder flowPathfinder;
    private volatile Map<String, ZombieTacticalOrder> orders = Map.of();
    private List<ZombieAttackLane> attackLanes = List.of();
    private long lastPlanAt;
    private long lastForecastAt;
    private long clearedAt;

    ZombieTacticalRuntime(GameState gameState) {
        this.gameState = gameState;
        this.flowPathfinder = new Pathfinder(gameState, (int) Player.SIZE);
    }

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
        List<Unit> aliveZombies = units.values().stream()
                .filter(p -> p.team() == Player.Team.ZOMBIE && p.health() > 0)
                .sorted(Comparator.comparing(Unit::id)).toList();
        int remainingZombies = gameState.getRemainingZombieCount();
        boolean cleanupPhase = remainingZombies > 0
                && remainingZombies <= ZombieTacticalCoordinator.CLEANUP_REMAINDER_THRESHOLD
                && remainingZombies == aliveZombies.size();
        List<Contact> cleanupLeads = cleanupPhase ? aliveZombies.stream()
                .map(zombie -> new Contact(zombie.id(), zombie.position(), zombie.health(), now)).toList()
                : List.of();
        if (!cleanupPhase && now - lastForecastAt >= 750) {
            lastForecastAt = now;
            attackLanes = flowForecaster.forecast(now, aliveZombies, allies, hazards,
                    this::previewRoute, point -> point.x() >= Player.SIZE && point.y() >= Player.SIZE
                            && point.x() < gameState.getMapWidth() - Player.SIZE
                            && point.y() < gameState.getMapHeight() - Player.SIZE
                            && flowPathfinder.isWalkable(point.point()));
        } else if (cleanupPhase || aliveZombies.isEmpty()) {
            attackLanes = List.of();
        }
        PathfindingModule path = independentAis.stream().filter(p -> p.team == Player.Team.CT)
                .map(Player::getPathfindingModule).filter(Objects::nonNull).findFirst().orElse(null);
        ZombieTacticalSnapshot snapshot = new ZombieTacticalSnapshot(now, allies,
                intel.contacts(Player.Team.CT, units, now), hazards, remainingZombies, cleanupLeads, attackLanes);
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
        attackLanes = List.of();
        lastPlanAt = 0;
        lastForecastAt = 0;
        clearedAt = System.currentTimeMillis();
    }

    private List<Vec> previewRoute(Vec source, Vec target) {
        List<Node> nodes = flowPathfinder.findPath(source.point(), target.point());
        if (nodes == null || nodes.isEmpty()) return List.of();
        List<Vec> route = new ArrayList<>(nodes.size() + 1);
        route.add(source);
        nodes.stream().map(flowPathfinder::nodeToWorld).map(Vec::of).forEach(route::add);
        return List.copyOf(route);
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
                p.isAI && !p.isControlledByPlayer(), power, p.lastShotTime);
    }
}
