package cs2d.server;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.NavigableMap;
import java.util.TreeMap;

/**
 * Game-thread-owned, bounded and duplicate-safe input buffer for one player.
 */
final class SubtickInputBuffer {
    static final int MAX_PENDING_COMMANDS = 128;
    private static final Comparator<SubtickInputCommand> EXECUTION_ORDER = Comparator
            .comparingLong(SubtickInputCommand::clientTick)
            .thenComparingInt(SubtickInputCommand::subTick)
            .thenComparingLong(SubtickInputCommand::sequence);

    private final NavigableMap<Long, SubtickInputCommand> pendingBySequence = new TreeMap<>();
    private long lastAppliedSequence = -1;
    private long duplicateCommands;
    private long overflowCommands;

    void offerAll(List<SubtickInputCommand> commands) {
        for (SubtickInputCommand command : commands) {
            if (command.sequence() <= lastAppliedSequence || pendingBySequence.containsKey(command.sequence())) {
                duplicateCommands++;
                continue;
            }
            pendingBySequence.put(command.sequence(), command);
            while (pendingBySequence.size() > MAX_PENDING_COMMANDS) {
                pendingBySequence.pollFirstEntry();
                overflowCommands++;
            }
        }
    }

    List<SubtickInputCommand> drainOrdered() {
        if (pendingBySequence.isEmpty())
            return List.of();
        List<SubtickInputCommand> drained = new ArrayList<>(pendingBySequence.values());
        pendingBySequence.clear();
        drained.sort(EXECUTION_ORDER);
        for (SubtickInputCommand command : drained)
            lastAppliedSequence = Math.max(lastAppliedSequence, command.sequence());
        return drained;
    }

    long lastAppliedSequence() {
        return lastAppliedSequence;
    }

    int pendingCount() {
        return pendingBySequence.size();
    }

    long duplicateCommands() {
        return duplicateCommands;
    }

    long overflowCommands() {
        return overflowCommands;
    }
}
