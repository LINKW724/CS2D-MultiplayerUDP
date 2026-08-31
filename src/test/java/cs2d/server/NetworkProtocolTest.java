package cs2d.server;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NetworkProtocolTest {
    private final Gson gson = new Gson();

    @Test
    void staticObstacleBoundsAreReusedAcrossHotQuadtreeQueries() {
        MapData.ShapeWrapper wrapper = new MapData.ShapeWrapper(
                MapData.ShapeWrapper.ShapeType.RECTANGLE, 10, 20, 30, 40);
        assertSame(MapData.getObstacleBounds(wrapper), MapData.getObstacleBounds(wrapper));
    }

    @Test
    void aiFreezesForManualPauseRoundEndAndMatchEnd() {
        assertTrue(GameState.shouldFreezeAiState(true, false, GameState.RoundPhase.IN_PROGRESS));
        assertTrue(GameState.shouldFreezeAiState(false, true, GameState.RoundPhase.IN_PROGRESS));
        assertTrue(GameState.shouldFreezeAiState(false, false, GameState.RoundPhase.ROUND_OVER));
        assertFalse(GameState.shouldFreezeAiState(false, false, GameState.RoundPhase.IN_PROGRESS));
    }

    @Test
    void chunksPreserveOrderingMetadata() {
        JsonObject state = new JsonObject();
        state.addProperty("type", "full_update");
        state.addProperty("protocolVersion", GameServer.PROTOCOL_VERSION);
        state.addProperty("sessionId", "session-a");
        state.addProperty("sequence", 42L);
        state.addProperty("serverTick", 120L);
        state.addProperty("payload", "state-data".repeat(1000));

        NetworkBroadcaster broadcaster = new NetworkBroadcaster(null, ignored -> { }, "session-a");
        List<String> chunks = broadcaster.prepareChunks(gson.toJson(state));

        assertFalse(chunks.isEmpty());
        for (String encodedChunk : chunks) {
            JsonObject chunk = gson.fromJson(encodedChunk, JsonObject.class);
            assertEquals(GameServer.PROTOCOL_VERSION, chunk.get("protocolVersion").getAsInt());
            assertEquals("session-a", chunk.get("sessionId").getAsString());
            assertEquals(42L, chunk.get("sequence").getAsLong());
            assertEquals(120L, chunk.get("serverTick").getAsLong());
        }
    }

    @Test
    void forcedFullSnapshotCannotBeOverwrittenByRoutineSmallSnapshot() {
        assertTrue(NetworkBroadcaster.shouldPreservePendingForcedFull(true, false));
        assertFalse(NetworkBroadcaster.shouldPreservePendingForcedFull(false, false));
        assertFalse(NetworkBroadcaster.shouldPreservePendingForcedFull(false, true));
        assertFalse(NetworkBroadcaster.shouldPreservePendingForcedFull(true, true));
    }

    @Test
    void kevlarOwnerPaysOnlyHelmetUpgradeDifference() {
        assertEquals(350, GameState.calculatePurchaseCost(Item.KEVLAR_HELMET, true, false));
        assertEquals(1000, GameState.calculatePurchaseCost(Item.KEVLAR_HELMET, false, false));
        assertEquals(1000, GameState.calculatePurchaseCost(Item.KEVLAR_HELMET, true, true));
    }

    @Test
    void slotProtocolAcceptsNumericAndLegacyStringIntegers() {
        JsonObject numeric = new JsonObject();
        numeric.addProperty("slot", 2);
        JsonObject legacy = new JsonObject();
        legacy.addProperty("slot", "3");
        JsonObject decimal = new JsonObject();
        decimal.addProperty("slot", 2.5);

        assertEquals(2, GameServer.requireCompatibleInt(numeric, "slot", 1, 10));
        assertEquals(3, GameServer.requireCompatibleInt(legacy, "slot", 1, 10));
        assertThrows(IllegalArgumentException.class,
                () -> GameServer.requireCompatibleInt(decimal, "slot", 1, 10));
    }

    @Test
    void blankControlTargetFallsBackInsteadOfBecomingInvalidPacket() {
        JsonObject blank = new JsonObject();
        blank.addProperty("targetId", "");
        JsonObject missing = new JsonObject();
        JsonObject explicit = new JsonObject();
        explicit.addProperty("targetId", "bot-7");
        JsonObject invalid = new JsonObject();
        invalid.addProperty("targetId", 7);

        assertFalse(GameServer.validateControlBotRequest(blank).has("targetId"));
        assertFalse(GameServer.validateControlBotRequest(missing).has("targetId"));
        assertEquals("bot-7",
                GameServer.validateControlBotRequest(explicit).get("targetId").getAsString());
        assertThrows(IllegalArgumentException.class,
                () -> GameServer.validateControlBotRequest(invalid));
    }

    @Test
    void smallUpdateCarriesBotControlLifecycleState() {
        JsonObject deadSpectator = new JsonObject();
        GameState.addControlStateToSmallUpdate(deadSpectator, false, "TEAM_SPECTATE", null);
        assertFalse(deadSpectator.get("isAlive").getAsBoolean());
        assertEquals("TEAM_SPECTATE", deadSpectator.get("spectatorMode").getAsString());
        assertTrue(deadSpectator.get("spectatorTargetId").isJsonNull());

        JsonObject controlling = new JsonObject();
        GameState.addControlStateToSmallUpdate(controlling, true, "CONTROLLING_BOT", "bot-7");
        assertTrue(controlling.get("isAlive").getAsBoolean());
        assertEquals("CONTROLLING_BOT", controlling.get("spectatorMode").getAsString());
        assertEquals("bot-7", controlling.get("spectatorTargetId").getAsString());
    }
}
