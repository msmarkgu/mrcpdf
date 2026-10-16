package com.mrcpdf.quality.model;

/**
 * Sauvola local-adaptive thresholding, published so that mask-stability metrics
 * can be measured from the very same binarization the MRC pipeline performs.
 *
 * <p>notes/gemini-page-readability-score.md §8.0 settles on a "late gate": the
 * mask-stability dimension of the MRC readiness score is computed as a
 * by-product of the Sauvola pass that segmentation already requires, rather
 * than binarizing the page a second time.  That requires the threshold map
 * itself, not just the resulting mask, because the margin metric measures each
 * text pixel's distance to the local threshold.
 *
 * <p>The defaults match {@code ImageSegmenter}'s production configuration
 * (window 15, k 0.20, R 128) so a score computed here describes the same mask
 * the compressor will build.  {@code ImageSegmenter} still owns segmentation;
 * this class exists for scoring and for tests.
 */
public final class SauvolaMask {

    /** Local window side in pixels; matches the compressor default. */
    public static final int DEFAULT_WINDOW = 15;
    /** Sauvola sensitivity factor; matches the compressor default. */
    public static final double DEFAULT_K = 0.20;
    /** Sauvola dynamic range of the gray scale; matches the compressor default. */
    public static final int DEFAULT_R = 128;

    private SauvolaMask() {
    }

    /**
     * The binarized mask plus the per-pixel threshold it was cut at.
     *
     * @param threshold per-pixel Sauvola threshold, same length as the input
     * @param foreground true where the pixel is ink ({@code gray <= threshold})
     */
    public record Result(int[] threshold, boolean[] foreground) {
        /** Number of ink pixels. */
        public int inkCount() {
            int n = 0;
            for (boolean b : foreground) if (b) n++;
            return n;
        }
    }

    /** Connected components of a binary mask, 8-connected. */
    public record Labels(int[] id, int[] sizes, int count) {
        /** Area of component {@code i}, or 0 for background. */
        public int size(int i) {
            return (i <= 0 || i >= sizes.length) ? 0 : sizes[i];
        }
    }

    /** Thresholds with the compressor's default parameters. */
    public static Result compute(int[] gray, int w, int h) {
        return compute(gray, w, h, DEFAULT_WINDOW, DEFAULT_K, DEFAULT_R);
    }

    /**
     * Computes {@code T(x,y) = m(x,y) * [1 + k * (s(x,y)/R - 1)]} over a local
     * window and cuts the image at it, using integral images for the window
     * mean and standard deviation (both accumulated in {@code long} so a
     * 255-valued sum over a multi-megapixel page cannot overflow).
     */
    public static Result compute(int[] gray, int w, int h, int window, double k, int r) {
        int n = w * h;
        long[] integral = new long[n];
        long[] integralSq = new long[n];
        for (int y = 0; y < h; y++) {
            int row = y * w;
            long rowSum = 0;
            long rowSumSq = 0;
            for (int x = 0; x < w; x++) {
                int v = gray[row + x];
                rowSum += v;
                rowSumSq += (long) v * v;
                long above = y > 0 ? integral[row - w + x] : 0;
                long aboveSq = y > 0 ? integralSq[row - w + x] : 0;
                integral[row + x] = rowSum + above;
                integralSq[row + x] = rowSumSq + aboveSq;
            }
        }

        int half = Math.max(1, window) / 2;
        int rr = Math.max(1, r);
        int[] threshold = new int[n];
        boolean[] fg = new boolean[n];
        for (int y = 0; y < h; y++) {
            int row = y * w;
            int y0 = Math.max(0, y - half);
            int y1 = Math.min(h - 1, y + half);
            for (int x = 0; x < w; x++) {
                int x0 = Math.max(0, x - half);
                int x1 = Math.min(w - 1, x + half);

                long count = (long) (x1 - x0 + 1) * (y1 - y0 + 1);
                long sum = rectSum(integral, x0, y0, x1, y1, w);
                long sumSq = rectSum(integralSq, x0, y0, x1, y1, w);
                double mean = (double) sum / count;
                double variance = Math.max(0.0, (double) sumSq / count - mean * mean);
                double std = Math.sqrt(variance);

                int t = (int) Math.round(mean * (1.0 + k * (std / rr - 1.0)));
                threshold[row + x] = t;
                fg[row + x] = gray[row + x] <= t;
            }
        }
        return new Result(threshold, fg);
    }

    /**
     * 8-connected component labelling by iterative flood fill.  Returns per-pixel
     * ids ({@code 0} = background) plus a size per id, which the speckle metric
     * needs in order to count only the sub-threshold-size blobs.
     */
    public static Labels label(boolean[] fg, int w, int h) {
        int n = w * h;
        int[] id = new int[n];
        int[] sizes = new int[Math.max(16, n / 8)];
        int next = 1;
        int[] stack = new int[Math.max(64, n)];

        for (int start = 0; start < n; start++) {
            if (!fg[start] || id[start] != 0) continue;
            if (next >= sizes.length) {
                int[] grown = new int[sizes.length * 2];
                System.arraycopy(sizes, 0, grown, 0, sizes.length);
                sizes = grown;
            }
            int label = next++;
            int size = 0;
            int sp = 0;
            stack[sp++] = start;
            id[start] = label;
            while (sp > 0) {
                int p = stack[--sp];
                size++;
                int px = p % w;
                int py = p / w;
                for (int dy = -1; dy <= 1; dy++) {
                    int ny = py + dy;
                    if (ny < 0 || ny >= h) continue;
                    for (int dx = -1; dx <= 1; dx++) {
                        if (dx == 0 && dy == 0) continue;
                        int nx = px + dx;
                        if (nx < 0 || nx >= w) continue;
                        int q = ny * w + nx;
                        if (fg[q] && id[q] == 0) {
                            id[q] = label;
                            stack[sp++] = q;
                        }
                    }
                }
            }
            sizes[label] = size;
        }
        return new Labels(id, sizes, next - 1);
    }

    /**
     * Inclusive-range rectangle sum over an integral image laid out as
     * {@code w * h} with {@code integral[y*w+x]} = sum of the prefix rectangle
     * [0..x]x[0..y].
     */
    private static long rectSum(long[] integral, int x0, int y0, int x1, int y1, int w) {
        long topLeft = x0 > 0 && y0 > 0 ? integral[(y0 - 1) * w + (x0 - 1)] : 0;
        long topRight = y0 > 0 ? integral[(y0 - 1) * w + x1] : 0;
        long bottomLeft = x0 > 0 ? integral[y1 * w + (x0 - 1)] : 0;
        return integral[y1 * w + x1] - topRight - bottomLeft + topLeft;
    }
}
