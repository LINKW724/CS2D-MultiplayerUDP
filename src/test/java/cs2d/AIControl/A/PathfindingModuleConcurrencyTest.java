package cs2d.AIControl.A;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Modifier;
import java.util.List;

import org.junit.jupiter.api.Test;

class PathfindingModuleConcurrencyTest {
    @Test
    void rejectsMissingAndOutOfRangePathIndexes() {
        assertFalse(PathfindingModule.isPathIndexUsable(null, 0));
        assertFalse(PathfindingModule.isPathIndexUsable(List.of(), 0));
        assertFalse(PathfindingModule.isPathIndexUsable(List.of("only"), -1));
        assertFalse(PathfindingModule.isPathIndexUsable(List.of("only"), 1));
        assertTrue(PathfindingModule.isPathIndexUsable(List.of("only"), 0));
    }

    @Test
    void pathMutationEntrypointsAreSerializedPerBot() throws Exception {
        assertTrue(Modifier.isSynchronized(PathfindingModule.class.getMethod("reset").getModifiers()));
        assertTrue(Modifier.isSynchronized(PathfindingModule.class
                .getMethod("setTarget", java.awt.geom.Point2D.Double.class).getModifiers()));
        assertTrue(Modifier.isSynchronized(PathfindingModule.class
                .getMethod("update", cs2d.server.AIService.AIWorldView.class).getModifiers()));
    }
}
