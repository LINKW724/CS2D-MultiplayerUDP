package cs2d.client;

import java.util.Arrays;

/**
 * Pure-CPU visibility-mask rasterizer used by the background fog worker.
 *
 * <p>Each backing buffer remembers its own previous transparent polygon. A new
 * frame restores only the union of the old and new polygon bounds to the solid
 * fog colour, then cuts the new polygon out again. This makes both buffers
 * independently reusable and avoids clearing the complete screen-sized mask.</p>
 */
final class FogMaskRasterizer {
    static final int ANTIALIAS_ROWS = 4;

    record DirtyRegion(int x, int y, int width, int height) {
        static DirtyRegion empty() {
            return new DirtyRegion(0, 0, 0, 0);
        }

        boolean isEmpty() {
            return width <= 0 || height <= 0;
        }

        long pixelCount() {
            return (long) width * height;
        }
    }

    record RenderResult(DirtyRegion dirtyRegion, long touchedPixels) {
    }

    private final int width;
    private final int height;
    private final MaskBuffer[] buffers;

    FogMaskRasterizer(int width, int height) {
        if (width <= 0 || height <= 0)
            throw new IllegalArgumentException("Fog mask dimensions must be positive");
        this.width = width;
        this.height = height;
        this.buffers = new MaskBuffer[] {
                new MaskBuffer(width, height),
                new MaskBuffer(width, height)
        };
    }

    int width() {
        return width;
    }

    int height() {
        return height;
    }

    int[] pixels(int bufferIndex) {
        return buffer(bufferIndex).pixels;
    }

    RenderResult render(int bufferIndex, double[] polygonX, double[] polygonY,
            int pointCount, int fogArgbPre) {
        return buffer(bufferIndex).render(polygonX, polygonY, pointCount, fogArgbPre);
    }

    private MaskBuffer buffer(int index) {
        if (index < 0 || index >= buffers.length)
            throw new IllegalArgumentException("Fog buffer index must be 0 or 1");
        return buffers[index];
    }

    private static final class MaskBuffer {
        private final int width;
        private final int height;
        private final int[] pixels;
        private final int[] coverage;
        private double[] intersections = new double[64];
        private DirtyRegion previousHole = DirtyRegion.empty();
        private boolean initialized;
        private int fogArgbPre;

        private MaskBuffer(int width, int height) {
            this.width = width;
            this.height = height;
            this.pixels = new int[width * height];
            this.coverage = new int[width];
        }

        private RenderResult render(double[] polygonX, double[] polygonY,
                int requestedPointCount, int newFogArgbPre) {
            int pointCount = Math.max(0, Math.min(requestedPointCount,
                    Math.min(polygonX == null ? 0 : polygonX.length,
                            polygonY == null ? 0 : polygonY.length)));
            DirtyRegion newHole = pointCount >= 3
                    ? polygonBounds(polygonX, polygonY, pointCount, width, height)
                    : DirtyRegion.empty();
            DirtyRegion dirty;
            if (!initialized || newFogArgbPre != fogArgbPre) {
                dirty = new DirtyRegion(0, 0, width, height);
            } else {
                dirty = union(previousHole, newHole, width, height);
            }

            if (!dirty.isEmpty())
                fillRegion(dirty, newFogArgbPre);
            if (!newHole.isEmpty())
                cutTransparentPolygon(polygonX, polygonY, pointCount, newHole, newFogArgbPre);

            initialized = true;
            fogArgbPre = newFogArgbPre;
            previousHole = newHole;
            long touched = dirty.pixelCount() + newHole.pixelCount();
            return new RenderResult(dirty, touched);
        }

        private void fillRegion(DirtyRegion region, int argbPre) {
            int maxX = region.x + region.width;
            int maxY = region.y + region.height;
            for (int y = region.y; y < maxY; y++)
                Arrays.fill(pixels, y * width + region.x, y * width + maxX, argbPre);
        }

        /**
         * Four vertical sub-samples plus exact horizontal interval coverage keep
         * polygon edges antialiased without invoking JavaFX/Marlin.
         */
        private void cutTransparentPolygon(double[] polygonX, double[] polygonY, int pointCount,
                DirtyRegion bounds, int argbPre) {
            ensureIntersectionCapacity(pointCount);
            int minX = bounds.x;
            int maxXExclusive = bounds.x + bounds.width;
            int maxYExclusive = bounds.y + bounds.height;
            for (int pixelY = bounds.y; pixelY < maxYExclusive; pixelY++) {
                Arrays.fill(coverage, minX, maxXExclusive, 0);
                for (int sample = 0; sample < ANTIALIAS_ROWS; sample++) {
                    double sampleY = pixelY + (sample + 0.5) / ANTIALIAS_ROWS;
                    int count = collectIntersections(polygonX, polygonY, pointCount, sampleY);
                    Arrays.sort(intersections, 0, count);
                    for (int i = 0; i + 1 < count; i += 2) {
                        double left = Math.max(minX, intersections[i]);
                        double right = Math.min(maxXExclusive, intersections[i + 1]);
                        if (right <= left)
                            continue;
                        int firstPixel = Math.max(minX, (int) Math.floor(left));
                        int lastPixel = Math.min(maxXExclusive - 1,
                                (int) Math.floor(Math.nextDown(right)));
                        for (int x = firstPixel; x <= lastPixel; x++) {
                            double overlap = Math.min(x + 1.0, right) - Math.max(x, left);
                            if (overlap > 0.0)
                                coverage[x] += (int) Math.round(overlap * 255.0 / ANTIALIAS_ROWS);
                        }
                    }
                }

                int rowOffset = pixelY * width;
                for (int x = minX; x < maxXExclusive; x++) {
                    int insideCoverage = Math.min(255, coverage[x]);
                    if (insideCoverage != 0)
                        pixels[rowOffset + x] = scalePremultiplied(argbPre, 255 - insideCoverage);
                }
            }
        }

        private int collectIntersections(double[] polygonX, double[] polygonY,
                int pointCount, double sampleY) {
            int count = 0;
            int previous = pointCount - 1;
            for (int current = 0; current < pointCount; current++) {
                double y1 = polygonY[previous];
                double y2 = polygonY[current];
                // Half-open edge rule prevents double-counting shared vertices.
                if ((y1 <= sampleY && y2 > sampleY) || (y2 <= sampleY && y1 > sampleY)) {
                    double x1 = polygonX[previous];
                    double x2 = polygonX[current];
                    intersections[count++] = x1 + (sampleY - y1) * (x2 - x1) / (y2 - y1);
                }
                previous = current;
            }
            return count;
        }

        private void ensureIntersectionCapacity(int required) {
            if (intersections.length < required)
                intersections = new double[Math.max(required, intersections.length * 2)];
        }
    }

    static DirtyRegion polygonBounds(double[] x, double[] y, int pointCount,
            int width, int height) {
        double minX = Double.POSITIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < pointCount; i++) {
            if (!Double.isFinite(x[i]) || !Double.isFinite(y[i]))
                continue;
            minX = Math.min(minX, x[i]);
            minY = Math.min(minY, y[i]);
            maxX = Math.max(maxX, x[i]);
            maxY = Math.max(maxY, y[i]);
        }
        if (!Double.isFinite(minX) || !Double.isFinite(minY))
            return DirtyRegion.empty();
        int left = clamp((int) Math.floor(minX) - 1, 0, width);
        int top = clamp((int) Math.floor(minY) - 1, 0, height);
        int right = clamp((int) Math.ceil(maxX) + 1, 0, width);
        int bottom = clamp((int) Math.ceil(maxY) + 1, 0, height);
        return right > left && bottom > top
                ? new DirtyRegion(left, top, right - left, bottom - top)
                : DirtyRegion.empty();
    }

    static DirtyRegion union(DirtyRegion a, DirtyRegion b, int width, int height) {
        if (a == null || a.isEmpty())
            return b == null ? DirtyRegion.empty() : b;
        if (b == null || b.isEmpty())
            return a;
        int left = clamp(Math.min(a.x, b.x), 0, width);
        int top = clamp(Math.min(a.y, b.y), 0, height);
        int right = clamp(Math.max(a.x + a.width, b.x + b.width), 0, width);
        int bottom = clamp(Math.max(a.y + a.height, b.y + b.height), 0, height);
        return new DirtyRegion(left, top, Math.max(0, right - left), Math.max(0, bottom - top));
    }

    private static int scalePremultiplied(int argbPre, int scale) {
        if (scale >= 255)
            return argbPre;
        if (scale <= 0)
            return 0;
        int a = ((argbPre >>> 24) * scale + 127) / 255;
        int r = (((argbPre >>> 16) & 0xff) * scale + 127) / 255;
        int g = (((argbPre >>> 8) & 0xff) * scale + 127) / 255;
        int b = ((argbPre & 0xff) * scale + 127) / 255;
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
