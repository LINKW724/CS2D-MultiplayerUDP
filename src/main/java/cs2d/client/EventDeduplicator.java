package cs2d.client;

import java.util.LinkedHashMap;
import java.util.Map;

/** Fixed-memory deduplicator for transient UDP events replayed by the server. */
final class EventDeduplicator {
    static final int DEFAULT_CAPACITY = 4_096;

    private final int capacity;
    private final LinkedHashMap<Long, Boolean> recentIds = new LinkedHashMap<>();

    EventDeduplicator() {
        this(DEFAULT_CAPACITY);
    }

    EventDeduplicator(int capacity) {
        if (capacity <= 0)
            throw new IllegalArgumentException("Event deduplication capacity must be positive");
        this.capacity = capacity;
    }

    synchronized boolean accept(long eventId) {
        if (eventId < 0)
            return true;
        if (recentIds.putIfAbsent(eventId, Boolean.TRUE) != null)
            return false;
        while (recentIds.size() > capacity) {
            Map.Entry<Long, Boolean> oldest = recentIds.entrySet().iterator().next();
            recentIds.remove(oldest.getKey());
        }
        return true;
    }

    synchronized void clear() {
        recentIds.clear();
    }

    synchronized int size() {
        return recentIds.size();
    }
}
