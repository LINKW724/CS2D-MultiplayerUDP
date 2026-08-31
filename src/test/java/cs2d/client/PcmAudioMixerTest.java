package cs2d.client;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class PcmAudioMixerTest {
    @Test
    void reusesFixedPcmBlocksAcrossMixerAndWriter() throws Exception {
        PcmAudioMixer.PcmBlockRing ring = new PcmAudioMixer.PcmBlockRing(3, 2, 1024);
        byte[] first = ring.acquireForMix();
        byte[] second = ring.acquireForMix();

        ring.publish(first);
        ring.publish(second);
        assertEquals(2, ring.readyCount());
        assertEquals(1, ring.freeCount());

        byte[] writtenFirst = ring.pollForWrite();
        assertSame(first, writtenFirst);
        ring.recycle(writtenFirst);
        byte[] writtenSecond = ring.pollForWrite();
        assertSame(second, writtenSecond);
        ring.recycle(writtenSecond);

        assertEquals(0, ring.readyCount());
        assertEquals(3, ring.freeCount());
    }

    @Test
    void decodesAndEncodesLittleEndianPcmWithoutQualityLoss() {
        byte[] input = { 0x34, 0x12, 0x00, (byte) 0x80, (byte) 0xff, 0x7f };
        PcmAudioMixer.Sound sound = PcmAudioMixer.decodeLittleEndian16(input);
        int[] mix = { sound.interleavedSamples()[0], sound.interleavedSamples()[1],
                sound.interleavedSamples()[2] };
        byte[] encoded = new byte[input.length];

        PcmAudioMixer.encodeLittleEndian16(mix, encoded);

        assertArrayEquals(input, encoded);
    }

    @Test
    void mixesConcurrentVoicesAndSaturatesOnlyAtPcmBoundary() {
        PcmAudioMixer.Sound first = new PcmAudioMixer.Sound(new short[] { 20_000, -20_000, 10_000, -10_000 });
        PcmAudioMixer.Sound second = new PcmAudioMixer.Sound(new short[] { 20_000, -20_000, 10_000, -10_000 });
        List<PcmAudioMixer.Voice> voices = new ArrayList<>();
        voices.add(new PcmAudioMixer.Voice(first, 1.0f));
        voices.add(new PcmAudioMixer.Voice(second, 1.0f));
        int[] mixed = new int[4];

        PcmAudioMixer.mixVoices(voices, mixed, 2);
        byte[] encoded = new byte[8];
        PcmAudioMixer.encodeLittleEndian16(mixed, encoded);

        assertEquals(40_000, mixed[0]);
        assertEquals(-40_000, mixed[1]);
        assertEquals(0x7fff, (encoded[0] & 0xff) | ((encoded[1] & 0xff) << 8));
        assertEquals(0x8000, (encoded[2] & 0xff) | ((encoded[3] & 0xff) << 8));
        assertTrue(voices.isEmpty());
    }

    @Test
    void decodesEveryBundledWavIntoTheSharedMixerFormat() throws Exception {
        URL soundsUrl = PcmAudioMixerTest.class.getResource("/sounds");
        assertTrue(soundsUrl != null && "file".equals(soundsUrl.getProtocol()));
        Path sounds = Paths.get(soundsUrl.toURI());
        PcmAudioMixer mixer = new PcmAudioMixer();
        int decoded = 0;
        try (var files = Files.walk(sounds)) {
            for (Path file : files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase().endsWith(".wav")).toList()) {
                try {
                    PcmAudioMixer.Sound sound = mixer.load(file.toUri().toURL());
                    assertTrue(sound.frameCount() > 0, file.toString());
                    decoded++;
                } catch (Exception e) {
                    fail("Cannot decode bundled WAV " + file + ": " + e.getMessage());
                }
            }
        }
        assertTrue(decoded > 20, "Expected the bundled weapon and effect WAV library");
    }
}
