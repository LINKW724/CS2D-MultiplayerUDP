package cs2d.AIControl.team;

import java.util.Map;

/** Selects authored routes for agents that do not yet own one. */
public interface RouteAssignmentPolicy {

    Map<String, String> assign(TeamTacticalSnapshot snapshot);
}
