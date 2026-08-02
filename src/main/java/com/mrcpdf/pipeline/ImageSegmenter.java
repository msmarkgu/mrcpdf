package com.mrcpdf.pipeline;

import java.awt.image.BufferedImage;

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
    private final int inpaintRadius;

    public ImageSegmenter() {
        this(64, 0.95, 3);
    }

    /**
     * @param tileSize     side length of the background-normalization grid tiles,
     *                     in image pixels (default 64)
     * @param percentile   per-tile background-level percentile (0.0 - 1.0, default 0.95)
     * @param inpaintRadius inpaint propagation radius, in image pixels (default 3);
     *                     should be ~1.5x the thickest text stroke
     */
    public ImageSegmenter(int tileSize, double percentile, int inpaintRadius) {
        this.tileSize = tileSize;
        this.percentile = percentile;
        this.inpaintRadius = inpaintRadius;
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
     *   3. Auto-threshold the normalized image with Otsu's method to separate
     *      dark foreground (text) from bright background (otsuThreshold).
     *   4. Build a binary TYPE_BYTE_BINARY foreground mask: black where the
     *      pixel intensity is <= threshold (text), white elsewhere. This mask
     *      becomes the JBIG2-compressed foreground layer in the output PDF.
     *   5. Inpaint the text regions out of the original (color) image by
     *      propagating surrounding pixel colors inward up to inpaintRadius px
     *      (two-pass Manhattan distance transform), so the JPEG background
     *      carries no text detail (inpaintBackground).
     *   6. Return the pair as a SegmentedImage.
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

        int threshold = otsuThreshold(bgNormalized);
        int[] maskPixels = new int[width * height];
        for (int i = 0; i < maskPixels.length; i++) {
            // ARGB ints on the mask: 0xFF000000 = opaque black (text/foreground),
            // 0xFFFFFFFF = opaque white (background).
            maskPixels[i] = (gray[i] <= threshold) ? 0xFF000000 : 0xFFFFFFFF;
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
     * "Inpaint" fills the foreground (text) pixels with the colors of the
     * surrounding background, erasing letter shapes from the background layer.
     * This matters because the background is JPEG-compressed at low quality,
     * and JPEG smears hard edges — if text strokes remained, JPEG
     * ringing/block artifacts would appear around characters. Removing them
     * keeps the smooth background clean to compress; the sharp text lives only
     * in the lossless JBIG2 foreground mask.
     *
     * Implementation: propagates surrounding background pixel colors inward
     * using a two-pass Manhattan distance transform, up to {@code radius} px;
     * foreground pixels farther away are set white.
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

        // Build result: fillColor for foreground (within radius), white for beyond radius, origPixels for background
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
