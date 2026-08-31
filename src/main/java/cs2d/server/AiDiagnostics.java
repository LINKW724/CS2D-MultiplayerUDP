package cs2d.server;

import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * AI高频诊断日志门控。默认只输出周期汇总，避免IDEA控制台成为同机客户端的调度噪声。
 */
public final class AiDiagnostics {
    private static final boolean VERBOSE = Boolean.getBoolean("cs2d.ai.verbose");
    private static final boolean SUMMARY_ENABLED =
            Boolean.parseBoolean(System.getProperty("cs2d.ai.logSummary", "true"));
    private static final long SUMMARY_INTERVAL_MS = Math.max(1_000L,
            Long.getLong("cs2d.ai.logSummaryMs", 10_000L));
    private static final ConcurrentHashMap<String, LongAdder> SUPPRESSED = new ConcurrentHashMap<>();
    private static final AtomicLong NEXT_SUMMARY_AT =
            new AtomicLong(System.currentTimeMillis() + SUMMARY_INTERVAL_MS);

    private AiDiagnostics() {
    }

    public static void trace(String category, Consumer<String> logger, Supplier<String> message) {
        if (logger == null)
            return;
        if (VERBOSE) {
            logger.accept(message.get());
            return;
        }
        SUPPRESSED.computeIfAbsent(category, ignored -> new LongAdder()).increment();
        maybePublishSummary(logger, System.currentTimeMillis());
    }

    private static void maybePublishSummary(Consumer<String> logger, long now) {
        if (!SUMMARY_ENABLED)
            return;
        long scheduled = NEXT_SUMMARY_AT.get();
        if (now < scheduled || !NEXT_SUMMARY_AT.compareAndSet(scheduled, now + SUMMARY_INTERVAL_MS))
            return;

        Map<String, Long> counts = new TreeMap<>();
        SUPPRESSED.forEach((category, counter) -> {
            long count = counter.sumThenReset();
            if (count > 0)
                counts.put(category, count);
        });
        if (!counts.isEmpty())
            logger.accept(formatSummary(SUMMARY_INTERVAL_MS, counts));
    }

    static String formatSummary(long intervalMs, Map<String, Long> counts) {
        StringBuilder summary = new StringBuilder("[AI日志汇总/")
                .append(intervalMs / 1_000.0)
                .append("s] ");
        boolean first = true;
        for (Map.Entry<String, Long> entry : new TreeMap<>(counts).entrySet()) {
            if (!first)
                summary.append(" | ");
            summary.append(entry.getKey()).append('=').append(entry.getValue());
            first = false;
        }
        return summary.toString();
    }
}
