package cs2d.server;

import java.awt.geom.Point2D;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ZombieSpawnStallMonitorTest {
    @Test void untouchedSpawnIsRecycledOnlyAfterItsFiveToTenSecondDeadline() {
        ZombieSpawnStallMonitor monitor = new ZombieSpawnStallMonitor();
        long deadline = ZombieSpawnStallMonitor.recycleDelayMillis("z");
        monitor.register("z", point(100, 100), 500, 0);
        assertTrue(monitor.findRecyclable(List.of(sample("z", 100, 100, 500)), deadline - 1).isEmpty());
        assertEquals(List.of("z"),
                monitor.findRecyclable(List.of(sample("z", 100, 100, 500)), deadline));
        assertTrue(deadline >= 5_000 && deadline <= 10_000);
    }

    @Test void movementPermanentlyDisqualifiesZombieFromSpawnRecycle() {
        ZombieSpawnStallMonitor monitor = new ZombieSpawnStallMonitor();
        monitor.register("z", point(100, 100), 500, 0);
        monitor.findRecyclable(List.of(sample("z", 140, 100, 500)), 1_000);
        assertTrue(monitor.findRecyclable(List.of(sample("z", 100, 100, 500)), 20_000).isEmpty());
    }

    @Test void anyHealthChangePermanentlyDisqualifiesZombieFromSpawnRecycle() {
        ZombieSpawnStallMonitor monitor = new ZombieSpawnStallMonitor();
        monitor.register("z", point(100, 100), 500, 0);
        monitor.findRecyclable(List.of(sample("z", 100, 100, 499)), 1_000);
        assertTrue(monitor.findRecyclable(List.of(sample("z", 100, 100, 500)), 20_000).isEmpty());
    }

    private static ZombieSpawnStallMonitor.Sample sample(String id, double x, double y, int health) {
        return new ZombieSpawnStallMonitor.Sample(id, point(x, y), health, true);
    }
    private static Point2D.Double point(double x, double y) { return new Point2D.Double(x, y); }
}
