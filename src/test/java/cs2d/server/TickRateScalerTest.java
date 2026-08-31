package cs2d.server;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TickRateScalerTest {

    @Test
    void sixtyHertzUsesTwoLegacyMotionSubsteps() {
        assertEquals(2, TickRateScaler.integralLegacySubsteps(60.0));
        assertEquals(0.95 * 0.95, TickRateScaler.friction(0.95, 60.0), 1e-12);
    }

    @Test
    void twoLegacySubstepsPreserveVelocityAndAccumulatedDisplacement() {
        double velocity = 0.0;
        double displacement = 0.0;
        for (int step = 0; step < TickRateScaler.integralLegacySubsteps(60.0); step++) {
            velocity = (velocity + 0.3) * 0.95;
            displacement += velocity;
        }

        assertEquals(0.55575, velocity, 1e-12);
        assertEquals(0.84075, displacement, 1e-12);
    }
}
