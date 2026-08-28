package cs2d.client;

/** Converts a high-frequency JavaFX pulse clock into a stable client render rate. */
final class RenderFrameScheduler {
    private final long frameIntervalNanos;
    private boolean initialized;
    private long nextDeadlineNanos;

    RenderFrameScheduler(int targetRate) {
        if (targetRate <= 0)
            throw new IllegalArgumentException("targetRate must be positive");
        frameIntervalNanos = Math.round(1_000_000_000.0 / targetRate);
    }

    boolean shouldRender(long nowNanos) {
        if (!initialized) {
            initialized = true;
            nextDeadlineNanos = nowNanos;
        }
        if (nowNanos < nextDeadlineNanos)
            return false;

        // Preserve the long-term phase during normal jitter. After a real stall,
        // skip missed frames instead of executing a burst of catch-up renders.
        if (nowNanos - nextDeadlineNanos > frameIntervalNanos * 4) {
            nextDeadlineNanos = nowNanos + frameIntervalNanos;
        } else {
            do {
                nextDeadlineNanos += frameIntervalNanos;
            } while (nextDeadlineNanos <= nowNanos);
        }
        return true;
    }

    long frameIntervalNanos() {
        return frameIntervalNanos;
    }
}
