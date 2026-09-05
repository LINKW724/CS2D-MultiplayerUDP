package cs2d.AIControl.zombie;

import cs2d.AIControl.zombie.ZombieTacticalOrder.Task;
import cs2d.AIControl.zombie.ZombieTacticalSnapshot.Vec;
import java.util.*;
import java.util.function.Supplier;

/** Keeps guard stations stable and gives every new task an execution generation. */
public final class ZombieTacticalTaskBoard {
    public static final long CLEAR_WINDOW_MS = 8_000L;
    public static final long CLEAR_COOLDOWN_MS = 5_000L;
    private static final long DEPLOYMENT_ANCHOR_LEASE_MS = 30_000L;
    private final Map<String, Vec> homes = new HashMap<>();
    private final Map<String, AnchorLease> deploymentAnchors = new HashMap<>();
    private final Map<String, ZombieTacticalOrder> orders = new HashMap<>();
    private final Map<String, Long> cooldowns = new HashMap<>();
    private long generation;

    public Vec home(String id, Supplier<Vec> choose) { return homes.computeIfAbsent(id, ignored -> choose.get()); }
    public Vec effectiveHome(String id, Supplier<Vec> choose, long now) {
        Vec home = home(id, choose);
        AnchorLease lease = deploymentAnchors.get(id);
        if (lease != null && now <= lease.expiresAt()) return lease.point();
        deploymentAnchors.remove(id);
        return home;
    }
    public void holdDeployment(String id, Vec point, long now) {
        if (id != null && point != null)
            deploymentAnchors.put(id, new AnchorLease(point, now + DEPLOYMENT_ANCHOR_LEASE_MS));
    }
    public void promoteHome(String id, Vec point) {
        if (id != null && point != null) homes.put(id, point);
    }
    public List<Vec> stations() { return List.copyOf(homes.values()); }
    public ZombieTacticalOrder current(String id) { return orders.get(id); }

    public boolean canClear(String id, long now) {
        ZombieTacticalOrder current = orders.get(id);
        return now >= cooldowns.getOrDefault(id, 0L)
                && (current == null || current.task() != Task.CLEAR_THREAT
                || now - current.startedAt() < CLEAR_WINDOW_MS);
    }

    public ZombieTacticalOrder assign(String id, Task task, String targetId, Vec destination, Vec home, long now) {
        return assign(id, task, targetId, destination, home, null, now);
    }

    public ZombieTacticalOrder assign(String id, Task task, String targetId, Vec destination, Vec home,
            Vec watchPoint, long now) {
        ZombieTacticalOrder old = orders.get(id);
        boolean same = old != null && old.task() == task && Objects.equals(old.targetId(), targetId)
                && old.active(now);
        if (old != null && old.task() == Task.CLEAR_THREAT && task != Task.CLEAR_THREAT)
            cooldowns.put(id, now + CLEAR_COOLDOWN_MS);
        // Changing a moving target's position updates the intent, never resurrects a completed task.
        ZombieTacticalOrder next = new ZombieTacticalOrder(id, same ? old.generation() : ++generation,
                task, targetId, destination, home, watchPoint,
                old != null && old.task() == task ? old.startedAt() : now, now + 1_200L);
        orders.put(id, next);
        return next;
    }

    public void retain(Set<String> aliveIds) {
        homes.keySet().retainAll(aliveIds);
        deploymentAnchors.keySet().retainAll(aliveIds);
        orders.keySet().retainAll(aliveIds);
        cooldowns.keySet().retainAll(aliveIds);
    }
    public void clear() { homes.clear(); deploymentAnchors.clear(); orders.clear(); cooldowns.clear(); }
    private record AnchorLease(Vec point, long expiresAt) {}
}
