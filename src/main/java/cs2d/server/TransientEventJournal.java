package cs2d.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Retains one-shot game events across several UDP snapshots. Continuous world
 * state remains latest-wins, while events receive stable ids and a short replay
 * window so a coalesced or lost snapshot cannot erase them.
 */
final class TransientEventJournal {
    static final long DEFAULT_RETENTION_MILLIS = 500L;
    static final int DEFAULT_MAX_EVENTS = 4_096;
    static final List<String> EVENT_FIELDS = List.of(
            "soundEvents", "privateSoundEvents", "damageLogEvents",
            "flashEvents", "footstepReveals");

    private final long retentionMillis;
    private final int maximumEvents;
    private final AtomicLong sequence = new AtomicLong();
    private final ArrayDeque<Entry> entries = new ArrayDeque<>();

    TransientEventJournal() {
        this(DEFAULT_RETENTION_MILLIS, DEFAULT_MAX_EVENTS);
    }

    TransientEventJournal(long retentionMillis, int maximumEvents) {
        if (retentionMillis <= 0 || maximumEvents <= 0)
            throw new IllegalArgumentException("Invalid transient event journal limits");
        this.retentionMillis = retentionMillis;
        this.maximumEvents = maximumEvents;
    }

    synchronized void captureAndReplay(JsonObject state, long nowMillis) {
        if (state == null)
            return;
        evictExpired(nowMillis);
        for (String field : EVENT_FIELDS) {
            JsonArray fresh = state.has(field) && state.get(field).isJsonArray()
                    ? state.getAsJsonArray(field)
                    : null;
            if (fresh == null)
                continue;
            for (JsonElement element : fresh) {
                if (element == null || !element.isJsonObject())
                    continue;
                JsonObject payload = element.getAsJsonObject().deepCopy();
                long eventId = sequence.incrementAndGet();
                payload.addProperty("eventId", eventId);
                entries.addLast(new Entry(eventId, nowMillis, field, payload));
            }
        }
        while (entries.size() > maximumEvents)
            entries.removeFirst();

        for (String field : EVENT_FIELDS)
            state.add(field, new JsonArray());
        for (Entry entry : entries)
            state.getAsJsonArray(entry.field()).add(entry.payload().deepCopy());
    }

    private void evictExpired(long nowMillis) {
        while (!entries.isEmpty() && nowMillis - entries.peekFirst().createdAtMillis() > retentionMillis)
            entries.removeFirst();
    }

    synchronized int size() {
        return entries.size();
    }

    private record Entry(long eventId, long createdAtMillis, String field, JsonObject payload) {
    }
}
