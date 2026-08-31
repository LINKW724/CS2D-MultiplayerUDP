package cs2d.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SubtickInputTransmitterTest {

    @Test
    void recordsPressAndReleaseEdgesInsideOneSixtyHertzTick() {
        SubtickInputTransmitter transmitter = new SubtickInputTransmitter(60);
        long start = 1_000_000_000L;

        transmitter.captureAndBuildBatch(start, 0.25, 0);
        transmitter.captureAndBuildBatch(start + 2_000_000L, 0.25,
                SubtickInputTransmitter.BUTTON_FIRE);
        JsonObject batch = transmitter.captureAndBuildBatch(start + 4_000_000L, 0.25, 0);

        JsonArray commands = batch.getAsJsonArray("commands");
        assertEquals(3, commands.size());
        assertEquals(SubtickInputTransmitter.BUTTON_FIRE,
                commands.get(1).getAsJsonArray().get(6).getAsInt());
        assertEquals(SubtickInputTransmitter.BUTTON_FIRE,
                commands.get(2).getAsJsonArray().get(7).getAsInt());
        assertTrue(commands.get(1).getAsJsonArray().get(2).getAsInt()
                < commands.get(2).getAsJsonArray().get(2).getAsInt());
    }

    @Test
    void acknowledgementRemovesOnlyConfirmedCommands() {
        SubtickInputTransmitter transmitter = new SubtickInputTransmitter(60);
        long start = 2_000_000_000L;
        transmitter.captureAndBuildBatch(start, 0.0, 0);
        transmitter.captureAndBuildBatch(start + 17_000_000L, 0.1,
                SubtickInputTransmitter.BUTTON_FORWARD);
        transmitter.captureAndBuildBatch(start + 34_000_000L, 0.2,
                SubtickInputTransmitter.BUTTON_FORWARD);

        transmitter.acknowledge(1);
        assertEquals(1, transmitter.pendingCount());
        transmitter.acknowledge(2);
        assertEquals(0, transmitter.pendingCount());
    }

    @Test
    void maximumRetransmissionBatchFitsServerUdpReceiveBuffer() {
        SubtickInputTransmitter transmitter = new SubtickInputTransmitter(60);
        JsonObject batch = null;
        long start = 3_000_000_000L;
        for (int index = 0; index < 40; index++) {
            batch = transmitter.captureAndBuildBatch(start + index * 16_666_666L,
                    Math.PI, SubtickInputTransmitter.BUTTON_FORWARD
                            | SubtickInputTransmitter.BUTTON_FIRE
                            | SubtickInputTransmitter.BUTTON_WALK);
        }

        assertEquals(SubtickInputTransmitter.MAX_COMMANDS_PER_BATCH,
                batch.getAsJsonArray("commands").size());
        assertTrue(batch.toString().getBytes(StandardCharsets.UTF_8).length < 4_096,
                "input retransmission batch must fit one server UDP receive buffer");
    }
}
