package cs2d.server;

import cs2d.AIControl.A.PathfindingModule;
import cs2d.AIControl.team.TacticalCoordinator;
import cs2d.AIControl.team.TacticalOrder;
import cs2d.AIControl.team.TacticalOrderProvider;
import cs2d.AIControl.team.TeamTacticalSnapshot;
import cs2d.AIControl.team.TeamTacticalSnapshot.AgentSnapshot;
import cs2d.AIControl.team.TeamTacticalSnapshot.ContactSnapshot;
import cs2d.AIControl.team.TeamTacticalSnapshot.ContactType;
import cs2d.AIControl.team.TeamTacticalSnapshot.HazardSnapshot;
import cs2d.AIControl.team.TeamTacticalSnapshot.HazardType;
import cs2d.AIControl.team.TeamTacticalSnapshot.Vec2;
import cs2d.playerAndAi.Player;

import java.awt.geom.Point2D;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Adapts the mutable server world to the immutable team-tactics API. The
 * coordinator never receives GameState, Player or pathfinding implementations.
 */
final class TeamTacticalRuntime implements TacticalOrderProvider {

    private static final long PLAN_INTERVAL_MS = 250L;
    private static final long CONTACT_MEMORY_MS = 3_000L;
    private static final int DEATH_MEMORY_MS = 20_000;
    private static final long DROP_MEMORY_MS = 30_000L;

    private final GameState gameState;
    private final TacticalCoordinator coordinator;
    private final Map<String, SoundEvent> recentContacts = new LinkedHashMap<>();
    private volatile Map<String, TacticalOrder> orders = Map.of();
    private long lastPlanTime;

    TeamTacticalRuntime(GameState gameState, TacticalCoordinator coordinator) {
        this.gameState = Objects.requireNonNull(gameState, "gameState");
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
    }

    void update(long now, List<SoundEvent> sounds, List<Player> independentAis) {
        rememberContacts(now, sounds);
        if (now - lastPlanTime < PLAN_INTERVAL_MS) {
            return;
        }
        lastPlanTime = now;

        List<Player> allCharacters = gameState.getAllCharacters();
        Map<String, Player> playersById = new HashMap<>();
        for (Player player : allCharacters) {
            if (player != null && player.id != null) {
                playersById.put(player.id, player);
            }
        }

        Map<Player.Team, List<Player>> teams = new LinkedHashMap<>();
        for (Player ai : independentAis) {
            if (ai != null && ai.isAlive() && ai.position != null
                    && (ai.team == Player.Team.CT || ai.team == Player.Team.T)) {
                teams.computeIfAbsent(ai.team, ignored -> new ArrayList<>()).add(ai);
            }
        }

        Map<String, TacticalOrder> nextOrders = new LinkedHashMap<>();
        for (Map.Entry<Player.Team, List<Player>> entry : teams.entrySet()) {
            TeamTacticalSnapshot snapshot = buildSnapshot(now, entry.getKey(), entry.getValue(), playersById);
            Map<String, TacticalOrder> teamOrders = coordinator.coordinate(snapshot);
            if (teamOrders != null) {
                nextOrders.putAll(teamOrders);
            }
        }
        orders = Map.copyOf(nextOrders);
    }

    @Override
    public Optional<TacticalOrder> orderFor(String agentId) {
        if (agentId == null) {
            return Optional.empty();
        }
        TacticalOrder order = orders.get(agentId);
        return order != null && order.isActive(System.currentTimeMillis())
                ? Optional.of(order)
                : Optional.empty();
    }

    void clear() {
        recentContacts.clear();
        orders = Map.of();
        lastPlanTime = 0L;
    }

    private TeamTacticalSnapshot buildSnapshot(long now, Player.Team team, List<Player> agents,
            Map<String, Player> playersById) {
        List<AgentSnapshot> agentSnapshots = agents.stream()
                .map(ai -> toAgentSnapshot(now, ai))
                .toList();
        List<ContactSnapshot> contacts = new ArrayList<>();
        List<HazardSnapshot> hazards = new ArrayList<>();

        for (SoundEvent sound : recentContacts.values()) {
            Player source = playersById.get(sound.sourcePlayerId());
            if (source == null || source.team == team || source.position == null) {
                continue;
            }
            ContactType type = sound.type() == SoundEvent.SoundType.FOOTSTEP
                    ? ContactType.FOOTSTEP
                    : ContactType.GUNSHOT;
            Vec2 position = new Vec2(sound.x(), sound.y());
            contacts.add(new ContactSnapshot(source.id, position, type, sound.timestamp()));
            if (type == ContactType.GUNSHOT) {
                hazards.add(new HazardSnapshot(position, 1.4, HazardType.ENEMY_FIRE, sound.timestamp()));
            }
        }

        for (GameState.PlayerDeath death : gameState.getRecentDeaths(DEATH_MEMORY_MS)) {
            if (death != null && death.team == team && death.position != null) {
                double freshness = remainingFraction(now - death.timestamp, DEATH_MEMORY_MS);
                hazards.add(new HazardSnapshot(toVec(death.position), 4.0 * freshness,
                        HazardType.FRIENDLY_DEATH, death.timestamp));
            }
        }

        for (GameState.DroppedItem item : gameState.getDroppedItems()) {
            if (item == null || item.isBomb || item.weapon == null || item.position == null
                    || item.dropperPlayerId != null) {
                continue;
            }
            long age = now - item.dropTime;
            if (age >= 0L && age <= DROP_MEMORY_MS) {
                double freshness = remainingFraction(age, DROP_MEMORY_MS);
                hazards.add(new HazardSnapshot(toVec(item.position), 0.75 * freshness,
                        HazardType.DROPPED_WEAPON, item.dropTime));
            }
        }

        return new TeamTacticalSnapshot(team.name(), now, gameState.getMapWidth(), gameState.getMapHeight(),
                agentSnapshots, contacts, hazards);
    }

    private AgentSnapshot toAgentSnapshot(long now, Player ai) {
        PathfindingModule pathfinding = ai.getPathfindingModule();
        String routeId = pathfinding == null ? null : pathfinding.getAssignedPresetRouteId();
        List<Vec2> routePoints = pathfinding == null
                ? List.of()
                : pathfinding.getAssignedPresetRoutePoints().stream().map(TeamTacticalRuntime::toVec).toList();
        boolean recentlyDamaged = now - ai.lastDamageSourcePositionTime <= 2_000L;
        return new AgentSnapshot(ai.id, toVec(ai.position), ai.health, ai.isReloading, recentlyDamaged,
                routeId, routePoints);
    }

    private void rememberContacts(long now, List<SoundEvent> sounds) {
        recentContacts.entrySet().removeIf(entry -> now - entry.getValue().timestamp() > CONTACT_MEMORY_MS);
        if (sounds == null) {
            return;
        }
        for (SoundEvent sound : sounds) {
            if (sound == null || sound.sourcePlayerId() == null
                    || (sound.type() != SoundEvent.SoundType.FIRE
                    && sound.type() != SoundEvent.SoundType.FOOTSTEP)) {
                continue;
            }
            String key = sound.type().name() + ':' + sound.sourcePlayerId();
            recentContacts.put(key, sound);
        }
    }

    private static Vec2 toVec(Point2D.Double point) {
        return new Vec2(point.x, point.y);
    }

    private static double remainingFraction(long age, long lifetime) {
        return Math.max(0.0, Math.min(1.0, 1.0 - (double) Math.max(0L, age) / lifetime));
    }
}
