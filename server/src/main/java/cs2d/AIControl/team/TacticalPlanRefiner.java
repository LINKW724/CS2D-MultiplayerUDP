package cs2d.AIControl.team;

/**
 * Open extension point for optional team doctrines. Refiners decorate a valid
 * base plan and remain independent of mutable server or AI controller state.
 */
@FunctionalInterface
public interface TacticalPlanRefiner {
    TacticalPlan refine(TeamTacticalSnapshot snapshot, TacticalPlan basePlan);
}
