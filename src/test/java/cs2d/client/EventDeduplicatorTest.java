package cs2d.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EventDeduplicatorTest {
    @Test
    void acceptsEachEventIdOnlyOnce() {
        EventDeduplicator deduplicator = new EventDeduplicator(4);
        assertTrue(deduplicator.accept(7L));
        assertFalse(deduplicator.accept(7L));
    }

    @Test
    void evictsOldestIdAtFixedCapacityAndCanResetForNewSession() {
        EventDeduplicator deduplicator = new EventDeduplicator(2);
        assertTrue(deduplicator.accept(1L));
        assertTrue(deduplicator.accept(2L));
        assertTrue(deduplicator.accept(3L));
        assertTrue(deduplicator.accept(1L));
        deduplicator.clear();
        assertTrue(deduplicator.accept(3L));
    }
}
