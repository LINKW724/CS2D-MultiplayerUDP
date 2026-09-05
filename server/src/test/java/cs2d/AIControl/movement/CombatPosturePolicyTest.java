package cs2d.AIControl.movement;

import org.junit.jupiter.api.Test;

import java.awt.geom.Point2D;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CombatPosturePolicyTest {

    @Test
    void retreatKeepsFacingTheLastVisibleThreat() {
        CombatPosturePolicy policy = new CombatPosturePolicy();
        policy.observeThreat(1_000, new Point2D.Double(100.0, 0.0),
                CombatPosturePolicy.ThreatEvidence.VISIBLE_ENEMY);

        assertEquals(0.0, policy.chooseFacing(1_500, new Point2D.Double(0.0, 0.0),
                List.of("A"), Math.PI), 0.0001);
    }

    @Test
    void lateralRetreatAlsoKeepsTheThreatCovered() {
        CombatPosturePolicy policy = new CombatPosturePolicy();
        policy.observeThreat(1_000, new Point2D.Double(100.0, 0.0),
                CombatPosturePolicy.ThreatEvidence.DAMAGE_SOURCE);

        assertEquals(0.0, policy.chooseFacing(1_500, new Point2D.Double(0.0, 0.0),
                List.of("W"), -Math.PI / 2.0), 0.0001);
    }

    @Test
    void forwardAdvanceUsesNormalTravelFacing() {
        CombatPosturePolicy policy = new CombatPosturePolicy();
        policy.observeThreat(1_000, new Point2D.Double(100.0, 0.0),
                CombatPosturePolicy.ThreatEvidence.VISIBLE_ENEMY);

        assertEquals(0.25, policy.chooseFacing(1_500, new Point2D.Double(0.0, 0.0),
                List.of("D"), 0.25), 0.0001);
    }

    @Test
    void threatMemoryExpiresWithoutFreshReliableEvidence() {
        CombatPosturePolicy policy = new CombatPosturePolicy();
        policy.observeThreat(1_000, new Point2D.Double(100.0, 0.0),
                CombatPosturePolicy.ThreatEvidence.VISIBLE_ENEMY);

        assertTrue(policy.hasActiveThreat(2_800));
        assertFalse(policy.hasActiveThreat(2_801));
        assertEquals(Math.PI, policy.chooseFacing(2_801, new Point2D.Double(0.0, 0.0),
                List.of("A"), Math.PI), 0.0001);
    }
}
