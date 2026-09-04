package cs2d.server;

import org.junit.jupiter.api.Test;
import java.awt.geom.Point2D;
import static org.junit.jupiter.api.Assertions.*;

class PlayerPairSeparationTest {
    @Test void coincidentPlayersReceiveStableOppositeCorrections() {
        Point2D.Double point = new Point2D.Double(500, 500);
        PlayerPairSeparation.Correction ab = PlayerPairSeparation.calculate("a", point, "b", point, 24, 0.1);
        PlayerPairSeparation.Correction ba = PlayerPairSeparation.calculate("b", point, "a", point, 24, 0.1);
        assertNotNull(ab);
        assertNotNull(ba);
        assertEquals(-ab.nx(), ba.nx(), 1.0e-9);
        assertEquals(-ab.ny(), ba.ny(), 1.0e-9);
        assertTrue(ab.pushPerPlayer() > 12);
    }

    @Test void separatedPlayersNeedNoCorrection() {
        assertNull(PlayerPairSeparation.calculate("a", new Point2D.Double(0, 0),
                "b", new Point2D.Double(24, 0), 24, 0.1));
    }
}
