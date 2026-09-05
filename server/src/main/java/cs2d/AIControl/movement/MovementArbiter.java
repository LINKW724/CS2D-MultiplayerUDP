package cs2d.AIControl.movement;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import cs2d.AIControl.movement.MovementIntent.CompositionMode;

/**
 * Pure decision boundary between AI thinking and keyboard execution.
 *
 * <p>Exactly one BASE intent is selected. STEERING intents may add a free axis
 * or reinforce the selected direction, but can never reverse it. The highest
 * priority EXCLUSIVE intent owns locomotion for that decision.</p>
 */
public final class MovementArbiter {

    public MovementDecision decide(Collection<MovementIntent> proposedIntents) {
        if (proposedIntents == null || proposedIntents.isEmpty()) {
            return MovementDecision.idle();
        }

        List<MovementIntent> intents = proposedIntents.stream()
                .filter(intent -> intent != null
                        && (intent.hasMovement() || intent.mode() == CompositionMode.EXCLUSIVE))
                .sorted(Comparator.comparingInt(MovementIntent::priority).reversed()
                        .thenComparing(MovementIntent::sourceId))
                .toList();
        if (intents.isEmpty()) {
            return MovementDecision.idle();
        }

        MovementIntent exclusive = intents.stream()
                .filter(intent -> intent.mode() == CompositionMode.EXCLUSIVE)
                .findFirst()
                .orElse(null);
        if (exclusive != null) {
            AxisState axes = new AxisState();
            axes.applyExclusive(exclusive.keys());
            return axes.toDecision(List.of(exclusive.sourceId()));
        }

        MovementIntent base = intents.stream()
                .filter(intent -> intent.mode() == CompositionMode.BASE)
                .findFirst()
                .orElse(null);
        if (base == null) {
            return MovementDecision.idle();
        }

        AxisState axes = new AxisState();
        Set<String> contributors = new LinkedHashSet<>();
        axes.applyBase(base.keys());
        contributors.add(base.sourceId());

        for (MovementIntent steering : intents) {
            if (steering.mode() != CompositionMode.STEERING) {
                continue;
            }
            if (axes.applySteering(steering.keys())) {
                contributors.add(steering.sourceId());
            }
        }
        return axes.toDecision(new ArrayList<>(contributors));
    }

    private static final class AxisState {
        private int vertical;
        private int horizontal;
        private int suppressed;

        private void applyExclusive(List<String> keys) {
            for (String key : keys) {
                apply(key);
            }
        }

        private void applyBase(List<String> keys) {
            for (String key : keys) {
                apply(key);
            }
        }

        private boolean applySteering(List<String> keys) {
            int beforeVertical = vertical;
            int beforeHorizontal = horizontal;
            for (String key : keys) {
                apply(key);
            }
            return beforeVertical != vertical || beforeHorizontal != horizontal;
        }

        private void apply(String key) {
            int proposedVertical = switch (key) {
                case "W" -> -1;
                case "S" -> 1;
                default -> 0;
            };
            int proposedHorizontal = switch (key) {
                case "A" -> -1;
                case "D" -> 1;
                default -> 0;
            };
            if (proposedVertical != 0) {
                vertical = mergeAxis(vertical, proposedVertical);
            }
            if (proposedHorizontal != 0) {
                horizontal = mergeAxis(horizontal, proposedHorizontal);
            }
        }

        private int mergeAxis(int current, int proposed) {
            if (current == 0 || current == proposed) {
                return proposed;
            }
            suppressed++;
            return current;
        }

        private MovementDecision toDecision(List<String> contributors) {
            List<String> keys = new ArrayList<>(2);
            if (vertical < 0) {
                keys.add("W");
            } else if (vertical > 0) {
                keys.add("S");
            }
            if (horizontal < 0) {
                keys.add("A");
            } else if (horizontal > 0) {
                keys.add("D");
            }
            return new MovementDecision(keys, contributors, suppressed);
        }
    }
}
