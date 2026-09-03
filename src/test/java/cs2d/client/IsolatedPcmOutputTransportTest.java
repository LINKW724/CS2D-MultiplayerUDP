package cs2d.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import org.junit.jupiter.api.Test;

class IsolatedPcmOutputTransportTest {
    @Test
    void acceptsOnlyTheExpectedLoopbackPacketToken() {
        long token = 0x1020_3040_5060_7080L;
        ByteBuffer packet = packet(token, 42);

        assertTrue(IsolatedPcmOutputTransport.isValidHeader(packet, token));
        assertEquals(42, packet.getInt());
    }

    @Test
    void rejectsPacketsFromAnOldAudioWorkerSession() {
        ByteBuffer packet = packet(7L, 42);

        assertTrue(!IsolatedPcmOutputTransport.isValidHeader(packet, 8L));
    }

    @Test
    void audioWorkerRecoversFromShortWritesAndStoppedLines() {
        assertTrue(PcmOutputWorkerMain.shouldRecoverOutput(4096, 2048, 1L, true, true));
        assertTrue(PcmOutputWorkerMain.shouldRecoverOutput(4096, 4096, 1L, true, false));
        assertTrue(!PcmOutputWorkerMain.shouldRecoverOutput(4096, 4096, 1L, true, true));
    }

    private static ByteBuffer packet(long token, int sequence) {
        ByteBuffer packet = ByteBuffer.allocate(IsolatedPcmOutputTransport.HEADER_BYTES)
                .order(ByteOrder.BIG_ENDIAN);
        packet.putInt(IsolatedPcmOutputTransport.MAGIC).putLong(token).putInt(sequence).flip();
        return packet;
    }
}
