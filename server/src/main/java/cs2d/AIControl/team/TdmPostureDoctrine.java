package cs2d.AIControl.team;

import cs2d.AIControl.team.TacticalOrder.Posture;
import cs2d.AIControl.team.TacticalOrder.TaskType;

/** Default TDM doctrine: movement is quiet unless a maneuver explicitly declares a rush. */
public final class TdmPostureDoctrine implements TacticalPostureDoctrine {

    @Override
    public Posture choose(TacticalOrder order) {
        if (order == null) {
            return Posture.AMBUSH;
        }
        return order.taskType() == TaskType.ADVANCE && order.posture() == Posture.RUSH
                ? Posture.RUSH
                : Posture.STEALTH_ADVANCE;
    }
}
