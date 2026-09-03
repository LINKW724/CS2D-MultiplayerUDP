package cs2d.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class TransientEventJournalTest {

    @Test
    void replaysCapturedSoundAcrossSnapshotsWithStableId() {
        TransientEventJournal journal = new TransientEventJournal(500L, 16);
        JsonObject first = stateWithEvent("soundEvents", "soundName", "ak47_fire");

        journal.captureAndReplay(first, 1_000L);
        long eventId = first.getAsJsonArray("soundEvents").get(0).getAsJsonObject()
                .get("eventId").getAsLong();

        JsonObject next = new JsonObject();
        journal.captureAndReplay(next, 1_200L);

        assertEquals(1, next.getAsJsonArray("soundEvents").size());
        assertEquals(eventId, next.getAsJsonArray("soundEvents").get(0).getAsJsonObject()
                .get("eventId").getAsLong());
    }

    @Test
    void expiresOldEventsAndBoundsCapacity() {
        TransientEventJournal journal = new TransientEventJournal(100L, 2);
        JsonObject first = stateWithEvent("soundEvents", "soundName", "one");
        journal.captureAndReplay(first, 1_000L);
        JsonObject second = stateWithEvent("privateSoundEvents", "soundName", "two");
        journal.captureAndReplay(second, 1_010L);
        JsonObject third = stateWithEvent("flashEvents", "playerId", "three");
        journal.captureAndReplay(third, 1_020L);

        assertEquals(2, journal.size());
        assertEquals(0, third.getAsJsonArray("soundEvents").size());

        JsonObject expired = new JsonObject();
        journal.captureAndReplay(expired, 1_200L);
        assertEquals(0, journal.size());
    }

    @Test
    void assignsDifferentIdsToDistinctEvents() {
        TransientEventJournal journal = new TransientEventJournal(500L, 16);
        JsonObject state = new JsonObject();
        JsonArray sounds = new JsonArray();
        sounds.add(new JsonObject());
        sounds.add(new JsonObject());
        state.add("soundEvents", sounds);

        journal.captureAndReplay(state, 1_000L);

        JsonArray replayed = state.getAsJsonArray("soundEvents");
        assertNotEquals(replayed.get(0).getAsJsonObject().get("eventId").getAsLong(),
                replayed.get(1).getAsJsonObject().get("eventId").getAsLong());
    }

    private static JsonObject stateWithEvent(String field, String property, String value) {
        JsonObject state = new JsonObject();
        JsonObject event = new JsonObject();
        event.addProperty(property, value);
        JsonArray events = new JsonArray();
        events.add(event);
        state.add(field, events);
        return state;
    }
}
