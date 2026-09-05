package cs2d.AIControl.team;

import cs2d.AIControl.team.TeamTacticalSnapshot.RouteSnapshot;
import cs2d.AIControl.team.TeamTacticalSnapshot.Vec2;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Assigns distinct, route-safe longitudinal slots to agents sharing one objective. */
public final class TacticalFormationSlotPolicy {

    private static final double SLOT_SPACING = 46.0;
    private final Map<SlotKey, Integer> stableSlots = new HashMap<>();

    public Map<String, TacticalOrder> assign(Map<String, TacticalOrder> orders,
            TeamTacticalSnapshot snapshot) {
        if (orders == null || orders.isEmpty() || snapshot == null) {
            return orders == null ? Map.of() : Map.copyOf(orders);
        }

        Map<String, RouteSnapshot> routes = new HashMap<>();
        for (RouteSnapshot route : snapshot.routes()) {
            if (route != null && route.routeId() != null && route.keyPoints().size() >= 2) {
                routes.put(route.routeId(), route);
            }
        }

        String teamId = snapshot.teamId() == null ? "" : snapshot.teamId();
        Map<String, List<TacticalOrder>> groups = new LinkedHashMap<>();
        for (TacticalOrder order : orders.values()) {
            if (order == null || order.agentId() == null) {
                continue;
            }
            String key = order.taskId() == null ? "agent:" + order.agentId() : "task:" + order.taskId();
            groups.computeIfAbsent(key, ignored -> new ArrayList<>()).add(order);
        }

        Map<String, TacticalOrder> result = new LinkedHashMap<>();
        java.util.Set<SlotKey> activeSlotKeys = new java.util.HashSet<>();
        for (Map.Entry<String, List<TacticalOrder>> entry : groups.entrySet()) {
            String groupId = entry.getKey();
            List<TacticalOrder> group = entry.getValue();
            group.sort(Comparator.comparing(TacticalOrder::agentId));
            TacticalOrder representative = group.get(0);
            RouteSnapshot route = routes.get(representative.routeId());
            boolean routeSlots = group.size() > 1 && representative.preserveMapRoute()
                    && representative.movementTarget() != null && route != null;
            java.util.Set<Integer> usedSlots = new java.util.HashSet<>();
            if (routeSlots) {
                for (TacticalOrder order : group) {
                    SlotKey key = new SlotKey(teamId, groupId, order.agentId());
                    Integer existing = stableSlots.get(key);
                    if (existing != null) {
                        usedSlots.add(existing);
                    }
                }
            }
            for (TacticalOrder order : group) {
                int slotIndex = 0;
                SlotKey key = new SlotKey(teamId, groupId, order.agentId());
                if (routeSlots) {
                    Integer existing = stableSlots.get(key);
                    if (existing == null) {
                        while (usedSlots.contains(slotIndex)) {
                            slotIndex++;
                        }
                        stableSlots.put(key, slotIndex);
                        usedSlots.add(slotIndex);
                    } else {
                        slotIndex = existing;
                    }
                    activeSlotKeys.add(key);
                }
                Vec2 slot = routeSlots
                        ? pointBehind(route.keyPoints(), order.movementTarget(), slotIndex * SLOT_SPACING)
                        : order.movementTarget();
                result.put(order.agentId(), order.withMovementTarget(slot));
            }
        }
        stableSlots.keySet().removeIf(key -> key.teamId().equals(teamId) && !activeSlotKeys.contains(key));
        return Map.copyOf(result);
    }

    public void clear() {
        stableSlots.clear();
    }

    private static Vec2 pointBehind(List<Vec2> points, Vec2 target, double offset) {
        if (offset <= 0.0 || points.size() < 2) {
            return target;
        }

        double bestDistanceSq = Double.POSITIVE_INFINITY;
        double targetAlongRoute = 0.0;
        double accumulated = 0.0;
        for (int i = 1; i < points.size(); i++) {
            Vec2 start = points.get(i - 1);
            Vec2 end = points.get(i);
            double dx = end.x() - start.x();
            double dy = end.y() - start.y();
            double length = Math.hypot(dx, dy);
            if (length <= 1.0e-9) {
                continue;
            }
            double t = ((target.x() - start.x()) * dx + (target.y() - start.y()) * dy)
                    / (length * length);
            t = Math.max(0.0, Math.min(1.0, t));
            double x = start.x() + t * dx;
            double y = start.y() + t * dy;
            double distanceSq = square(target.x() - x) + square(target.y() - y);
            if (distanceSq < bestDistanceSq) {
                bestDistanceSq = distanceSq;
                targetAlongRoute = accumulated + t * length;
            }
            accumulated += length;
        }

        double wanted = Math.max(0.0, targetAlongRoute - offset);
        accumulated = 0.0;
        for (int i = 1; i < points.size(); i++) {
            Vec2 start = points.get(i - 1);
            Vec2 end = points.get(i);
            double length = Math.hypot(end.x() - start.x(), end.y() - start.y());
            if (length <= 1.0e-9) {
                continue;
            }
            if (accumulated + length >= wanted) {
                double t = (wanted - accumulated) / length;
                return new Vec2(start.x() + (end.x() - start.x()) * t,
                        start.y() + (end.y() - start.y()) * t);
            }
            accumulated += length;
        }
        return points.get(points.size() - 1);
    }

    private static double square(double value) {
        return value * value;
    }

    private record SlotKey(String teamId, String groupId, String agentId) {
    }
}
