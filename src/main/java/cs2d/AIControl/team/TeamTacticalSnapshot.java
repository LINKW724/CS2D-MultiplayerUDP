package cs2d.AIControl.team;

import java.util.List;

/** Immutable world model presented to a team coordinator. */
public record TeamTacticalSnapshot(
        String teamId,
        long timestamp,
        int mapWidth,
        int mapHeight,
        List<AgentSnapshot> agents,
        List<ContactSnapshot> contacts,
        List<HazardSnapshot> hazards) {

    public TeamTacticalSnapshot {
        agents = agents == null ? List.of() : List.copyOf(agents);
        contacts = contacts == null ? List.of() : List.copyOf(contacts);
        hazards = hazards == null ? List.of() : List.copyOf(hazards);
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
