package cs2d.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class BoundedEventDeduplicatorTest {
    @Test
    void repeatedKillFeedEventIsAcceptedOnlyOnce() {
        BoundedEventDeduplicator<String> deduplicator = new BoundedEventDeduplicator<>(4);

        assertTrue(deduplicator.accept("killer-victim-123"));
        assertFalse(deduplicator.accept("killer-victim-123"));
        assertFalse(deduplicator.accept("killer-victim-123"));
    }

    @Test
    void evictsOldestIdentifierAtCapacity() {
        BoundedEventDeduplicator<String> deduplicator = new BoundedEventDeduplicator<>(2);

        assertTrue(deduplicator.accept("one"));
        assertTrue(deduplicator.accept("two"));
        assertTrue(deduplicator.accept("three"));
        assertEquals(2, deduplicator.size());
        assertTrue(deduplicator.accept("one"));
    }
}
