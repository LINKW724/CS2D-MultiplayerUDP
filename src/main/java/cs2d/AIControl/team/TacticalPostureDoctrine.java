package cs2d.AIControl.team;

import cs2d.AIControl.team.TacticalOrder.Posture;

/** Extension point for game-mode-specific posture selection. */
@FunctionalInterface
public interface TacticalPostureDoctrine {
    Posture choose(TacticalOrder order);
}
