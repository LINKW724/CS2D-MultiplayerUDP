package cs2d.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FogMaskRasterizerTest {
    private static final int FOG = 0xd91a202c;

    @Test
    void polygonCutsAnAntialiasedTransparentHoleWithoutTouchingOutsidePixels() {
        FogMaskRasterizer rasterizer = new FogMaskRasterizer(64, 48);
        double[] x = { 10.25, 50.75, 32.5 };
        double[] y = { 40.5, 40.5, 8.25 };

        FogMaskRasterizer.RenderResult result = rasterizer.render(0, x, y, 3, FOG);
        int[] pixels = rasterizer.pixels(0);

        assertEquals(64 * 48, result.dirtyRegion().pixelCount());
        assertEquals(FOG, pixels[2 * 64 + 2]);
        assertEquals(0, pixels[30 * 64 + 32]);
        int edge = pixels[40 * 64 + 10];
        assertNotEquals(0, edge);
        assertNotEquals(FOG, edge);
    }

    @Test
    void subsequentFrameRestoresOnlyUnionOfOldAndNewHoleBounds() {
        FogMaskRasterizer rasterizer = new FogMaskRasterizer(200, 120);
        double[] firstX = { 20, 80, 50 };
        double[] firstY = { 90, 90, 20 };
        double[] secondX = { 35, 95, 65 };
        double[] secondY = { 90, 90, 20 };

        rasterizer.render(0, firstX, firstY, 3, FOG);
        FogMaskRasterizer.RenderResult second = rasterizer.render(0, secondX, secondY, 3, FOG);

        assertTrue(second.dirtyRegion().pixelCount() < 200L * 120L);
        assertEquals(FOG, rasterizer.pixels(0)[60 * 200 + 22]);
        assertEquals(0, rasterizer.pixels(0)[60 * 200 + 65]);
    }

    @Test
    void twoBuffersRetainIndependentDirtyHistories() {
        FogMaskRasterizer rasterizer = new FogMaskRasterizer(80, 60);
        double[] x = { 10, 70, 40 };
        double[] y = { 50, 50, 10 };

        rasterizer.render(0, x, y, 3, FOG);
        rasterizer.render(1, x, y, 3, FOG);
        FogMaskRasterizer.RenderResult next = rasterizer.render(0,
                new double[] { 15, 75, 45 }, y, 3, FOG);

        assertTrue(next.dirtyRegion().pixelCount() < 80L * 60L);
        assertEquals(0, rasterizer.pixels(1)[35 * 80 + 40]);
    }
}
