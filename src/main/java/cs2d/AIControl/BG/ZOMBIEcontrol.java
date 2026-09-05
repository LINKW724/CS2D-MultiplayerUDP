package cs2d.AIControl.BG;

import cs2d.AIControl.A.AttackExecutionPolicy;
import cs2d.AIControl.A.AttackModule;
import cs2d.AIControl.A.GrenadeModule;
import cs2d.AIControl.A.PathfindingModule;
import cs2d.AIControl.B.PerceptionModule;
import cs2d.AIControl.zombie.*;
import cs2d.AIControl.zombie.ZombieTacticalSnapshot.Vec;
import cs2d.playerAndAi.Player;
import cs2d.playerAndAi.Weapon;
import cs2d.server.AIDifficulty;
import cs2d.server.AIService.AIInput;
import cs2d.server.AIService.AIWorldView;
import cs2d.server.GameState;
import cs2d.server.Item;

import java.awt.geom.Point2D;
import java.util.*;
import java.util.function.Consumer;

/**
 * Zombie-mode executor. Team intent is owned by ZombieTacticalRuntime; this
 * controller owns immediate sight, safe local movement, weapons and melee.
 */
public class ZOMBIEcontrol {
    private final Player owner;
    private final GameState gameState;
    private final PerceptionModule perceptionModule;
    private final AttackModule attackModule;
    private final PathfindingModule pathfindingModule;
    private final GrenadeModule grenadeModule;
    private final Random rand = new Random();
    private final ZombiePositionPlanner positions = new ZombiePositionPlanner();
    private final ZombieGrenadeSafetyPolicy grenadeSafety = new ZombieGrenadeSafetyPolicy();
    private final ZombiePursuitPlanner pursuit = new ZombiePursuitPlanner();
    private final ZombieCrowdFirePlanner crowdFire = new ZombieCrowdFirePlanner();
    private final ZombieSurvivorMobilityPolicy mobility = new ZombieSurvivorMobilityPolicy();
    private final ZombieEscapeRoutePlanner escapeRoutes = new ZombieEscapeRoutePlanner();
    private Player primaryTarget;
    private ZombieCrowdFirePlanner.Decision crowdFireDecision = ZombieCrowdFirePlanner.Decision.NONE;
    private Point2D.Double lastKnownPosition;
    private long nextPositionAt;
    private long nextGrenadeAt;
    private long lastMeleeTime;
    private Vec movementGoal;
    private boolean grenadeBusy;
    private long grenadeDeadline;
    private Item grenadeItem;
    private boolean wasEmergency;
    private boolean kiting;
    private ZombieEscapeRoutePlanner.EscapePlan escapePlan = ZombieEscapeRoutePlanner.EscapePlan.EMPTY;
    private int escapeRouteIndex;
    private int escapeWaypointIndex;
    private long escapeCommittedUntil;
    private long nextEscapePreparationAt;
    private Vec lastEscapeProgressPosition;
    private long lastEscapeProgressAt;
    private Vec lastZombieProgressPosition;
    private long lastZombieProgressAt;
    private Vec zombieDetourTarget;
    private long zombieDetourUntil;

    public ZOMBIEcontrol(Player owner, GameState gameState, AIDifficulty difficulty,
            PerceptionModule perceptionModule, AttackModule attackModule,
            PathfindingModule pathfindingModule, Consumer<String> logger) {
        this.owner = owner;
        this.gameState = gameState;
        this.perceptionModule = perceptionModule;
        this.attackModule = attackModule;
        this.pathfindingModule = pathfindingModule;
        this.grenadeModule = owner.team == Player.Team.CT
                ? new GrenadeModule(owner, gameState, pathfindingModule.pathfinder, difficulty, logger, rand)
                : null;
    }

    /** Compatibility entry point: no commander order means hold, never choose an arbitrary human. */
    public AIInput update(AIWorldView world, long now) { return update(world, now, null); }

    public AIInput update(AIWorldView world, long now, ZombieTacticalOrder order) {
        if (!owner.isAlive()) { reset(); return neutral(); }
        perceptionModule.update(world, now);
        selectPrimaryTarget(now);
        if (owner.team == Player.Team.CT) return executeSurvivor(world, now, order);
        if (owner.team == Player.Team.ZOMBIE) return executeZombie(world, now);
        return neutral();
    }

    private void selectPrimaryTarget(long now) {
        List<Player> visible = perceptionModule.getAllPerceivedEnemies().values().stream()
                .filter(info -> info != null && info.isCurrentlyVisible())
                .map(info -> gameState.getPlayerById(info.enemyId()))
                .filter(target -> ZombieHostilityPolicy.canTarget(owner, target))
                .filter(target -> pathfindingModule.hasLineOfSight(target.position))
                .toList();
        Weapon weapon = owner.getCurrentWeapon();
        if (owner.team == Player.Team.CT && weapon != null
                && weapon.getWeaponType() == Weapon.WeaponType.LMG) {
            crowdFireDecision = crowdFire.select(Vec.of(owner.position), visible.stream()
                    .map(target -> new ZombieCrowdFirePlanner.Target(target.id,
                            Vec.of(target.position), target.health)).toList(), now);
            primaryTarget = visible.stream().filter(target ->
                    target.id.equals(crowdFireDecision.targetId())).findFirst().orElse(null);
        } else {
            crowdFire.reset();
            crowdFireDecision = ZombieCrowdFirePlanner.Decision.NONE;
            primaryTarget = visible.stream()
                    .min(Comparator.comparingDouble(target -> owner.position.distanceSq(target.position)))
                    .orElse(null);
        }
        lastKnownPosition = primaryTarget == null ? null
                : new Point2D.Double(primaryTarget.position.x, primaryTarget.position.y);
    }

    private List<Vec> visibleThreats() {
        return perceptionModule.getAllPerceivedEnemies().values().stream()
                .filter(info -> info != null && info.isCurrentlyVisible())
                .map(info -> gameState.getPlayerById(info.enemyId()))
                .filter(target -> ZombieHostilityPolicy.canTarget(owner, target))
                .filter(target -> pathfindingModule.hasLineOfSight(target.position))
                .map(target -> Vec.of(target.position)).toList();
    }

    private AIInput executeSurvivor(AIWorldView world, long now, ZombieTacticalOrder order) {
        List<Vec> threats = new ArrayList<>(visibleThreats());
        List<ZombieAreaHazard> hazards = activeHazards(now);
        Vec origin = Vec.of(owner.position);
        boolean inHazard = hazards.stream().anyMatch(hazard -> hazard.contains(origin, Player.SIZE));
        long lastZombieDamageAt = owner.lastDamageSourceTeam == Player.Team.ZOMBIE
                ? owner.lastDamageSourcePositionTime : 0;
        if (owner.lastDamageSourcePosition != null && lastZombieDamageAt > 0
                && now >= owner.lastDamageSourcePositionTime
                && now - owner.lastDamageSourcePositionTime <= ZombieSurvivorMobilityPolicy.DAMAGE_ESCAPE_MS) {
            Vec damageSource = Vec.of(owner.lastDamageSourcePosition);
            if (threats.stream().noneMatch(t -> t.distance(damageSource) < 8)) threats.add(damageSource);
        }
        double nearest = threats.stream().mapToDouble(origin::distance).min().orElse(Double.POSITIVE_INFINITY);
        ZombieSurvivorMobilityPolicy.Decision mobilityDecision = mobility.decide(
                owner.primaryWeapon, owner.secondaryWeapon, nearest, inHazard,
                lastZombieDamageAt, now, kiting);
        kiting = mobilityDecision.kiting();
        boolean emergency = mobilityDecision.emergency();
        if (emergency && !wasEmergency) {
            nextPositionAt = 0;
            escapeCommittedUntil = now + 1_200;
            lastEscapeProgressPosition = origin;
            lastEscapeProgressAt = now;
        }
        if (!emergency && wasEmergency) clearEscapeRoute();
        wasEmergency = emergency;
        if (!emergency && !threats.isEmpty() && nearest < 520 && now >= nextEscapePreparationAt) {
            planEscape(origin, threats, liveAllyPositions(), hazards, now);
            nextEscapePreparationAt = now + 1_500;
        }

        AIInput grenade = grenadeAction(world, now, nearest, threats, hazards);
        if (grenade != null) return grenade;
        applyCombatWeapon(mobilityDecision.desiredWeaponSlot());
        if (owner.currentAmmo <= 0 && owner.reserveAmmo > 0 && !owner.isReloading)
            owner.startReload();

        List<Vec> teammates = liveAllyPositions();
        Vec desired = order != null && order.active(now) && owner.id.equals(order.agentId())
                ? order.destination() : origin;
        if (desired == null) desired = origin;
        if (emergency) {
            movementGoal = committedEscapeGoal(origin, threats, teammates, hazards, now);
        } else if (now >= nextPositionAt || movementGoal == null) {
            Vec local = desired;
            if (positions.requiresLocalDetour(origin, desired,
                    threats, hazards, now, this::walkable)
                    || teammates.stream().anyMatch(p -> p.distance(origin) < 50)) {
                local = positions.choose(origin, desired, threats, teammates, hazards, now, this::walkable,
                        null);
            }
            movementGoal = local;
            nextPositionAt = now + 500;
        }
        moveTo(movementGoal);
        AIInput movement = pathfindingModule.update(world);
        AIInput attack = attackModule.update(primaryTarget, lastKnownPosition, now,
                AttackExecutionPolicy.ZOMBIE_SURVIVOR, crowdFireDecision.engagementId());
        boolean shoot = attack.shooting()
                && ZombieHostilityPolicy.bulletFireAllowed(owner, primaryTarget);
        double angle = primaryTarget != null ? attack.angle()
                : movement.keys().isEmpty()
                        ? order != null && order.active(now)
                                ? order.watchPoint() != null
                                        ? Math.atan2(order.watchPoint().y() - owner.position.y,
                                                order.watchPoint().x() - owner.position.x)
                                        : order.watchAngle()
                                : owner.angle
                        : movement.angle();
        List<String> keys = movement.keys();
        Weapon weapon = owner.getCurrentWeapon();
        if (!emergency && primaryTarget != null && weapon != null
                && weapon.getWeaponType() == Weapon.WeaponType.SNIPER)
            keys = attack.keys();
        return new AIInput(keys, angle, shoot, false, false);
    }

    private Vec committedEscapeGoal(Vec origin, List<Vec> threats, List<Vec> teammates,
            List<ZombieAreaHazard> hazards, long now) {
        if (lastEscapeProgressPosition == null || origin.distance(lastEscapeProgressPosition) >= 18) {
            lastEscapeProgressPosition = origin;
            lastEscapeProgressAt = now;
        }
        boolean stalled = lastEscapeProgressAt > 0 && now - lastEscapeProgressAt >= 700;
        ZombieEscapeRoutePlanner.EscapeRoute route = currentEscapeRoute();
        boolean routeUnsafe = route != null && now >= escapeCommittedUntil
                && !escapeRoutes.routeStillSafe(route, escapeWaypointIndex, threats, hazards, now);
        if (route == null || stalled || routeUnsafe) {
            if (route != null && escapeRouteIndex + 1 < escapePlan.routes().size()) {
                escapeRouteIndex++;
                escapeWaypointIndex = initialWaypoint(currentEscapeRoute());
                lastEscapeProgressPosition = origin;
                lastEscapeProgressAt = now;
            } else {
                planEscape(origin, threats, teammates, hazards, now);
            }
            route = currentEscapeRoute();
        }
        if (route == null) {
            return positions.choose(origin, origin, threats, teammates, hazards, now,
                    this::walkable, null);
        }
        while (escapeWaypointIndex < route.waypoints().size() - 1
                && origin.distance(route.waypoints().get(escapeWaypointIndex)) <= 46) {
            escapeWaypointIndex = Math.min(route.waypoints().size() - 1, escapeWaypointIndex + 2);
        }
        if (escapeWaypointIndex >= route.waypoints().size() - 1
                && origin.distance(route.destination()) <= 52 && now >= escapeCommittedUntil) {
            planEscape(origin, threats, teammates, hazards, now);
            route = currentEscapeRoute();
        }
        return route == null ? origin : route.waypoints().get(escapeWaypointIndex);
    }

    private void planEscape(Vec origin, List<Vec> threats, List<Vec> teammates,
            List<ZombieAreaHazard> hazards, long now) {
        escapePlan = escapeRoutes.plan(origin, threats, teammates, hazards, now, this::walkable,
                (from, to) -> positions.safeSegment(from, to, List.of(), hazards, now, this::walkable));
        escapeRouteIndex = 0;
        escapeWaypointIndex = initialWaypoint(currentEscapeRoute());
        escapeCommittedUntil = now + 1_200;
        lastEscapeProgressPosition = origin;
        lastEscapeProgressAt = now;
    }

    private ZombieEscapeRoutePlanner.EscapeRoute currentEscapeRoute() {
        return escapeRouteIndex >= 0 && escapeRouteIndex < escapePlan.routes().size()
                ? escapePlan.routes().get(escapeRouteIndex) : null;
    }

    private static int initialWaypoint(ZombieEscapeRoutePlanner.EscapeRoute route) {
        return route == null ? 0 : Math.min(route.waypoints().size() - 1, 3);
    }

    private void clearEscapeRoute() {
        escapePlan = ZombieEscapeRoutePlanner.EscapePlan.EMPTY;
        escapeRouteIndex = 0;
        escapeWaypointIndex = 0;
        escapeCommittedUntil = 0;
        lastEscapeProgressPosition = null;
        lastEscapeProgressAt = 0;
    }

    private boolean walkable(Vec point) {
        return point.x() >= Player.SIZE && point.y() >= Player.SIZE
                && point.x() < gameState.getMapWidth() - Player.SIZE
                && point.y() < gameState.getMapHeight() - Player.SIZE
                && pathfindingModule.isWalkable(point.point());
    }

    private void moveTo(Vec point) {
        moveTo(point, 30);
    }

    private void moveTo(Vec point, double arrivalRadius) {
        if (point == null || point.distance(Vec.of(owner.position)) <= arrivalRadius) {
            if (pathfindingModule.isActive()) pathfindingModule.setTarget(null);
            return;
        }
        if (pathfindingModule.isFollowingPresetPath()) pathfindingModule.cancelPresetPath();
        Point2D.Double previous = pathfindingModule.getTargetPosition();
        if (!pathfindingModule.isActive() || previous == null || previous.distanceSq(point.point()) > 25 * 25)
            pathfindingModule.setTarget(point.point());
    }

    private List<ZombieAreaHazard> activeHazards(long now) {
        return gameState.getFirePatches().stream()
                .filter(fire -> fire != null && !fire.isExpired())
                .map(fire -> new ZombieAreaHazard(Vec.of(fire.position), GameState.FirePatch.RADIUS,
                        fire.creationTime + GameState.FirePatch.DURATION_MS,
                        ZombieAreaHazard.Type.ACTIVE_FIRE))
                .filter(hazard -> hazard.active(now)).toList();
    }

    /** Grenades are optional support, never a reason to stop with a zombie at melee distance. */
    private AIInput grenadeAction(AIWorldView world, long now, double nearest, List<Vec> enemies,
            List<ZombieAreaHazard> hazards) {
        if (grenadeModule == null) return null;
        boolean standingInHazard = hazards.stream()
                .anyMatch(hazard -> hazard.contains(Vec.of(owner.position), Player.SIZE));
        if (nearest < 320 || standingInHazard || (now > grenadeDeadline && grenadeBusy) || owner.isReloading
                || !ZombieHostilityPolicy.canTarget(owner, primaryTarget)) {
            if (grenadeBusy) {
                grenadeModule.cancelPendingWork();
                restoreGun();
                grenadeBusy = false;
                grenadeItem = null;
            }
            return null;
        }
        if (!grenadeBusy && now >= nextGrenadeAt && enemies.size() >= 3) {
            Item item = owner.equipment.containsKey(Item.MOLOTOV) ? Item.MOLOTOV
                    : owner.equipment.containsKey(Item.INCENDIARY) ? Item.INCENDIARY
                    : owner.equipment.containsKey(Item.HE_GRENADE) ? Item.HE_GRENADE : null;
            nextGrenadeAt = now + 3_000;
            if (item != null) {
                List<Vec> allies = liveAllies();
                grenadeSafety.chooseTarget(item, Vec.of(owner.position), enemies, allies, hazards)
                        .ifPresent(target -> {
                            boolean accepted = grenadeModule.requestThrow(item, target.point(), plan ->
                                    plan.predictedLandingPosition() != null
                                            && grenadeSafety.isSafeImpact(item,
                                                    Vec.of(plan.predictedLandingPosition()), allies, hazards));
                            if (accepted) {
                                grenadeBusy = true;
                                grenadeItem = item;
                                grenadeDeadline = now + 5_000;
                            }
                        });
            }
        }
        if (!grenadeBusy) return null;
        Optional<GrenadeModule.GrenadeCommand> command = grenadeModule.update(primaryTarget,
                lastKnownPosition, null, owner.vx * owner.vx + owner.vy * owner.vy < 0.1, true);
        if (command.isEmpty()) return null; // Async calculation may still be pending.
        GrenadeModule.GrenadeCommand cmd = command.get();
        switch (cmd.action()) {
            case SWITCH_SLOT:
                owner.switchToSlot(cmd.slot());
                pathfindingModule.setTarget(null);
                return neutral();
            case HOLD_AND_AIM:
                pathfindingModule.setTarget(null);
                return new AIInput(List.of(), cmd.aimAngle(), false, false, false);
            case MOVE_TO_SPOT:
                if (cmd.pathTarget() == null || !positions.safeSegment(Vec.of(owner.position),
                        Vec.of(cmd.pathTarget()), visibleThreats(), activeHazards(now), now, this::walkable)) {
                    grenadeModule.cancelPendingWork();
                    grenadeBusy = false;
                    grenadeItem = null;
                    restoreGun();
                    return null;
                }
                moveTo(Vec.of(cmd.pathTarget()));
                return pathfindingModule.update(world);
            case THROW_NOW:
                boolean safe = grenadeItem != null && cmd.predictedLandingPosition() != null
                        && grenadeSafety.isSafeImpact(grenadeItem, Vec.of(cmd.predictedLandingPosition()),
                                liveAllies(), activeHazards(now));
                if (safe && ZombieHostilityPolicy.canTarget(owner, primaryTarget)) {
                    owner.angle = cmd.aimAngle();
                    gameState.throwGrenade(owner);
                } else {
                    grenadeModule.cancelPendingWork();
                }
                grenadeBusy = false;
                grenadeItem = null;
                restoreGun();
                return new AIInput(List.of(), cmd.aimAngle(), false, false, false);
            default:
                return null; // Async planning does not pause ordinary defense.
        }
    }

    private List<Vec> liveAllies() {
        return gameState.getAllCharacters().stream()
                .filter(p -> p != null && p.isAlive() && p.team == owner.team && p.position != null)
                .map(p -> Vec.of(p.position)).toList();
    }

    private List<Vec> liveAllyPositions() {
        return gameState.getAllCharacters().stream()
                .filter(p -> p != null && p != owner && p.isAlive()
                        && p.team == owner.team && p.position != null)
                .map(p -> Vec.of(p.position)).toList();
    }

    private void restoreGun() {
        if (kiting && owner.secondaryWeapon != null) owner.switchToSlot(2);
        else if (owner.primaryWeapon != null) owner.switchToSlot(1);
        else if (owner.secondaryWeapon != null) owner.switchToSlot(2);
    }

    private void applyCombatWeapon(int desiredSlot) {
        if (!grenadeBusy && desiredSlot > 0 && owner.currentSlot != desiredSlot)
            owner.switchToSlot(desiredSlot);
    }

    private AIInput executeZombie(AIWorldView world, long now) {
        // Preserve the mode's existing zombie smell fallback, but never target a zombie or self.
        if (primaryTarget == null) {
            primaryTarget = gameState.getAllCharacters().stream()
                    .filter(p -> ZombieHostilityPolicy.canTarget(owner, p))
                    .min(Comparator.comparingDouble(p -> owner.position.distanceSq(p.position))).orElse(null);
        }
        if (!ZombieHostilityPolicy.canTarget(owner, primaryTarget)) {
            pathfindingModule.setTarget(null);
            return neutral();
        }
        double distance = owner.position.distance(primaryTarget.position);
        double angle = Math.atan2(primaryTarget.position.y - owner.position.y,
                primaryTarget.position.x - owner.position.x);
        if (distance < Player.SIZE * 1.5 && pathfindingModule.hasLineOfSight(primaryTarget.position)) {
            if (now - lastMeleeTime >= 1_000 && ZombieHostilityPolicy.canTarget(owner, primaryTarget)) {
                gameState.requestMelee(owner, primaryTarget, 20);
                lastMeleeTime = now;
            }
        }
        Vec origin = Vec.of(owner.position);
        List<ZombiePursuitPlanner.Neighbor> pack = gameState.getAllCharacters().stream()
                .filter(p -> p != null && p.isAlive() && p.team == Player.Team.ZOMBIE && p.position != null)
                .map(p -> new ZombiePursuitPlanner.Neighbor(p.id, Vec.of(p.position))).toList();
        if (lastZombieProgressPosition == null || origin.distance(lastZombieProgressPosition) >= 14) {
            lastZombieProgressPosition = origin;
            lastZombieProgressAt = now;
            zombieDetourTarget = null;
        }
        boolean congested = owner.isCollidingWithTeammate
                || pack.stream().anyMatch(p -> !p.id().equals(owner.id) && p.position().distance(origin) < 28);
        if ((congested || now - lastZombieProgressAt >= 1_000) && now >= zombieDetourUntil) {
            zombieDetourTarget = pursuit.detour(owner.id, origin, Vec.of(primaryTarget.position), pack,
                    this::walkable);
            zombieDetourUntil = now + 750;
            lastZombieProgressAt = now;
        }
        Vec destination = zombieDetourTarget != null && now < zombieDetourUntil
                ? zombieDetourTarget
                : pursuit.engagementPoint(owner.id, Vec.of(primaryTarget.position), pack, this::walkable);
        moveTo(destination, 8);
        AIInput movement = pathfindingModule.update(world);
        return new AIInput(movement.keys(), movement.angle(), false, false, false);
    }

    private AIInput neutral() { return new AIInput(List.of(), owner.angle, false, false, false); }

    public void reset() {
        primaryTarget = null;
        lastKnownPosition = null;
        movementGoal = null;
        nextPositionAt = 0;
        nextGrenadeAt = 0;
        lastMeleeTime = 0;
        grenadeBusy = false;
        grenadeItem = null;
        wasEmergency = false;
        kiting = false;
        clearEscapeRoute();
        nextEscapePreparationAt = 0;
        lastZombieProgressPosition = null;
        lastZombieProgressAt = 0;
        zombieDetourTarget = null;
        zombieDetourUntil = 0;
        crowdFire.reset();
        crowdFireDecision = ZombieCrowdFirePlanner.Decision.NONE;
        if (pathfindingModule != null) pathfindingModule.reset();
        if (attackModule != null) attackModule.reset();
        if (grenadeModule != null) grenadeModule.cancelPendingWork();
    }
    public void cancelPendingActions() { reset(); }
    public void onDeath() { reset(); }
    public void onKill() { nextGrenadeAt = System.currentTimeMillis() + 1_500; }

    public void initializeWeaponChoice() {
        if (owner.team != Player.Team.CT) { owner.nextWeapon = null; return; }
        List<Weapon> choices = List.of(Weapon.M249, Weapon.NEGEV, Weapon.P90,
                Weapon.BIZON, Weapon.MAC10, Weapon.XM1014, Weapon.MP7);
        owner.nextWeapon = choices.get(rand.nextInt(choices.size())).name();
    }
}
