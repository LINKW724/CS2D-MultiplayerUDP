package cs2d.server;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LatestOnlyAiSchedulerTest {
    @Test
    void keepsOnlyLatestPendingFrameAndNeverRunsOneAiConcurrently() throws Exception {
        LatestOnlyAiScheduler scheduler = new LatestOnlyAiScheduler(2, 8);
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch latestFinished = new CountDownLatch(1);
        AtomicInteger value = new AtomicInteger();
        AtomicInteger concurrent = new AtomicInteger();
        AtomicInteger maximumConcurrent = new AtomicInteger();

        try {
            scheduler.submitLatest("bot-1", () -> {
                maximumConcurrent.accumulateAndGet(concurrent.incrementAndGet(), Math::max);
                firstStarted.countDown();
                await(releaseFirst);
                value.set(1);
                concurrent.decrementAndGet();
            });
            assertTrue(firstStarted.await(2, TimeUnit.SECONDS));

            scheduler.submitLatest("bot-1", () -> value.set(2));
            scheduler.submitLatest("bot-1", () -> {
                maximumConcurrent.accumulateAndGet(concurrent.incrementAndGet(), Math::max);
                value.set(3);
                concurrent.decrementAndGet();
                latestFinished.countDown();
            });
            releaseFirst.countDown();

            assertTrue(latestFinished.await(2, TimeUnit.SECONDS));
            assertEquals(3, value.get());
            assertEquals(1, maximumConcurrent.get());
            LatestOnlyAiScheduler.Stats stats = scheduler.snapshotAndResetStats();
            assertEquals(3, stats.submitted());
            assertEquals(2, stats.executed());
            assertEquals(1, stats.coalesced());
        } finally {
            scheduler.close();
        }
    }

    @Test
    void pendingWorkCanBeDroppedForInactiveAi() throws Exception {
        LatestOnlyAiScheduler scheduler = new LatestOnlyAiScheduler(1, 4);
        CountDownLatch blockerStarted = new CountDownLatch(1);
        CountDownLatch releaseBlocker = new CountDownLatch(1);
        AtomicInteger removedAiExecutions = new AtomicInteger();

        try {
            scheduler.submitLatest("blocker", () -> {
                blockerStarted.countDown();
                await(releaseBlocker);
            });
            assertTrue(blockerStarted.await(2, TimeUnit.SECONDS));
            scheduler.submitLatest("removed", removedAiExecutions::incrementAndGet);
            scheduler.retainOnly(java.util.Set.of("blocker"));
            releaseBlocker.countDown();

            assertTrue(org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
                while (scheduler.snapshotAndResetStats().queued() != 0)
                    Thread.sleep(5);
                return true;
            }));
            assertEquals(0, removedAiExecutions.get());
        } finally {
            scheduler.close();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
