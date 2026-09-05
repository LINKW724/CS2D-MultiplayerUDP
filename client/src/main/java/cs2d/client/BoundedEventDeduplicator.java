package cs2d.client;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Fixed-memory first-observation tracker for identifiers repeated across UDP
 * snapshots.
 */
final class BoundedEventDeduplicator<T> {
    private final int capacity;
    private final Set<T> recentIds = new LinkedHashSet<>();

    BoundedEventDeduplicator(int capacity) {
        if (capacity <= 0)
            throw new IllegalArgumentException("Event deduplication capacity must be positive");
        this.capacity = capacity;
    }

    synchronized boolean accept(T eventId) {
        if (eventId == null || !recentIds.add(eventId))
            return false;
        while (recentIds.size() > capacity) {
            T oldest = recentIds.iterator().next();
            recentIds.remove(oldest);
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
