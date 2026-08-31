package cs2d.AIControl.A;

import cs2d.AIControl.BW.MapViz.DynamicPathfinderVisualizer.PathType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PresetRouteAllocatorTest {

    @AfterEach
    void resetAllocator() {
        PresetRouteAllocator.resetForTests();
    }

    @Test
    void distributesEachCompleteWaveEvenlyAcrossRoutes() {
        int[] counts = new int[5];

        for (int i = 0; i < 25; i++) {
            counts[PresetRouteAllocator.nextRouteIndex("mirage", PathType.TDM_CT_T, counts.length)]++;
        }

        for (int count : counts) {
            assertEquals(5, count);
        }
    }

    @Test
    void keepsDifferentMapsAndPathTypesIndependent() {
        int mirageFirst = PresetRouteAllocator.nextRouteIndex("mirage", PathType.TDM_CT_T, 5);
        for (int i = 0; i < 17; i++) {
            PresetRouteAllocator.nextRouteIndex("mirage", PathType.TDM_T_CT, 5);
            PresetRouteAllocator.nextRouteIndex("boom1", PathType.TDM_CT_T, 5);
        }
        int mirageSecond = PresetRouteAllocator.nextRouteIndex("mirage", PathType.TDM_CT_T, 5);

        assertEquals((mirageFirst + 1) % 5, mirageSecond);
    }

    @Test
    void remainsBalancedWhenManyAiSpawnConcurrently() throws Exception {
        int routeCount = 5;
        int allocationCount = 1_000;
        int[] counts = new int[routeCount];
        ExecutorService executor = Executors.newFixedThreadPool(8);

        try {
            List<Callable<Integer>> tasks = new ArrayList<>();
            for (int i = 0; i < allocationCount; i++) {
                tasks.add(() -> PresetRouteAllocator.nextRouteIndex("mirage", PathType.TDM_T_CT, routeCount));
            }
            List<Future<Integer>> results = executor.invokeAll(tasks);
            for (Future<Integer> result : results) {
                counts[result.get()]++;
            }
        } finally {
            executor.shutdownNow();
        }

        for (int count : counts) {
            assertEquals(allocationCount / routeCount, count);
        }
    }

    @Test
    void rejectsEmptyRouteCollections() {
        assertThrows(IllegalArgumentException.class,
                () -> PresetRouteAllocator.nextRouteIndex("mirage", PathType.TDM_T_CT, 0));
    }
}
