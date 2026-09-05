package cs2d.server;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AiDiagnosticsTest {

    @Test
    void summaryIsStableCompactAndSorted() {
        assertEquals("[AI日志汇总/10.0s] grenadeTimeout=12 | perception=465 | unstuck=3",
                AiDiagnostics.formatSummary(10_000L,
                        Map.of("unstuck", 3L, "perception", 465L, "grenadeTimeout", 12L)));
    }
}
