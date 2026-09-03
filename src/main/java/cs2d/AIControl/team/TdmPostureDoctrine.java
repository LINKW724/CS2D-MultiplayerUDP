package cs2d.AIControl.team;

import cs2d.AIControl.team.TacticalOrder.Posture;
import cs2d.AIControl.team.TacticalOrder.Role;
import cs2d.AIControl.team.TacticalOrder.TaskType;

/** Default TDM doctrine: entry advances may rush; uncertain or supporting work stays quiet. */
public final class TdmPostureDoctrine implements TacticalPostureDoctrine {

    @Override
    public Posture choose(TacticalOrder order) {
        if (order == null) {
            return Posture.AMBUSH;
        }
        TaskType task = order.taskType();
        Role role = order.role();
        return task == TaskType.ADVANCE && role == Role.ENTRY
                ? Posture.RUSH
                : Posture.STEALTH_ADVANCE;
    }
}
