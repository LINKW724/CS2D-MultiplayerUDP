package cs2d.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Captures timestamped input independently from JavaFX rendering and keeps a
 * small retransmission window until the authoritative server acknowledges it.
 */
final class SubtickInputTransmitter {
    static final int BUTTON_FORWARD = 1 << 0;
    static final int BUTTON_BACK = 1 << 1;
    static final int BUTTON_LEFT = 1 << 2;
    static final int BUTTON_RIGHT = 1 << 3;
    static final int BUTTON_FIRE = 1 << 4;
    static final int BUTTON_WALK = 1 << 5;
    static final int BUTTON_INTERACT = 1 << 6;
    static final int BUTTON_UNDERHAND = 1 << 7;

    static final int SUB_TICK_MAX = 65_535;
    /** 16条约覆盖267ms，并确保详细JSON仍能放进服务端4096字节接收缓冲。 */
    static final int MAX_COMMANDS_PER_BATCH = 16;
    private static final int MAX_PENDING_COMMANDS = 64;

    private final long inputIntervalNanos;
    private final Deque<InputSample> pending = new ArrayDeque<>();
    private long timeOriginNanos = Long.MIN_VALUE;
    private long nextSequence;
    private long nextBatchSequence;
    private long acknowledgedSequence = -1;
    private int lastButtons;

    SubtickInputTransmitter(int inputRate) {
        if (inputRate <= 0 || inputRate > 1_000)
            throw new IllegalArgumentException("inputRate out of range");
        inputIntervalNanos = 1_000_000_000L / inputRate;
    }

    synchronized JsonObject captureAndBuildBatch(long nowNanos, double angle, int buttons) {
        capture(nowNanos, angle, buttons);
        return buildBatch(nowNanos);
    }

    private void capture(long nowNanos, double angle, int buttons) {
        if (!Double.isFinite(angle))
            return;
        if (timeOriginNanos == Long.MIN_VALUE)
            timeOriginNanos = nowNanos;

        long elapsed = Math.max(0L, nowNanos - timeOriginNanos);
        long clientTick = elapsed / inputIntervalNanos;
        long remainder = elapsed % inputIntervalNanos;
        int subTick = (int) Math.min(SUB_TICK_MAX,
                Math.round(remainder * (double) SUB_TICK_MAX / inputIntervalNanos));
        int pressed = buttons & ~lastButtons;
        int released = lastButtons & ~buttons;
        lastButtons = buttons;

        pending.addLast(new InputSample(nextSequence++, clientTick, subTick, elapsed,
                angle, buttons, pressed, released));
        while (pending.size() > MAX_PENDING_COMMANDS)
            pending.removeFirst();
    }

    private JsonObject buildBatch(long nowNanos) {
        JsonObject batch = new JsonObject();
        batch.addProperty("type", "inputBatch");
        batch.addProperty("batch", nextBatchSequence++);
        batch.addProperty("time",
                timeOriginNanos == Long.MIN_VALUE ? 0L : Math.max(0L, nowNanos - timeOriginNanos));

        List<InputSample> samples = new ArrayList<>(pending);
        int fromIndex = Math.max(0, samples.size() - MAX_COMMANDS_PER_BATCH);
        JsonArray commands = new JsonArray();
        for (int index = fromIndex; index < samples.size(); index++) {
            InputSample sample = samples.get(index);
            JsonArray command = new JsonArray();
            command.add(sample.sequence());
            command.add(sample.clientTick());
            command.add(sample.subTick());
            command.add(sample.clientTimeNanos());
            command.add(sample.angle());
            command.add(sample.buttons());
            command.add(sample.pressed());
            command.add(sample.released());
            commands.add(command);
        }
        batch.add("commands", commands);
        return batch;
    }

    synchronized void acknowledge(long sequence) {
        if (sequence <= acknowledgedSequence)
            return;
        acknowledgedSequence = sequence;
        while (!pending.isEmpty() && pending.peekFirst().sequence() <= sequence)
            pending.removeFirst();
    }

    synchronized void reset() {
        pending.clear();
        timeOriginNanos = Long.MIN_VALUE;
        nextSequence = 0;
        nextBatchSequence = 0;
        acknowledgedSequence = -1;
        lastButtons = 0;
    }

    synchronized int pendingCount() {
        return pending.size();
    }

    private record InputSample(long sequence, long clientTick, int subTick, long clientTimeNanos,
            double angle, int buttons, int pressed, int released) {
    }
}
