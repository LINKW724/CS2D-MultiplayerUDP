package cs2d.AIControl.route;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PresetRouteCatalogRepositoryTest {

    @TempDir
    Path temporaryDirectory;

    @AfterEach
    void resetRepository() {
        System.clearProperty(PresetRouteCatalogRepository.ROUTE_DIRECTORY_PROPERTY);
        PresetRouteCatalogRepository.clearForTests();
    }

    @Test
    void parsesEveryRouteAndPrecomputesMetadata() {
        RouteCatalog catalog = PresetRouteCatalogRepository.read("arena.json", new StringReader(sampleJson()));

        assertEquals("arena", catalog.mapName());
        assertEquals(3, catalog.routes().size());
        assertEquals(2, catalog.routesFor(RouteType.TDM_T_CT).size());
        RouteDescriptor first = catalog.find("TDM_T_CT:0").orElseThrow();
        assertEquals(200.0, first.length(), 0.001);
        assertTrue(first.overlappingRouteIds().contains("TDM_T_CT:1"));
        assertFalse(first.overlappingRouteIds().contains("TDM_CT_T:0"));
    }

    @Test
    void publishesDeeplyImmutableCatalog() {
        RouteCatalog catalog = PresetRouteCatalogRepository.read("arena", new StringReader(sampleJson()));
        RouteDescriptor first = catalog.routes().get(0);

        assertThrows(UnsupportedOperationException.class, () -> catalog.routes().clear());
        assertThrows(UnsupportedOperationException.class, () -> first.keyPoints().clear());
        assertThrows(UnsupportedOperationException.class, () -> first.overlappingRouteIds().clear());
    }

    @Test
    void loadsMapFileOnceAndSharesSameCatalogInstance() throws Exception {
        Files.writeString(temporaryDirectory.resolve("arena_prePath.json"), sampleJson());
        System.setProperty(PresetRouteCatalogRepository.ROUTE_DIRECTORY_PROPERTY,
                temporaryDirectory.toString());

        RouteCatalog first = PresetRouteCatalogRepository.load("arena.json");
        RouteCatalog second = PresetRouteCatalogRepository.load("arena");

        assertSame(first, second);
        assertEquals(3, first.routes().size());
    }

    @Test
    void doesNotCacheMissingFileSoGeneratedRoutesCanAppearLater() throws Exception {
        System.setProperty(PresetRouteCatalogRepository.ROUTE_DIRECTORY_PROPERTY,
                temporaryDirectory.toString());

        assertTrue(PresetRouteCatalogRepository.load("generated-later").isEmpty());
        Files.writeString(temporaryDirectory.resolve("generated-later_prePath.json"), sampleJson());

        assertEquals(3, PresetRouteCatalogRepository.load("generated-later").routes().size());
    }

    private static String sampleJson() {
        return """
                {
                  "presetPathsTtoCT": [
                    [{"x":0,"y":0},{"x":100,"y":0},{"x":200,"y":0}],
                    [{"x":0,"y":20},{"x":100,"y":20},{"x":200,"y":20}]
                  ],
                  "presetPathsCTtoT": [
                    [{"x":900,"y":900},{"x":1000,"y":900}]
                  ],
                  "presetPathsCTtoA": [],
                  "presetPathsCTtoB": [],
                  "presetPathsTtoA": [],
                  "presetPathsTtoB": []
                }
                """;
    }
}
