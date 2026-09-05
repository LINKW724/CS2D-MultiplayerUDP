package cs2d.AIControl.movement;

import java.util.List;

/** Final immutable locomotion decision consumed by the input executor. */
public record MovementDecision(
        List<String> keys,
        List<String> contributingSources,
        int suppressedOpposingInputs) {

    public MovementDecision {
        keys = keys == null ? List.of() : List.copyOf(keys);
        contributingSources = contributingSources == null ? List.of() : List.copyOf(contributingSources);
        suppressedOpposingInputs = Math.max(0, suppressedOpposingInputs);
    }

    public static MovementDecision idle() {
        return new MovementDecision(List.of(), List.of(), 0);
    }
}
