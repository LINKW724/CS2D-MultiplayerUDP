package cs2d.server;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class NetworkProtocolTest {
    private final Gson gson = new Gson();

    @Test
    void chunksPreserveOrderingMetadata() {
        JsonObject state = new JsonObject();
        state.addProperty("type", "full_update");
        state.addProperty("protocolVersion", 2);
        state.addProperty("sessionId", "session-a");
        state.addProperty("sequence", 42L);
        state.addProperty("serverTick", 120L);
        state.addProperty("payload", "state-data".repeat(1000));

        NetworkBroadcaster broadcaster = new NetworkBroadcaster(null, ignored -> { }, "session-a");
        List<String> chunks = broadcaster.prepareChunks(gson.toJson(state));

        assertFalse(chunks.isEmpty());
        for (String encodedChunk : chunks) {
            JsonObject chunk = gson.fromJson(encodedChunk, JsonObject.class);
            assertEquals(2, chunk.get("protocolVersion").getAsInt());
            assertEquals("session-a", chunk.get("sessionId").getAsString());
            assertEquals(42L, chunk.get("sequence").getAsLong());
            assertEquals(120L, chunk.get("serverTick").getAsLong());
        }
    }

    @Test
    void kevlarOwnerPaysOnlyHelmetUpgradeDifference() {
        assertEquals(350, GameState.calculatePurchaseCost(Item.KEVLAR_HELMET, true, false));
        assertEquals(1000, GameState.calculatePurchaseCost(Item.KEVLAR_HELMET, false, false));
        assertEquals(1000, GameState.calculatePurchaseCost(Item.KEVLAR_HELMET, true, true));
    }
}
