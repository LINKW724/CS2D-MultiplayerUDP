package cs2d.AIControl.zombie;

import cs2d.playerAndAi.Player;
import java.awt.geom.Point2D;
import java.util.List;

/** Immutable input: allied status is authoritative; enemy positions come only from fresh intel. */
public record ZombieTacticalSnapshot(long now, List<Unit> allies, List<Contact> contacts,
                                     List<ZombieAreaHazard> hazards, int remainingZombies,
                                     List<Contact> cleanupLeads, List<ZombieAttackLane> attackLanes) {
    public ZombieTacticalSnapshot {
        allies = List.copyOf(allies);
        contacts = List.copyOf(contacts);
        hazards = List.copyOf(hazards);
        remainingZombies = Math.max(0, remainingZombies);
        cleanupLeads = List.copyOf(cleanupLeads);
        attackLanes = List.copyOf(attackLanes);
    }
    public ZombieTacticalSnapshot(long now, List<Unit> allies, List<Contact> contacts) {
        this(now, allies, contacts, List.of(), 0, List.of(), List.of());
    }
    public ZombieTacticalSnapshot(long now, List<Unit> allies, List<Contact> contacts,
            List<ZombieAreaHazard> hazards) {
        this(now, allies, contacts, hazards, 0, List.of(), List.of());
    }
    public ZombieTacticalSnapshot(long now, List<Unit> allies, List<Contact> contacts,
            List<ZombieAreaHazard> hazards, int remainingZombies, List<Contact> cleanupLeads) {
        this(now, allies, contacts, hazards, remainingZombies, cleanupLeads, List.of());
    }
    public record Vec(double x, double y) {
        public double distance(Vec other) { return Math.hypot(x - other.x, y - other.y); }
        public Point2D.Double point() { return new Point2D.Double(x, y); }
        public static Vec of(Point2D.Double p) { return new Vec(p.x, p.y); }
    }
    public record Unit(String id, Player.Team team, Vec position, int health, int ammo,
                       boolean reloading, boolean independentAi, double firepower) {
        public boolean ready() { return health >= 40 && ammo > 0 && !reloading && firepower > 0; }
        public double strength() {
            return ready() ? firepower * Math.min(1.0, ammo / 8.0) * Math.min(1.0, health / 80.0) : 0.0;
        }
    }
    public record Contact(String id, Vec position, int health, long observedAt) {}
}
