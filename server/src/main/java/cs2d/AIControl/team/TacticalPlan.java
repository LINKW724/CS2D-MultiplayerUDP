package cs2d.AIControl.team;

import java.util.List;
import java.util.Map;

/** Immutable output of a tactical coordinator: available tasks plus agent assignments. */
public record TacticalPlan(List<TacticalTask> tasks, Map<String, TacticalOrder> orders) {

    public TacticalPlan {
        tasks = tasks == null ? List.of() : List.copyOf(tasks);
        orders = orders == null ? Map.of() : Map.copyOf(orders);
    }

    public static TacticalPlan ordersOnly(Map<String, TacticalOrder> orders) {
        return new TacticalPlan(List.of(), orders);
    }
}
