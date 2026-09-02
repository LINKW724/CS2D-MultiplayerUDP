package cs2d.AIControl.movement;

/**
 * Small shared admission controller for expensive local geometry decisions.
 * It spreads synchronized bot decisions across simulation frames instead of
 * allowing a spawn wave to scan cover in one main-loop tick.
 */
public final class CoverScanBudget {
    private final int maximumScansPerWindow;
    private final long windowMillis;
    private long activeWindow = Long.MIN_VALUE;
    private int scansInWindow;

    public CoverScanBudget(int maximumScansPerWindow, long windowMillis) {
        if (maximumScansPerWindow <= 0 || windowMillis <= 0) {
            throw new IllegalArgumentException("cover scan budget must be positive");
        }
        this.maximumScansPerWindow = maximumScansPerWindow;
        this.windowMillis = windowMillis;
    }

    public synchronized boolean tryAcquire(long now) {
        long window = Math.floorDiv(now, windowMillis);
        if (window != activeWindow) {
            activeWindow = window;
            scansInWindow = 0;
        }
        if (scansInWindow >= maximumScansPerWindow) {
            return false;
        }
        scansInWindow++;
        return true;
    }
}
