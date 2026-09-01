package cs2d.AIControl.BG;

import cs2d.AIControl.B.PerceptionType;
import cs2d.playerAndAi.Player;

import java.awt.geom.Point2D;
import java.util.List;

/**
 * Selects a small, deterministic response squad for non-visual enemy sounds.
 * Direct visual contact is intentionally handled outside this policy.
 */
final class TeamSoundResponsePolicy {

    private static final double GUNSHOT_RESPONSE_RATIO = 0.10;
    private static final int MAX_GUNSHOT_RESPONDERS = 3;
    private static final double FOOTSTEP_RESPONSE_RATIO = 0.05;
    private static final int MAX_FOOTSTEP_RESPONDERS = 1;
    private static final double MAX_GUNSHOT_RESPONSE_DISTANCE = 900.0;
    private static final double MAX_FOOTSTEP_RESPONSE_DISTANCE = 650.0;

    private TeamSoundResponsePolicy() {
    }

    static boolean shouldRespond(Player owner, Point2D.Double soundPosition, PerceptionType type,
            List<Player> eligibleTeamAi) {
        if (owner == null || owner.id == null || owner.position == null || soundPosition == null || type == null
                || eligibleTeamAi == null || eligibleTeamAi.isEmpty()) {
            return false;
        }

        int responseLimit = responseLimit(type, eligibleTeamAi.size());
        if (responseLimit <= 0) {
            return false;
        }

        double ownerDistanceSq = owner.position.distanceSq(soundPosition);
        double maximumDistance = type == PerceptionType.GUNSHOT
                ? MAX_GUNSHOT_RESPONSE_DISTANCE
                : MAX_FOOTSTEP_RESPONSE_DISTANCE;
        if (ownerDistanceSq > maximumDistance * maximumDistance) {
            return false;
        }
        int candidatesAhead = 0;
        boolean ownerIsEligible = false;

        for (Player candidate : eligibleTeamAi) {
            if (candidate == null || candidate.id == null || candidate.position == null) {
                continue;
            }
            if (candidate.id.equals(owner.id)) {
                ownerIsEligible = true;
                continue;
            }

            double candidateDistanceSq = candidate.position.distanceSq(soundPosition);
            int distanceOrder = Double.compare(candidateDistanceSq, ownerDistanceSq);
            if (distanceOrder < 0 || (distanceOrder == 0 && candidate.id.compareTo(owner.id) < 0)) {
                candidatesAhead++;
                if (candidatesAhead >= responseLimit) {
                    return false;
                }
            }
        }

        return ownerIsEligible;
    }

    static int responseLimit(PerceptionType type, int aliveTeamAiCount) {
        if (type == null || aliveTeamAiCount <= 0 || type == PerceptionType.SIGHT) {
            return 0;
        }
        if (type == PerceptionType.GUNSHOT) {
            return scaledLimit(aliveTeamAiCount, GUNSHOT_RESPONSE_RATIO, MAX_GUNSHOT_RESPONDERS);
        }
        return scaledLimit(aliveTeamAiCount, FOOTSTEP_RESPONSE_RATIO, MAX_FOOTSTEP_RESPONDERS);
    }

    private static int scaledLimit(int teamSize, double ratio, int maximum) {
        return Math.max(1, Math.min(maximum, (int) Math.ceil(teamSize * ratio)));
    }
}
