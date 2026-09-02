package cs2d.client;

/**
 * Non-blocking boundary between the game mixer and the operating-system audio
 * device. Implementations must never make the render, network or state threads
 * wait for an audio driver.
 */
interface PcmOutputTransport extends AutoCloseable {
    boolean start();

    boolean offer(byte[] pcm, int length);

    long recoveriesThenReset();

    long droppedBatchesThenReset();

    @Override
    void close();
}
