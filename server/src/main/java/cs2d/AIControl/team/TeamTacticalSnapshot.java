package cs2d.AIControl.team;

import java.util.List;

/** Immutable world model presented to a team coordinator. */
public record TeamTacticalSnapshot(
        String teamId,
        long timestamp,
        int mapWidth,
        int mapHeight,
        List<RouteSnapshot> routes,
        List<AgentSnapshot> agents,
        List<ContactSnapshot> contacts,
        List<HazardSnapshot> hazards) {

    public TeamTacticalSnapshot {
        routes = routes == null ? List.of() : List.copyOf(routes);
        agents = agents == null ? List.of() : List.copyOf(agents);
        contacts = contacts == null ? List.of() : List.copyOf(contacts);
        hazards = hazards == null ? List.of() : List.copyOf(hazards);
    }

    /** Compatibility constructor for coordinators that do not provide a route catalog. */
    public TeamTacticalSnapshot(String teamId, long timestamp, int mapWidth, int mapHeight,
            List<AgentSnapshot> agents, List<ContactSnapshot> contacts, List<HazardSnapshot> hazards) {
        this(teamId, timestamp, mapWidth, mapHeight, List.of(), agents, contacts, hazards);
    }

    public record RouteSnapshot(
            String routeId,
            String routeType,
            List<Vec2> keyPoints,
            double length,
            int suggestedCapacity,
            List<String> overlappingRouteIds) {
        public RouteSnapshot {
            keyPoints = keyPoints == null ? List.of() : List.copyOf(keyPoints);
            overlappingRouteIds = overlappingRouteIds == null ? List.of() : List.copyOf(overlappingRouteIds);
        }
    }

    public record Vec2(double x, double y) {
        public double distanceSq(Vec2 other) {
            if (other == null) {
                return Double.POSITIVE_INFINITY;
            }
            double dx = x - other.x;
            double dy = y - other.y;
            return dx * dx + dy * dy;
        }
    }

    public record AgentSnapshot(
            String id,
            Vec2 position,
            int health,
            boolean reloading,
            boolean recentlyDamaged,
            String routeId,
            List<Vec2> routePoints) {
        public AgentSnapshot {
            routePoints = routePoints == null ? List.of() : List.copyOf(routePoints);
        }
    }

    public record ContactSnapshot(
            String enemyId,
            Vec2 position,
            ContactType type,
            long timestamp) {
    }

    public record HazardSnapshot(
            Vec2 position,
            double weight,
            HazardType type,
            long timestamp) {
    }

    public enum ContactType {
        GUNSHOT,
        FOOTSTEP
    }

    public enum HazardType {
        FRIENDLY_DEATH,
        DROPPED_WEAPON,
        ENEMY_FIRE
    }
}
