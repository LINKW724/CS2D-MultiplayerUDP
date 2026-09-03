package cs2d.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BroadcastAccumulatorTest {

    @Test
    void keepsLatestStateAndMergesUnsentEventsInOrder() {
        BroadcastAccumulator accumulator = new BroadcastAccumulator();
        accumulator.offer(job(100L, false, 7L));
        accumulator.offer(job(101L, false, 8L));

        BroadcastAccumulator.Job merged = accumulator.poll();

        assertEquals(101L, merged.state().get("serverTick").getAsLong());
        JsonArray sounds = merged.state().getAsJsonArray("soundEvents");
        assertEquals(2, sounds.size());
        assertEquals(7L, sounds.get(0).getAsJsonObject().get("eventId").getAsLong());
        assertEquals(8L, sounds.get(1).getAsJsonObject().get("eventId").getAsLong());
    }

    @Test
    void forcedFullStateSurvivesButReceivesNewEvents() {
        BroadcastAccumulator accumulator = new BroadcastAccumulator();
        accumulator.offer(job(100L, true, 7L));
        accumulator.offer(job(101L, false, 8L));

        BroadcastAccumulator.Job merged = accumulator.poll();

        assertTrue(merged.forcedFull());
        assertEquals(100L, merged.state().get("serverTick").getAsLong());
        assertEquals(2, merged.state().getAsJsonArray("soundEvents").size());
    }

    @Test
    void duplicateEventIdsAreNotRepeated() {
        BroadcastAccumulator.Job merged = BroadcastAccumulator.merge(
                job(100L, false, 7L), job(101L, false, 7L));
        assertEquals(1, merged.state().getAsJsonArray("soundEvents").size());
    }

    private static BroadcastAccumulator.Job job(long tick, boolean forced, long eventId) {
        JsonObject state = new JsonObject();
        state.addProperty("serverTick", tick);
        JsonObject sound = new JsonObject();
        sound.addProperty("eventId", eventId);
        JsonArray sounds = new JsonArray();
        sounds.add(sound);
        state.add("soundEvents", sounds);
        return new BroadcastAccumulator.Job(state, List.of(), forced);
    }
}
