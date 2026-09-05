package cs2d.client;

/** Fixed-memory deduplicator for transient UDP events replayed by the server. */
final class EventDeduplicator {
    static final int DEFAULT_CAPACITY = 4_096;

    private final BoundedEventDeduplicator<Long> recentIds;

    EventDeduplicator() {
        this(DEFAULT_CAPACITY);
    }

    EventDeduplicator(int capacity) {
        recentIds = new BoundedEventDeduplicator<>(capacity);
    }

    synchronized boolean accept(long eventId) {
        if (eventId < 0)
            return true;
        return recentIds.accept(eventId);
    }

    synchronized void clear() {
        recentIds.clear();
    }

    synchronized int size() {
        return recentIds.size();
    }
}
