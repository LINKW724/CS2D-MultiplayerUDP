package cs2d.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;

/**
 * One-slot latest-state mailbox that never applies latest-wins semantics to
 * transient events. Replaced state is discarded, but its unsent event ids are
 * merged into the surviving job.
 */
final class BroadcastAccumulator {
    record Job(JsonObject state, List<InetSocketAddress> recipients, boolean forcedFull) {
        Job {
            recipients = recipients == null ? List.of() : List.copyOf(recipients);
        }
    }

    private final AtomicReference<Job> pending = new AtomicReference<>();
    private final LongAdder coalesced = new LongAdder();

    void offer(Job incoming) {
        if (incoming == null || incoming.state() == null)
            return;
        while (true) {
            Job current = pending.get();
            Job merged = current == null ? incoming : merge(current, incoming);
            if (pending.compareAndSet(current, merged)) {
                if (current != null)
                    coalesced.increment();
                return;
            }
        }
    }

    Job poll() {
        return pending.getAndSet(null);
    }

    boolean hasPending() {
        return pending.get() != null;
    }

    void clear() {
        pending.set(null);
    }

    long coalescedThenReset() {
        return coalesced.sumThenReset();
    }

    static Job merge(Job current, Job incoming) {
        boolean preserveCurrentState = current.forcedFull() && !incoming.forcedFull();
        Job stateOwner = preserveCurrentState ? current : incoming;
        JsonObject mergedState = stateOwner.state().deepCopy();
        mergeEvents(mergedState, current.state(), incoming.state());
        return new Job(mergedState, stateOwner.recipients(), stateOwner.forcedFull());
    }

    private static void mergeEvents(JsonObject target, JsonObject first, JsonObject second) {
        for (String field : TransientEventJournal.EVENT_FIELDS) {
            Map<Long, JsonObject> byId = new LinkedHashMap<>();
            collect(field, first, byId);
            collect(field, second, byId);
            List<Map.Entry<Long, JsonObject>> ordered = new ArrayList<>(byId.entrySet());
            ordered.sort(Comparator.comparingLong(Map.Entry::getKey));
            JsonArray array = new JsonArray();
            ordered.forEach(entry -> array.add(entry.getValue().deepCopy()));
            target.add(field, array);
        }
    }

    private static void collect(String field, JsonObject source, Map<Long, JsonObject> target) {
        if (source == null || !source.has(field) || !source.get(field).isJsonArray())
            return;
        for (JsonElement element : source.getAsJsonArray(field)) {
            if (element == null || !element.isJsonObject())
                continue;
            JsonObject payload = element.getAsJsonObject();
            if (!payload.has("eventId") || !payload.get("eventId").isJsonPrimitive())
                continue;
            target.putIfAbsent(payload.get("eventId").getAsLong(), payload);
        }
    }
}
