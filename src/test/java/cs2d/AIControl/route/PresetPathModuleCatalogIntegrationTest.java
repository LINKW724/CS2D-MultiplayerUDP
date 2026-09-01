package cs2d.AIControl.route;

import cs2d.AIControl.A.PresetPathModule;
import cs2d.AIControl.BW.MapViz.DynamicPathfinderVisualizer.PathType;
import cs2d.playerAndAi.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class PresetPathModuleCatalogIntegrationTest {

    @TempDir
    Path temporaryDirectory;

    @AfterEach
    void resetRepository() {
        System.clearProperty(PresetRouteCatalogRepository.ROUTE_DIRECTORY_PROPERTY);
        PresetRouteCatalogRepository.clearForTests();
    }

    @Test
    void legacyPathModulesShareCatalogAndStillCoverEveryRoute() throws Exception {
        Files.writeString(temporaryDirectory.resolve("arena_prePath.json"), """
                {
                  "presetPathsTtoCT": [],
                  "presetPathsCTtoT": [
                    [],
                    [{"x":0,"y":0},{"x":100,"y":0}],
                    [{"x":0,"y":100},{"x":100,"y":100}]
                  ]
                }
                """);
        System.setProperty(PresetRouteCatalogRepository.ROUTE_DIRECTORY_PROPERTY,
                temporaryDirectory.toString());
        PresetPathModule firstModule = new PresetPathModule();
        PresetPathModule secondModule = new PresetPathModule();

        firstModule.loadMap("arena.json");
        secondModule.loadMap("arena");

        assertSame(firstModule.getRouteCatalog(), secondModule.getRouteCatalog());
        Set<Integer> selectedIndices = new HashSet<>();
        PresetPathModule.PresetPathSelection first = firstModule
                .getPresetPathSelection(Player.Team.CT, PathType.TDM_CT_T);
        PresetPathModule.PresetPathSelection second = secondModule
                .getPresetPathSelection(Player.Team.CT, PathType.TDM_CT_T);
        assertNotNull(first);
        assertNotNull(second);
        selectedIndices.add(first.routeIndex());
        selectedIndices.add(second.routeIndex());
        assertEquals(Set.of(1, 2), selectedIndices);

        PresetPathModule.PresetPathSelection commanded = firstModule
                .getPresetPathSelection("TDM_CT_T:2");
        assertNotNull(commanded);
        assertEquals(2, commanded.routeIndex());
        assertEquals(100.0, commanded.keyPoints().get(0).y);
    }
}
