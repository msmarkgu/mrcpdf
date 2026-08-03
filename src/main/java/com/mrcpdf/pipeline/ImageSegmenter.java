package com.mrcpdf.pipeline;

import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.Map;

import com.mrcpdf.model.SegmentedImage;

public class ImageSegmenter {

    // tileSize is in image pixels at the rendering DPI (not PDF points): the
    // side length of each square tile used to estimate the local background.
    private final int tileSize;
    private final double percentile;
    // inpaintRadius is in image pixels at the rendering DPI: the Manhattan
    // distance from a foreground pixel to the nearest background pixel up to
    // which an inpainted color is propagated. Inpainting erases text from the
    // background layer so the JPEG carries no text detail (see inpaintBackground).
    // radius <= 0 means infinite propagation (no white blobs behind thick strokes).
    private final int inpaintRadius;
    // Thresholding: "sauvola" (default, local adaptive) or "otsu" (global).
    private final String thresholdMode;
    private final int sauvolaWindow;
    private final double sauvolaK;
    private final int sauvolaR;
    // Connected-component filtering: components larger than these limits are
    // treated as pictures/logos and left in the background layer (not masked).
    // 0 disables that filter.
    private final double maxComponentArea; // fraction of page area
    private final double maxComponentDim;  // fraction of the larger page dimension

    public ImageSegmenter() {
        this(64, 0.95, 0);
    }

    /**
     * @param tileSize       side length of the background-normalization grid tiles,
     *                       in image pixels (default 64)
     * @param percentile     per-tile background-level percentile (0.0 - 1.0, default 0.95)
     * @param inpaintRadius  inpaint propagation radius, in image pixels; <= 0 means
     *                       infinite (default)
     */
    public ImageSegmenter(int tileSize, double percentile, int inpaintRadius) {
        this(tileSize, percentile, inpaintRadius, "sauvola", 15, 0.20, 128, 0.005, 0.10);
    }

    /**
     * Full configuration constructor.
     *
     * @param tileSize         background-normalization tile side, in image pixels
     * @param percentile       per-tile background-level percentile (0.0 - 1.0)
     * @param inpaintRadius    inpaint propagation radius; <= 0 = infinite
     * @param thresholdMode    "sauvola" (local adaptive, default) or "otsu" (global)
     * @param sauvolaWindow    Sauvola local window side, in image pixels (default 15)
     * @param sauvolaK         Sauvola sensitivity factor (default 0.20)
     * @param sauvolaR         Sauvola dynamic range of the image (default 128)
     * @param maxComponentArea components above this fraction of page area are dropped
     *                         from the mask (0 disables)
     * @param maxComponentDim  components above this fraction of the larger page
     *                         dimension are dropped from the mask (0 disables)
     */
    public ImageSegmenter(int tileSize, double percentile, int inpaintRadius,
                          String thresholdMode, int sauvolaWindow, double sauvolaK, int sauvolaR,
                          double maxComponentArea, double maxComponentDim) {
        this.tileSize = tileSize;
        this.percentile = percentile;
        this.inpaintRadius = inpaintRadius;
        this.thresholdMode = "otsu".equalsIgnoreCase(thresholdMode) ? "otsu" : "sauvola";
        this.sauvolaWindow = Math.max(1, sauvolaWindow);
        this.sauvolaK = sauvolaK;
        this.sauvolaR = Math.max(1, sauvolaR);
        this.maxComponentArea = Math.max(0, maxComponentArea);
        this.maxComponentDim = Math.max(0, maxComponentDim);
    }

    /**
     * Segments a rendered page image into a foreground mask and a cleaned
     * background, the two visual layers of MRC compression.
     *
     * Algorithm:
     *   1. Extract the page as ARGB pixels and convert to grayscale using
     *      ITU-R BT.601 luma (toGrayscale).
     *   2. Correct non-uniform illumination (shadows, gradients, scanner
     *      vignetting): divide the page into a grid of tileSize tiles, estimate
     *      each tile's local background level at the given percentile, build a
     *      smooth background surface via bilinear interpolation, then stretch
     *      each pixel: new = old * 255 / bg (backgroundNormalize).
     *   3. Threshold the normalized image to separate dark foreground (text)
     *      from bright background. Default is local adaptive Sauvola
     *      thresholding (sauvolaThreshold), which keeps faint text on uneven
     *      backgrounds; the global Otsu method (otsuThreshold) is available via
     *      the "otsu" threshold mode.
     *   4. Drop oversized connected components (photos, logos, halftones) from
     *      the mask so they stay in the color background instead of polluting
     *      the JBIG2 dictionary (filterLargeComponents).
     *   5. Build a binary TYPE_BYTE_BINARY foreground mask: black where the
     *      pixel is foreground (text), white elsewhere. This mask becomes the
     *      JBIG2-compressed foreground layer in the output PDF.
     *   6. Inpaint the text regions out of the original (color) image by
     *      propagating surrounding pixel colors inward up to inpaintRadius px
     *      (two-pass Manhattan distance transform); radius <= 0 propagates to
     *      every foreground pixel so no white blobs remain. The JPEG background
     *      then carries no text detail (inpaintBackground).
     *   7. Return the pair as a SegmentedImage.
     */
    public SegmentedImage segment(BufferedImage image) {
        int width = image.getWidth();
        int height = image.getHeight();

        int[] origPixels = new int[width * height];
        // Bulk-copy the whole image into the flat ARGB array in row-major order:
        // getRGB(x0, y0, w, h, dst, offset, scansize). Here offset=0 starts at
        // origPixels[0] and scansize=width matches the packed layout, so pixel
        // (x, y) lands at origPixels[y * width + x]. This avoids per-pixel
        // getRGB(x, y) overhead; the flat arrays below operate on this copy.
        image.getRGB(0, 0, width, height, origPixels, 0, width);

        int[] gray = toGrayscale(origPixels);
        int[] bgNormalized = backgroundNormalize(gray, width, height, tileSize);

        // Phase 1: binary classification into foreground (1) / background (0)
        int[] binary = new int[width * height];
        if ("otsu".equals(thresholdMode)) {
            int threshold = otsuThreshold(bgNormalized);
            for (int i = 0; i < binary.length; i++) {
                binary[i] = (gray[i] <= threshold) ? 1 : 0;
            }
        } else {
            int[] thresholds = sauvolaThreshold(bgNormalized, width, height, sauvolaWindow, sauvolaK, sauvolaR);
            for (int i = 0; i < binary.length; i++) {
                binary[i] = (bgNormalized[i] <= thresholds[i]) ? 1 : 0;
            }
        }

        // Phase 2: drop oversized components (photos/logos) from the mask
        if (maxComponentArea > 0 || maxComponentDim > 0) {
            filterLargeComponents(binary, width, height, maxComponentArea, maxComponentDim);
        }

        int[] maskPixels = new int[width * height];
        for (int i = 0; i < maskPixels.length; i++) {
            // ARGB ints on the mask: 0xFF000000 = opaque black (text/foreground),
            // 0xFFFFFFFF = opaque white (background).
            maskPixels[i] = (binary[i] != 0) ? 0xFF000000 : 0xFFFFFFFF;
        }
        BufferedImage foregroundMask = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_BINARY);
        foregroundMask.setRGB(0, 0, width, height, maskPixels, 0, width);

        BufferedImage cleanedBackground = inpaintBackground(origPixels, maskPixels, width, height, inpaintRadius);

        return new SegmentedImage(foregroundMask, cleanedBackground);
    }

    /**
     * Converts a flat ARGB int array to a grayscale int array (0 = black, 255 = white)
     * using ITU-R BT.601 luma with integer arithmetic.
     *
     * Integer coefficients (scaled by 256):
     *   0.299 * 256 ≈ 77
     *   0.587 * 256 ≈ 150
     *   0.114 * 256 ≈ 29
     *   Sum: 77 + 150 + 29 = 256 (exact), so (R=G=B) maps to itself.
     *
     * Bit shifts in RGB extraction:
     *   (rgb >> 16) & 0xFF  — extract the red  byte (bits 16-23 of 0xAARRGGBB)
     *   (rgb >>  8) & 0xFF  — extract the green byte (bits  8-15)
     *   rgb        & 0xFF  — extract the blue byte (bits  0-7)
     */
    private int[] toGrayscale(int[] rgba) {
        int[] gray = new int[rgba.length];
        for (int i = 0; i < rgba.length; i++) {
            int rgb = rgba[i];
            int r = (rgb >> 16) & 0xFF;
            int g = (rgb >> 8) & 0xFF;
            int b = rgb & 0xFF;
            gray[i] = (77 * r + 150 * g + 29 * b) >> 8;
        }
        return gray;
    }

    /**
     * Tile-based background normalization that corrects non-uniform illumination
     * (e.g. shadows, gradients, scanner vignetting).
     *
     * Divides the image into a grid of tiles, estimates the local background
     * level in each tile (95th percentile), builds a smooth background surface
     * via bilinear interpolation, then stretches each pixel:
     *   new = old * 255 / bg_estimate.
     *
     * Optimizations versus the original:
     *   - Integer ceiling division for tile counts.
     *   - Precomputed per-column tile indices (tx0LUT, tx1LUT, fxLUT) to
     *     eliminate two div/rem operations per pixel.
     *   - Precomputed 256×256 normalization lookup table to replace the
     *     gray * 255 / bg division + clamp with a single array load.
     */
    private int[] backgroundNormalize(int[] gray, int width, int height, int tileSize) {
        int tilesX = (width + tileSize - 1) / tileSize;
        int tilesY = (height + tileSize - 1) / tileSize;
        int ts2 = tileSize * tileSize;

        // Sample background level per tile (95th percentile = bright, since text is dark)
        int[][] bgLevels = new int[tilesY][tilesX];
        for (int ty = 0; ty < tilesY; ty++) {
            int startY = ty * tileSize;
            int endY = Math.min(startY + tileSize, height);
            for (int tx = 0; tx < tilesX; tx++) {
                int startX = tx * tileSize;
                int endX = Math.min(startX + tileSize, width);

                int[] hist = new int[256];
                for (int y = startY; y < endY; y++) {
                    int rowOffset = y * width;
                    for (int x = startX; x < endX; x++) {
                        hist[gray[rowOffset + x]]++;
                    }
                }
                int count = (endX - startX) * (endY - startY);
                int cum = 0;
                int bgValue = 0;
                int target = (int) (count * percentile);
                for (int v = 0; v < 256; v++) {
                    cum += hist[v];
                    if (cum >= target) {
                        bgValue = v;
                        break;
                    }
                }
                bgLevels[ty][tx] = bgValue;
            }
        }

        // Precompute per-column tile indices and fractions
        // Shift anchor points by halfTile so bgLevels values are centred in tiles.
        int halfTile = tileSize / 2;
        int[] tx0LUT = new int[width];
        int[] tx1LUT = new int[width];
        int[] fxLUT = new int[width];
        for (int x = 0; x < width; x++) {
            int sx = x + halfTile;
            int t = sx / tileSize;
            if (t < 0) t = 0;
            if (t >= tilesX) t = tilesX - 1;
            tx0LUT[x] = t;
            tx1LUT[x] = Math.min(t + 1, tilesX - 1);
            int f = sx - t * tileSize;
            fxLUT[x] = f < tileSize ? f : tileSize - 1;
        }

        // Precompute 256×256 normalization table: norm[gray][bg] = min(255, gray*255/bg)
        int[][] norm = new int[256][256];
        for (int g = 0; g < 256; g++) {
            int[] row = norm[g];
            row[0] = g;
            for (int b = 1; b < 256; b++) {
                int v = g * 255 / b;
                row[b] = v < 255 ? v : 255;
            }
        }

        // Per-pixel background normalization with integer bilinear interpolation
        // Tile anchors are shifted by halfTile to centre bgLevels within each tile.
        int[] result = new int[gray.length];
        for (int y = 0; y < height; y++) {
            int sy = y + halfTile;
            int ty0 = sy / tileSize;
            if (ty0 < 0) ty0 = 0;
            if (ty0 >= tilesY) ty0 = tilesY - 1;
            int ty1 = Math.min(ty0 + 1, tilesY - 1);
            int fy = sy - ty0 * tileSize;
            if (fy >= tileSize) fy = tileSize - 1;

            int[] bgRow0 = bgLevels[ty0];
            int[] bgRow1 = bgLevels[ty1];

            int rowOffset = y * width;
            for (int x = 0; x < width; x++) {
                int fx = fxLUT[x];
                int b00 = bgRow0[tx0LUT[x]];
                int b01 = bgRow0[tx1LUT[x]];
                int b10 = bgRow1[tx0LUT[x]];
                int b11 = bgRow1[tx1LUT[x]];

                int top = b00 * (tileSize - fx) + b01 * fx;
                int bot = b10 * (tileSize - fx) + b11 * fx;
                int bg = (top * (tileSize - fy) + bot * fy) / ts2;

                result[rowOffset + x] = norm[gray[rowOffset + x]][bg];
            }
        }
        return result;
    }

    /**
     * Otsu's method for automatic image thresholding.
     *
     * Theory: exhaustively search all 256 possible threshold values and pick the
     * one that maximises the inter-class variance between foreground and background
     * pixel distributions.  This gives optimal separation without any user tuning.
     *
     * Algorithm steps:
     *   1. Build a 256-bin histogram of pixel intensities.
     *   2. For each threshold t (0..255), split pixels into two classes:
     *      class B (background, intensities > t) and class F (foreground, ≤ t).
     *   3. Compute the between-class variance:
     *          σ² = wB * wF * (μB - μF)²
     *      where w = weight (proportion of pixels), μ = mean intensity.
     *   4. Return the t that maximises σ².
     *
     * Magic numbers:
     *   256 — number of bins in an 8-bit grayscale histogram (2^8 possible values).
     *   wB, wF  — weights (class pixel counts).  These are integers but the formula
     *     uses them as the product to give heavier weight to balanced splits.
     */
    private int otsuThreshold(int[] pixels) {
        int[] histogram = new int[256];
        for (int p : pixels) {
            histogram[p]++;
        }

        int total = pixels.length;
        double sum = 0;
        for (int i = 0; i < 256; i++) {
            sum += (double) i * histogram[i];
        }

        double sumB = 0;
        int wB = 0;
        double maxVariance = 0;
        int threshold = 0;

        for (int i = 0; i < 256; i++) {
            wB += histogram[i];
            if (wB == 0) continue;
            int wF = total - wB;
            if (wF == 0) break;

            sumB += (double) i * histogram[i];
            double meanB = sumB / wB;
            double meanF = (sum - sumB) / wF;
            double variance = (double) wB * wF * (meanB - meanF) * (meanB - meanF);

            if (variance > maxVariance) {
                maxVariance = variance;
                threshold = i;
            }
        }
        return threshold;
    }

    /**
     * Sauvola local adaptive thresholding.
     *
     * Computes a per-pixel threshold from the mean and standard deviation of a
     * small window around each pixel, using integral images so the whole page is
     * O(W×H) regardless of window size. Unlike the global Otsu method this keeps
     * faint text in low-contrast / unevenly lit regions:
     *
     *     threshold = mean * (1 + k * (stddev / R - 1))
     *
     * Background pixels (stddev ≈ 0) get threshold ≈ mean * (1 - k), which stays
     * below a bright uniform background, while dark text far below the local mean
     * is classified foreground. Returns an int array of thresholds (one per pixel).
     */
    private int[] sauvolaThreshold(int[] gray, int width, int height, int window, double k, int r) {
        // Integral images: sum and sum-of-squares, both long to avoid overflow
        // (sum of 255 over 8M pixels exceeds int range).
        long[] integral = new long[width * height];
        long[] integralSq = new long[width * height];
        for (int y = 0; y < height; y++) {
            int row = y * width;
            long rowSum = 0, rowSumSq = 0;
            for (int x = 0; x < width; x++) {
                int v = gray[row + x];
                rowSum += v;
                rowSumSq += (long) v * v;
                long above = y > 0 ? integral[row - width + x] : 0;
                long aboveSq = y > 0 ? integralSq[row - width + x] : 0;
                integral[row + x] = rowSum + above;
                integralSq[row + x] = rowSumSq + aboveSq;
            }
        }

        int half = window / 2;
        int[] thresholds = new int[width * height];
        for (int y = 0; y < height; y++) {
            int row = y * width;
            int y0 = Math.max(0, y - half);
            int y1 = Math.min(height - 1, y + half);
            for (int x = 0; x < width; x++) {
                int x0 = Math.max(0, x - half);
                int x1 = Math.min(width - 1, x + half);

                long sum = rectSum(integral, x0, y0, x1, y1, width);
                long sumSq = rectSum(integralSq, x0, y0, x1, y1, width);
                int count = (x1 - x0 + 1) * (y1 - y0 + 1);
                double mean = (double) sum / count;
                double variance = ((double) sumSq / count) - mean * mean;
                double std = variance > 0 ? Math.sqrt(variance) : 0;
                thresholds[row + x] = (int) Math.round(mean * (1.0 + k * (std / r - 1.0)));
            }
        }
        return thresholds;
    }

    private static long rectSum(long[] integral, int x0, int y0, int x1, int y1, int width) {
        long topLeft = x0 > 0 && y0 > 0 ? integral[(y0 - 1) * width + (x0 - 1)] : 0;
        long topRight = y0 > 0 ? integral[(y0 - 1) * width + x1] : 0;
        long bottomLeft = x0 > 0 ? integral[y1 * width + (x0 - 1)] : 0;
        return integral[y1 * width + x1] - topRight - bottomLeft + topLeft;
    }

    /**
     * Removes oversized connected components from the binary mask so that dark
     * photos, logos and halftone regions stay in the (color) background layer
     * instead of being binarized into the JBIG2 foreground dictionary.
     *
     * A component is dropped when its pixel area exceeds {@code maxComponentArea}
     * (a fraction of the page area) or either bbox dimension exceeds
     * {@code maxComponentDim} (a fraction of the larger page dimension). Setting
     * either limit to 0 disables that criterion.
     *
     * Implementation: single-pass union-find labeling (8-connectivity) with one
     * int array (negative root size), then per-root area/bbox accumulation.
     */
    private static void filterLargeComponents(int[] binary, int width, int height,
                                              double maxComponentArea, double maxComponentDim) {
        int n = binary.length;
        // uf[i] < 0 : i is a root, -uf[i] = component size (background uses 0)
        // uf[i] >= 0: i points at its parent
        int[] uf = new int[n];
        for (int y = 0; y < height; y++) {
            int row = y * width;
            for (int x = 0; x < width; x++) {
                int idx = row + x;
                if (binary[idx] == 0) continue;
                uf[idx] = -1; // root with size 1
                if (x > 0 && binary[idx - 1] != 0) union(uf, idx, idx - 1);
                if (y > 0) {
                    if (binary[idx - width] != 0) union(uf, idx, idx - width);
                    if (x > 0 && binary[idx - width - 1] != 0) union(uf, idx, idx - width - 1);
                    if (x < width - 1 && binary[idx - width + 1] != 0) union(uf, idx, idx - width + 1);
                }
            }
        }

        // Accumulate area + bbox per root
        Map<Integer, long[]> roots = new HashMap<>();
        for (int y = 0; y < height; y++) {
            int row = y * width;
            for (int x = 0; x < width; x++) {
                int idx = row + x;
                if (binary[idx] == 0) continue;
                int root = find(uf, idx);
                long[] acc = roots.get(root);
                if (acc == null) {
                    // [area, minX, minY, maxX, maxY]
                    acc = new long[]{0, x, y, x, y};
                    roots.put(root, acc);
                }
                acc[0]++;
                if (x < acc[1]) acc[1] = x;
                if (y < acc[2]) acc[2] = y;
                if (x > acc[3]) acc[3] = x;
                if (y > acc[4]) acc[4] = y;
            }
        }

        long pageArea = (long) width * height;
        int pageDim = Math.max(width, height);
        long maxAreaPx = maxComponentArea > 0 ? (long) (pageArea * maxComponentArea) : Long.MAX_VALUE;
        int maxDimPx = maxComponentDim > 0 ? (int) (pageDim * maxComponentDim) : Integer.MAX_VALUE;

        int[] drop = new int[roots.size()];
        int dropCount = 0;
        for (Map.Entry<Integer, long[]> e : roots.entrySet()) {
            long[] acc = e.getValue();
            int compW = (int) (acc[3] - acc[1] + 1);
            int compH = (int) (acc[4] - acc[2] + 1);
            if (acc[0] > maxAreaPx || Math.max(compW, compH) > maxDimPx) {
                drop[dropCount++] = e.getKey();
            }
        }
        if (dropCount == 0) return;

        for (int i = 0; i < dropCount; i++) {
            int root = drop[i];
            // Null out the root's size so it can never be confused with a live root
            uf[root] = Integer.MIN_VALUE;
        }

        for (int i = 0; i < n; i++) {
            if (binary[i] == 0) continue;
            if (uf[find(uf, i)] == Integer.MIN_VALUE) binary[i] = 0;
        }
    }

    private static int find(int[] uf, int x) {
        int root = x;
        while (uf[root] >= 0) root = uf[root];
        while (uf[x] >= 0) {
            int next = uf[x];
            uf[x] = root;
            x = next;
        }
        return root;
    }

    private static void union(int[] uf, int a, int b) {
        int ra = find(uf, a);
        int rb = find(uf, b);
        if (ra == rb) return;
        // Union by size: more negative = larger component
        if (uf[ra] > uf[rb]) {
            int t = ra;
            ra = rb;
            rb = t;
        }
        uf[ra] += uf[rb];
        uf[rb] = ra;
    }

    /**
     * "Inpaint" fills the foreground (text) pixels with the colors of the
     * surrounding background, erasing letter shapes from the background layer.
     * This matters because the background is JPEG-compressed at low quality,
     * and JPEG smears hard edges — if text strokes remained, JPEG
     * ringing/block artifacts would appear around characters. Removing them
     * keeps the smooth background clean to compress; the sharp text lives only
     * in the lossless JBIG2 foreground mask.
     *
     * Implementation: propagates surrounding background pixel colors inward
     * using a two-pass Manhattan distance transform. With a positive
     * {@code radius}, foreground pixels farther away than {@code radius} px are
     * set white; with {@code radius <= 0} (default) propagation continues to
     * every foreground pixel, so no white blobs appear behind thick strokes.
     *
     * This replaces the O(N × R²) brute-force search with O(N) propagation
     * (4-connected manhattan distance to nearest background pixel).
     *
     * Pass 1 (top-left → bottom-right): propagate colors from top and left neighbors.
     * Pass 2 (bottom-right → top-left): propagate colors from bottom and right neighbors.
     *
     * Background pixels are copied unchanged.
     */
    private BufferedImage inpaintBackground(int[] origPixels, int[] maskPixels, int width, int height, int radius) {
        int len = width * height;
        int[] dist = new int[len];
        int[] fillColor = new int[len];

        // Initialize: background (white mask) = distance 0, foreground (black mask) = INF
        // Always seed fillColor from origPixels so all-foreground images degrade gracefully.
        for (int i = 0; i < len; i++) {
            fillColor[i] = origPixels[i];
            if ((maskPixels[i] & 0xFFFFFF) != 0) {
                dist[i] = 0;
            } else {
                dist[i] = Integer.MAX_VALUE / 2;
            }
        }

        // Pass 1: top-left → bottom-right
        for (int y = 0; y < height; y++) {
            int row = y * width;
            for (int x = 0; x < width; x++) {
                int idx = row + x;
                if (dist[idx] == 0) continue;
                // Check top neighbor
                if (y > 0) {
                    int n = idx - width;
                    int nd = dist[n] + 1;
                    if (nd < dist[idx]) {
                        dist[idx] = nd;
                        fillColor[idx] = fillColor[n];
                    }
                }
                // Check left neighbor
                if (x > 0) {
                    int n = idx - 1;
                    int nd = dist[n] + 1;
                    if (nd < dist[idx]) {
                        dist[idx] = nd;
                        fillColor[idx] = fillColor[n];
                    }
                }
            }
        }

        // Pass 2: bottom-right → top-left
        for (int y = height - 1; y >= 0; y--) {
            int row = y * width;
            for (int x = width - 1; x >= 0; x--) {
                int idx = row + x;
                if (dist[idx] == 0) continue;
                // Check bottom neighbor
                if (y < height - 1) {
                    int n = idx + width;
                    int nd = dist[n] + 1;
                    if (nd < dist[idx]) {
                        dist[idx] = nd;
                        fillColor[idx] = fillColor[n];
                    }
                }
                // Check right neighbor
                if (x < width - 1) {
                    int n = idx + 1;
                    int nd = dist[n] + 1;
                    if (nd < dist[idx]) {
                        dist[idx] = nd;
                        fillColor[idx] = fillColor[n];
                    }
                }
            }
        }

        // Build result: fillColor for foreground (within radius), white for
        // foreground beyond radius when a finite radius is set, origPixels for
        // background. radius <= 0 propagates to every foreground pixel.
        int[] resultPixels = new int[len];
        for (int i = 0; i < len; i++) {
            if (dist[i] == 0) {
                resultPixels[i] = origPixels[i];
            } else if (radius > 0 && dist[i] > radius) {
                resultPixels[i] = 0xFFFFFFFF; // white for foreground pixels beyond inpaint radius
            } else {
                resultPixels[i] = fillColor[i];
            }
        }

        BufferedImage result = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        result.setRGB(0, 0, width, height, resultPixels, 0, width);
        return result;
    }
}
