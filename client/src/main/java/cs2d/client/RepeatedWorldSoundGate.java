package cs2d.client;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Collapses retransmitted/duplicated low-priority world sounds per source.
 * Gunfire and all unclassified sounds bypass this gate completely.
 */
final class RepeatedWorldSoundGate {
    static final long FOOTSTEP_DUPLICATE_NANOS = TimeUnit.MILLISECONDS.toNanos(100);
    static final long RELOAD_DUPLICATE_NANOS = TimeUnit.MILLISECONDS.toNanos(350);
    private static final long ENTRY_TTL_NANOS = TimeUnit.SECONDS.toNanos(10);

    private record Key(String type, String sourcePlayerId) {
    }

    private final Map<Key, Long> lastAccepted = new HashMap<>();
    private long suppressed;
    private long nextCleanupNanos;

    synchronized boolean shouldPlay(String type, String sourcePlayerId, long nowNanos) {
        long duplicateWindow = duplicateWindow(type);
        if (duplicateWindow == 0L || sourcePlayerId == null || sourcePlayerId.isBlank())
            return true;

        Key key = new Key(type, sourcePlayerId);
        Long previous = lastAccepted.get(key);
        if (previous != null && nowNanos - previous < duplicateWindow) {
            suppressed++;
            return false;
        }
        lastAccepted.put(key, nowNanos);
        cleanupIfDue(nowNanos);
        return true;
    }

    synchronized long suppressedThenReset() {
        long value = suppressed;
        suppressed = 0L;
        return value;
    }

    synchronized void clear() {
        lastAccepted.clear();
        suppressed = 0L;
        nextCleanupNanos = 0L;
    }

    private void cleanupIfDue(long nowNanos) {
        if (nowNanos < nextCleanupNanos)
            return;
        long cutoff = nowNanos - ENTRY_TTL_NANOS;
        Iterator<Long> iterator = lastAccepted.values().iterator();
        while (iterator.hasNext()) {
            if (iterator.next() < cutoff)
                iterator.remove();
        }
        nextCleanupNanos = nowNanos + ENTRY_TTL_NANOS;
    }

    private static long duplicateWindow(String type) {
        if ("FOOTSTEP".equals(type))
            return FOOTSTEP_DUPLICATE_NANOS;
        if ("RELOAD".equals(type))
            return RELOAD_DUPLICATE_NANOS;
        return 0L;
    }
}
