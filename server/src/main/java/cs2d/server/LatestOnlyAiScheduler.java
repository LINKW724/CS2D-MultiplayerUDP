package cs2d.server;

import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;

/**
 * Serializes work per AI and keeps at most one not-yet-started decision per AI.
 * A newer world frame replaces an older pending frame instead of building an
 * unbounded history that is already obsolete when it is finally evaluated.
 */
final class LatestOnlyAiScheduler implements AutoCloseable {
    record Stats(long submitted, long executed, long coalesced, long rejected,
            int queued, int running, int trackedAis,
            double averageQueueWaitMs, double maximumQueueWaitMs,
            double averageExecutionMs, double maximumExecutionMs) {
    }

    private record WorkItem(long submittedNanos, Runnable task) {
    }

    private static final class Slot {
        private final String aiId;
        private final AtomicReference<WorkItem> latest = new AtomicReference<>();
        /** true while this slot is queued or one worker is executing it. */
        private final AtomicBoolean claimed = new AtomicBoolean();

        private Slot(String aiId) {
            this.aiId = aiId;
        }
    }

    private final ConcurrentHashMap<String, Slot> slots = new ConcurrentHashMap<>();
    private final ArrayBlockingQueue<Slot> readyQueue;
    private final Thread[] workers;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final AtomicInteger activeWorkers = new AtomicInteger();
    private final LongAdder submitted = new LongAdder();
    private final LongAdder executed = new LongAdder();
    private final LongAdder coalesced = new LongAdder();
    private final LongAdder rejected = new LongAdder();
    private final LongAdder queueWaitNanos = new LongAdder();
    private final LongAdder executionNanos = new LongAdder();
    private final AtomicLong maximumQueueWaitNanos = new AtomicLong();
    private final AtomicLong maximumExecutionNanos = new AtomicLong();

    LatestOnlyAiScheduler(int threadCount, int queueCapacity) {
        if (threadCount <= 0)
            throw new IllegalArgumentException("threadCount must be positive");
        if (queueCapacity < threadCount)
            throw new IllegalArgumentException("queueCapacity must be at least threadCount");

        readyQueue = new ArrayBlockingQueue<>(queueCapacity);
        workers = new Thread[threadCount];
        for (int i = 0; i < workers.length; i++) {
            Thread worker = new Thread(this::workerLoop, "AI-Decision-" + (i + 1));
            worker.setPriority(Thread.NORM_PRIORITY);
            workers[i] = worker;
            worker.start();
        }
    }

    void submitLatest(String aiId, Runnable task) {
        if (!running.get() || aiId == null || task == null)
            return;

        submitted.increment();
        Slot slot = slots.computeIfAbsent(aiId, Slot::new);
        WorkItem previous = slot.latest.getAndSet(new WorkItem(System.nanoTime(), task));
        if (previous != null)
            coalesced.increment();
        claimAndQueue(slot);
    }

    /** Drops pending work belonging to dead, player-controlled, or removed AIs. */
    void retainOnly(Set<String> activeAiIds) {
        slots.forEach((aiId, slot) -> {
            if (activeAiIds.contains(aiId))
                return;
            slot.latest.set(null);
            if (!slot.claimed.get())
                slots.remove(aiId, slot);
        });
    }

    void clearPending() {
        slots.values().forEach(slot -> slot.latest.set(null));
    }

    boolean isShutdown() {
        return !running.get();
    }

    Stats snapshotAndResetStats() {
        long executedCount = executed.sumThenReset();
        long waitNanos = queueWaitNanos.sumThenReset();
        long workNanos = executionNanos.sumThenReset();
        return new Stats(
                submitted.sumThenReset(),
                executedCount,
                coalesced.sumThenReset(),
                rejected.sumThenReset(),
                readyQueue.size(),
                activeWorkers.get(),
                slots.size(),
                nanosToAverageMillis(waitNanos, executedCount),
                maximumQueueWaitNanos.getAndSet(0L) / 1_000_000.0,
                nanosToAverageMillis(workNanos, executedCount),
                maximumExecutionNanos.getAndSet(0L) / 1_000_000.0);
    }

    private void claimAndQueue(Slot slot) {
        if (!slot.claimed.compareAndSet(false, true))
            return;
        if (!readyQueue.offer(slot)) {
            slot.claimed.set(false);
            rejected.increment();
        }
    }

    private void workerLoop() {
        while (running.get() || !readyQueue.isEmpty()) {
            Slot slot = null;
            try {
                slot = readyQueue.poll(100, TimeUnit.MILLISECONDS);
                if (slot == null)
                    continue;
                WorkItem work = slot.latest.getAndSet(null);
                if (work != null)
                    execute(work);
            } catch (InterruptedException interrupted) {
                if (!running.get())
                    break;
            } finally {
                if (slot != null) {
                    slot.claimed.set(false);
                    if (slot.latest.get() != null && running.get())
                        claimAndQueue(slot);
                    else if (slot.latest.get() == null)
                        slots.remove(slot.aiId, slot);
                }
            }
        }
    }

    private void execute(WorkItem work) {
        long started = System.nanoTime();
        long wait = Math.max(0L, started - work.submittedNanos());
        queueWaitNanos.add(wait);
        maximumQueueWaitNanos.accumulateAndGet(wait, Math::max);
        activeWorkers.incrementAndGet();
        try {
            work.task().run();
        } catch (Throwable failure) {
            // AIService owns per-AI error handling. This guard keeps a worker alive
            // if an Error or an unexpected wrapper task escapes that boundary.
            System.err.println("AI scheduler task failed: " + failure.getMessage());
        } finally {
            long duration = Math.max(0L, System.nanoTime() - started);
            executionNanos.add(duration);
            maximumExecutionNanos.accumulateAndGet(duration, Math::max);
            executed.increment();
            activeWorkers.decrementAndGet();
        }
    }

    private static double nanosToAverageMillis(long nanos, long count) {
        return count == 0L ? 0.0 : nanos / 1_000_000.0 / count;
    }

    @Override
    public void close() {
        if (!running.compareAndSet(true, false))
            return;
        clearPending();
        readyQueue.clear();
        for (Thread worker : workers)
            worker.interrupt();
    }
}
