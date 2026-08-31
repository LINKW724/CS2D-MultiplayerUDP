package cs2d.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.awt.geom.Point2D;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SubtickInputBufferTest {

    @Test
    void validatesAndExecutesCommandsBySubtickWhileDeduplicatingRetransmits() {
        JsonObject batch = new JsonObject();
        JsonArray commands = new JsonArray();
        commands.add(command(2, 10, 50_000, SubtickInputCommand.BUTTON_FIRE));
        commands.add(command(1, 10, 1_000, SubtickInputCommand.BUTTON_FORWARD));
        batch.add("commands", commands);

        List<SubtickInputCommand> validated = GameServer.validateSubtickInputBatch(batch);
        SubtickInputBuffer buffer = new SubtickInputBuffer();
        buffer.offerAll(validated);
        buffer.offerAll(validated);

        List<SubtickInputCommand> drained = buffer.drainOrdered();
        assertEquals(List.of(1L, 2L), drained.stream().map(SubtickInputCommand::sequence).toList());
        assertEquals(2, buffer.duplicateCommands());
        assertEquals(2L, buffer.lastAppliedSequence());
    }

    @Test
    void rejectsUnknownButtonBitsAndOutOfRangeSubtick() {
        JsonObject unknownButton = new JsonObject();
        JsonArray commands = new JsonArray();
        commands.add(command(0, 0, 0, 1 << 20));
        unknownButton.add("commands", commands);
        assertThrows(IllegalArgumentException.class,
                () -> GameServer.validateSubtickInputBatch(unknownButton));

        JsonObject invalidSubtick = new JsonObject();
        JsonArray command = command(0, 0, 70_000, 0);
        JsonArray invalidCommands = new JsonArray();
        invalidCommands.add(command);
        invalidSubtick.add("commands", invalidCommands);
        assertThrows(IllegalArgumentException.class,
                () -> GameServer.validateSubtickInputBatch(invalidSubtick));
    }

    @Test
    void movementPressedThreeQuartersIntoTickOnlyContributesLastQuarter() {
        SubtickInputCommand latePress = new SubtickInputCommand(1, 4,
                (int) Math.round(SubtickInputCommand.MAX_SUB_TICK * 0.75), 1_000_000L,
                0.0, SubtickInputCommand.BUTTON_FORWARD,
                SubtickInputCommand.BUTTON_FORWARD, 0);

        Point2D.Double blend = GameState.calculateSubtickMovementBlend(List.of(latePress), 0);
        assertEquals(0.0, blend.x, 1e-3);
        assertEquals(-0.25, blend.y, 1e-3);
    }

    private static JsonArray command(long sequence, long clientTick, int subTick, int buttons) {
        JsonArray command = new JsonArray();
        command.add(sequence);
        command.add(clientTick);
        command.add(subTick);
        command.add(clientTick * 16_666_666L);
        command.add(1.25);
        command.add(buttons);
        command.add(buttons);
        command.add(0);
        return command;
    }
}
