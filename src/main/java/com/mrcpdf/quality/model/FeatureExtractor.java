package com.mrcpdf.quality.model;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.mrcpdf.model.TextBlock;
import com.mrcpdf.util.ImageOps;

/**
 * Phase-2 feature extraction for the quality-gate logistic model.
 *
 * Produces the 11 core image features plus, when a genuinely informative text
 * layer (real, non-100.0 OCR confidences with usable word boxes) is present,
 * 3 glyph-local features.  The feature order is fixed and recorded in the
 * model artifact's {@code featureNames}; the scorer and trainer must agree.
 *
 * All pixel-length quantities are DPI-normalized to a 300-DPI equivalent.
 * Power-law quantities (energy, SNR, Laplacian variance) are log1p'd before
 * use, matching the model specification in notes/per-page-quality-score.md.
 */
public final class FeatureExtractor {

    /** Reference DPI for length normalization. */
    public static final double DPI_REF = 300.0;

    /** Minimum gradient magnitude to count a pixel as a text edge. */
    public static final double EDGE_MIN_MAG = 30.0;

    /** Background tile side, in pixels, for the uniformity surface. */
    static final int BG_TILE = 64;

    /** How far below the local background a pixel still counts as background. */
    static final int BG_TOLERANCE = 12;

    private static final double EPS = 1e-9;

    public static final String[] IMAGE_FEATURE_NAMES = {
            "blurSigmaPx", "edgeWidthCV", "hiFreqEnergy", "laplacianVar",
            "otsuSeparability", "michelsonContrast", "histRange",
            "bgUniformity", "noiseSigma", "snr", "inkCoverage"
    };

    public static final String[] TEXT_FEATURE_NAMES = {
            "strokeWidthCV", "fragmentation", "edgeAlign"
    };

    private FeatureExtractor() {
    }

    // ──────────────────────────────────────────────────────────────────────
    // Image features
    // ──────────────────────────────────────────────────────────────────────

    /** Extracts the 11 image features from a rendered page. */
    public static double[] extractImage(BufferedImage image, double dpi) {
        int w = image.getWidth();
        int h = image.getHeight();
        return extractImage(ImageOps.toGrayscale(image), w, h, dpi);
    }

    /** Extracts the 11 image features from a flat grayscale array. */
    public static double[] extractImage(int[] gray, int w, int h, double dpi) {
        int n = Math.max(1, w * h);
        double dpiScale = Math.max(1, dpi) / DPI_REF;

        ImageOps.OtsuStats otsu = ImageOps.otsuStats(gray);
        int[] hist = histogram(gray);

        // Otsu split means for contrast / SNR amplitude.
        long inkCount = 0;
        long fgSum = 0, fgCount = 0, bgSum = 0, bgCount = 0;
        for (int p : gray) {
            if (p <= otsu.threshold()) {
                inkCount++;
                fgSum += p;
                fgCount++;
            } else {
                bgSum += p;
                bgCount++;
            }
        }
        double fgMean = fgCount > 0 ? (double) fgSum / fgCount : 0;
        double bgMean = bgCount > 0 ? (double) bgSum / bgCount : 255;
        double michelson = (bgMean - fgMean) / (bgMean + fgMean + EPS);
        double inkCoverage = (double) inkCount / n;
        double amplitude = Math.max(0, bgMean - fgMean);

        // Single gradient pass: edge widths, HF energy, Laplacian variance.
        double[] edgeWidths = new double[n];
        int edgeCount = 0;
        double magSqSum = 0;
        double lapSum = 0, lapSqSum = 0;
        for (int y = 1; y < h - 1; y++) {
            int row = y * w;
            for (int x = 1; x < w - 1; x++) {
                int idx = row + x;
                int a = gray[idx - w - 1], b = gray[idx - w], c = gray[idx - w + 1];
                int d = gray[idx - 1], e = gray[idx], f = gray[idx + 1];
                int g = gray[idx + w - 1], hh = gray[idx + w], ii = gray[idx + w + 1];
                int gx = (c + 2 * f + ii) - (a + 2 * d + g);
                int gy = (g + 2 * hh + ii) - (a + 2 * b + c);
                double mag = Math.sqrt(gx * gx + gy * gy);
                double lap = Math.abs((a + b + c + d + f + g + hh + ii) - 8 * e);
                lapSum += lap;
                lapSqSum += lap * lap;
                if (mag > EDGE_MIN_MAG) {
                    // Edge-width proxy: a sharp step concentrates 2nd-derivative
                    // energy (lap large relative to mag); blur lowers it.
                    edgeWidths[edgeCount++] = mag / (lap + EPS);
                    magSqSum += mag * mag;
                }
            }
        }

        double blurSigmaPx = 0;
        double edgeWidthCV = 0;
        double hiFreqEnergy = 0;
        if (edgeCount > 0) {
            double[] widths = Arrays.copyOf(edgeWidths, edgeCount);
            Arrays.sort(widths);
            double median = widths[widths.length / 2];
            // Robust width-spread index.  The classic std/mean CV is numerically
            // swamped by mag/(eps) outliers and by the 2-4x larger edge population
            // of blurred pages, so it cannot distinguish a sharp+smudged spread
            // from a uniform soft one.  An inter-decile ratio measures the same
            // physical quantity (width variation across the page) while staying
            // immune to tail/lap-epsilon artifacts; it rises monotonically as a
            // page mixes sharp and heavily blurred edges.
            double p10 = quantile(widths, 0.10);
            double p90 = quantile(widths, 0.90);
            double edgeWidthSpread = (p90 - p10) / (p90 + p10 + EPS);
            // 2.0 maps a sharp step toward ~0.3-1.0 px and heavy blur toward 3px+.
            blurSigmaPx = Math.min(20.0, median * 2.0) * dpiScale;
            edgeWidthCV = edgeWidthSpread;
            hiFreqEnergy = Math.log1p(magSqSum / widths.length);
        }
        double lapMean = lapSum / n;
        double lapVar = Math.max(0, lapSqSum / n - lapMean * lapMean);
        double laplacianVar = Math.log1p(lapVar);

        double[] stats = backgroundNoise(gray, w, h);
        double bgUniformity = stats[0];
        double noiseSigma = stats[1];
        double snr = Math.log1p((amplitude * amplitude) / (noiseSigma * noiseSigma + EPS));

        double histRange = (percentile(hist, n, 0.99) - percentile(hist, n, 0.01)) / 255.0;

        return new double[]{
                blurSigmaPx,
                edgeWidthCV,
                hiFreqEnergy,
                laplacianVar,
                otsu.separability(),
                clamp01(michelson),
                histRange,
                bgUniformity,
                noiseSigma,
                snr,
                inkCoverage
        };
    }

    /**
     * Background statistics over a tile-approximated local-background surface:
     * returns {@code [relativeBackgroundStd, medianFilterResidualStd]}.
     *
     * The median-filter residual (image minus 3x3 median) over background
     * pixels isolates high-frequency sensor noise from the low-frequency text
     * signal; the surface residual captures bleed-through / stains.
     */
    private static double[] backgroundNoise(int[] gray, int w, int h) {
        int tilesX = Math.max(1, (w + BG_TILE - 1) / BG_TILE);
        int tilesY = Math.max(1, (h + BG_TILE - 1) / BG_TILE);
        int[][] levels = new int[tilesY][tilesX];
        for (int ty = 0; ty < tilesY; ty++) {
            int sy = ty * BG_TILE;
            int ey = Math.min(sy + BG_TILE, h);
            for (int tx = 0; tx < tilesX; tx++) {
                int sx = tx * BG_TILE;
                int ex = Math.min(sx + BG_TILE, w);
                levels[ty][tx] = percentile(histogramIn(gray, w, sx, ex, sy, ey),
                        (ex - sx) * (ey - sy), 0.95);
            }
        }

        double sumR = 0, sumR2 = 0, sumN = 0, sumN2 = 0, sumBg = 0;
        long rCount = 0, nCount = 0, bgSum = 0, bgCount = 0;
        for (int y = 0; y < h; y++) {
            int row = y * w;
            int yT0 = Math.min(tilesY - 1, y / BG_TILE);
            int yT1 = Math.min(tilesY - 1, yT0 + 1);
            double fy = yT0 == yT1 ? 0 : (y - yT0 * BG_TILE) / (double) BG_TILE;
            for (int x = 0; x < w; x++) {
                int xT0 = Math.min(tilesX - 1, x / BG_TILE);
                int xT1 = Math.min(tilesX - 1, xT0 + 1);
                double fx = xT0 == xT1 ? 0 : (x - xT0 * BG_TILE) / (double) BG_TILE;
                double top = levels[yT0][xT0] * (1 - fx) + levels[yT0][xT1] * fx;
                double bot = levels[yT1][xT0] * (1 - fx) + levels[yT1][xT1] * fx;
                double localBg = top * (1 - fy) + bot * fy;

                int gv = gray[row + x];
                if (gv < localBg - BG_TOLERANCE) continue; // foreground stroke
                double r = gv - localBg;
                sumR += r;
                sumR2 += r * r;
                rCount++;
                bgSum += gv;
                bgCount++;

                double residue = gv - median3(gray, w, h, x, y);
                sumN += residue;
                sumN2 += residue * residue;
                nCount++;
            }
        }

        double bgUniformity = 0;
        if (rCount > 1) {
            double mean = sumR / rCount;
            double var = Math.max(0, sumR2 / rCount - mean * mean);
            double bgMean = bgCount > 0 ? (double) bgSum / bgCount : 1;
            bgUniformity = Math.sqrt(var) / Math.max(bgMean, EPS);
        }
        double noiseSigma = 0;
        if (nCount > 1) {
            double mean = sumN / nCount;
            double var = Math.max(0, sumN2 / nCount - mean * mean);
            noiseSigma = Math.sqrt(var);
        }
        return new double[]{bgUniformity, noiseSigma};
    }

    /** 3x3 median of the gray neighborhood centered at (x, y). */
    private static double median3(int[] gray, int w, int h, int cx, int cy) {
        int x0 = Math.max(0, cx - 1), x1 = Math.min(w - 1, cx + 1);
        int y0 = Math.max(0, cy - 1), y1 = Math.min(h - 1, cy + 1);
        double[] win = new double[9];
        int k = 0;
        for (int y = y0; y <= y1; y++) {
            int row = y * w;
            for (int x = x0; x <= x1; x++) win[k++] = gray[row + x];
        }
        Arrays.sort(win, 0, k);
        return win[k / 2];
    }

    // ──────────────────────────────────────────────────────────────────────
    // Text-layer features
    // ──────────────────────────────────────────────────────────────────────

    /** Sobel magnitude map used by the glyph-local text features (0 at borders). */
    public static float[] gradientMagnitudes(int[] gray, int w, int h) {
        float[] mag = new float[w * h];
        for (int y = 1; y < h - 1; y++) {
            int row = y * w;
            for (int x = 1; x < w - 1; x++) {
                int idx = row + x;
                int a = gray[idx - w - 1], b = gray[idx - w], c = gray[idx - w + 1];
                int d = gray[idx - 1], f = gray[idx + 1];
                int g = gray[idx + w - 1], hh = gray[idx + w], ii = gray[idx + w + 1];
                int gx = (c + 2 * f + ii) - (a + 2 * d + g);
                int gy = (g + 2 * hh + ii) - (a + 2 * b + c);
                mag[idx] = (float) Math.sqrt(gx * gx + gy * gy);
            }
        }
        return mag;
    }

    /**
     * Glyph-local features from an informative text layer: stroke-width CV,
     * median fragmentation (CCs per character), and edge alignment of glyph
     * boxes against the gradient edge map (halo / registration proxy).
     *
     * Returns null when fewer than 2 usable boxes remain.
     */
    public static double[] extractText(int[] gray, float[] mag, int w, int h,
                                       List<TextBlock> blocks) {
        List<TextBlock> valid = validBlocks(blocks, w, h);
        if (valid.size() < 2) return null;

        List<Double> widths = new ArrayList<>();
        List<Double> frags = new ArrayList<>();
        List<Double> align = new ArrayList<>();

        for (TextBlock tb : valid) {
            Rectangle b = tb.getBbox();
            int cw = Math.min(w, b.x + b.width) - Math.max(0, b.x);
            int ch = Math.min(h, b.y + b.height) - Math.max(0, b.y);
            if (cw <= 0 || ch <= 0) continue;
            int[] local = crop(gray, w, h, b);
            if (local == null) continue;
            ImageOps.OtsuStats otsu = ImageOps.otsuStats(local);
            boolean[] fg = new boolean[local.length];
            int fgCount = 0;
            for (int i = 0; i < local.length; i++) {
                fg[i] = local[i] <= otsu.threshold();
                if (fg[i]) fgCount++;
            }
            if (fgCount < 8) continue;

            int cc = countComponents(fg, cw, ch);
            int wordLen = tb.getWord() == null ? 1 : Math.max(1, tb.getWord().length());
            frags.add((double) cc / wordLen);

            double strokeWidth = strokeWidthEstimate(fg, cw, ch, local);
            if (strokeWidth > 0) widths.add(strokeWidth);

            Double ratio = edgeAlignment(mag, w, h, b);
            if (ratio != null) align.add(ratio);
        }

        if (widths.size() < 3 || frags.isEmpty()) return null;
        double strokeWidthCV = cvOfList(widths);
        double fragmentation = medianOfList(frags);
        double edgeAlign = align.isEmpty() ? 1.0 : medianOfList(align);
        return new double[]{strokeWidthCV, fragmentation, edgeAlign};
    }

    /**
     * Median of the mean |Sobel| in a 2px band outside each glyph box over the
     * box interior.  Values >> 1 mean edge energy outside the layer's boxes —
     * signature of mask drift, halos or registration error.
     */
    private static Double edgeAlignment(float[] mag, int w, int h, Rectangle b) {
        int x0 = Math.max(1, b.x), y0 = Math.max(1, b.y);
        int x1 = Math.min(w - 2, b.x + b.width - 1);
        int y1 = Math.min(h - 2, b.y + b.height - 1);
        if (x1 <= x0 || y1 <= y0) return null;

        double inside = 0;
        long insideCount = 0;
        for (int y = y0; y <= y1; y++) {
            int row = y * w;
            for (int x = x0; x <= x1; x++) {
                if (mag == null) return null;
                inside += mag[row + x];
                insideCount++;
            }
        }
        if (insideCount == 0) return null;

        double outside = 0;
        long outsideCount = 0;
        int grow = 2;
        int ox0 = Math.max(1, b.x - grow), oy0 = Math.max(1, b.y - grow);
        int ox1 = Math.min(w - 2, b.x + b.width - 1 + grow);
        int oy1 = Math.min(h - 2, b.y + b.height - 1 + grow);
        for (int y = oy0; y <= oy1; y++) {
            int row = y * w;
            for (int x = ox0; x <= ox1; x++) {
                if (x >= x0 && x <= x1 && y >= y0 && y <= y1) continue;
                outside += mag[row + x];
                outsideCount++;
            }
        }
        if (outsideCount == 0) return null;
        double interiorMean = inside / insideCount;
        return interiorMean > EPS ? (outside / outsideCount) / interiorMean : null;
    }

    // ──────────────────────────────────────────────────────────────────────
    // Text-layer validation
    // ──────────────────────────────────────────────────────────────────────

    /**
     * True when the layer carries a real OCR confidence signal: at least one
     * confidence differs from the constant 100.0 that PDFBox-derived layers
     * pin, and at least {@link #MIN_GLYPH_BOXES} boxes are usable.
     */
    public static boolean isInformativeTextLayer(List<TextBlock> blocks, int w, int h) {
        if (blocks == null) return false;
        boolean seen = false;
        boolean differs = false;
        for (TextBlock tb : blocks) {
            double c = tb.getConfidence();
            if (Math.abs(c - 100.0) > 1e-6) differs = true;
            seen = true;
        }
        return seen && differs && validBlocks(blocks, w, h).size() >= MIN_GLYPH_BOXES;
    }

    static final int MIN_GLYPH_BOXES = 2;

    private static List<TextBlock> validBlocks(List<TextBlock> textBlocks, int w, int h) {
        List<TextBlock> out = new ArrayList<>();
        if (textBlocks == null || textBlocks.isEmpty()) return out;
        for (TextBlock tb : textBlocks) {
            Rectangle b = tb.getBbox();
            if (b == null) continue;
            if (b.width < 4 || b.height < 3) continue;
            if (b.x < 0 || b.y < 0) continue;
            if (b.x + b.width > w || b.y + b.height > h) continue;
            if (b.width > 0.8 * w || b.height > 0.8 * h) continue;
            out.add(tb);
        }
        return out;
    }

    // ──────────────────────────────────────────────────────────────────────
    // Reused image primitives (chamfer EDT, CC labeling) — kept local so the
    // scorer and extractor stay independent.
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Median stroke width of a binarized word patch, or 0 when the patch holds no
     * ink.  Package-private so {@link MrcReadiness} can measure the same
     * quantity the {@code strokeWidthCV} feature uses.
     */
    static double strokeWidthEstimate(boolean[] fg, int bw, int bh, int[] local) {
        int n = bw * bh;
        int INF = 1 << 29;
        int[] d = new int[n];
        Arrays.fill(d, INF);
        // Seed on the BACKGROUND and read the result back at the FOREGROUND.
        // Each ink pixel's distance to the nearest background pixel is that
        // pixel's local half-stroke, so a high percentile over ink tracks stroke
        // width.  Seeding and reading the same set instead (the previous code)
        // makes every sampled distance identically 0, so this always returned 0,
        // which in turn made extractText() always return null and silently
        // dropped the 3 text features from every corpus built so far.
        for (int i = 0; i < n; i++) {
            if (!fg[i]) d[i] = 0;
        }
        for (int y = 0; y < bh; y++) {
            int row = y * bw;
            for (int x = 0; x < bw; x++) {
                int idx = row + x;
                if (d[idx] == 0) continue;
                int best = INF;
                if (x > 0) best = Math.min(best, d[idx - 1] + 3);
                if (y > 0) best = Math.min(best, d[idx - bw] + 3);
                if (x > 0 && y > 0) best = Math.min(best, d[idx - bw - 1] + 4);
                if (x < bw - 1 && y > 0) best = Math.min(best, d[idx - bw + 1] + 4);
                d[idx] = best;
            }
        }
        for (int y = bh - 1; y >= 0; y--) {
            int row = y * bw;
            for (int x = bw - 1; x >= 0; x--) {
                int idx = row + x;
                if (d[idx] == 0) continue;
                int best = d[idx];
                if (x < bw - 1) best = Math.min(best, d[idx + 1] + 3);
                if (y < bh - 1) best = Math.min(best, d[idx + bw] + 3);
                if (x < bw - 1 && y < bh - 1) best = Math.min(best, d[idx + bw + 1] + 4);
                if (x > 0 && y < bh - 1) best = Math.min(best, d[idx + bw - 1] + 4);
                d[idx] = best;
            }
        }
        double[] dists = new double[fg.length];
        int count = 0;
        for (int i = 0; i < fg.length; i++) {
            if (fg[i] && d[i] < INF && d[i] > 0) dists[count++] = d[i];
        }
        if (count == 0) return 0;
        Arrays.sort(dists, 0, count);
        int idx = Math.min(count - 1, (int) Math.round(0.80 * count));
        return 2.0 * dists[idx] / 3.0;
    }

    /** 8-connected foreground component count via scan-line flood fill. */
    static int countComponents(boolean[] fg, int bw, int bh) {
        int n = bw * bh;
        boolean[] visited = new boolean[n];
        int count = 0;
        int[] stack = new int[Math.max(32, n)];
        for (int i = 0; i < n; i++) {
            if (!fg[i] || visited[i]) continue;
            count++;
            int sp = 0;
            stack[sp++] = i;
            visited[i] = true;
            while (sp > 0) {
                int p = stack[--sp];
                int x = p % bw;
                int y = p / bw;
                int[] nbr = {p - bw - 1, p - bw, p - bw + 1, p - 1, p + 1,
                        p + bw - 1, p + bw, p + bw + 1};
                for (int nb : nbr) {
                    if (nb < 0 || nb >= n) continue;
                    int nx = nb % bw;
                    int ny = nb / bw;
                    if (Math.abs(nx - x) <= 1 && Math.abs(ny - y) <= 1
                            && fg[nb] && !visited[nb]) {
                        visited[nb] = true;
                        stack[sp++] = nb;
                    }
                }
            }
        }
        return count;
    }

    // ──────────────────────────────────────────────────────────────────────
    // Small helpers
    // ──────────────────────────────────────────────────────────────────────

    private static int[] histogram(int[] gray) {
        int[] hist = new int[256];
        for (int p : gray) hist[p]++;
        return hist;
    }

    private static int[] histogramIn(int[] gray, int rowStride, int sx, int ex, int sy, int ey) {
        int[] hist = new int[256];
        for (int y = sy; y < ey; y++) {
            int row = y * rowStride;
            for (int x = sx; x < ex; x++) hist[gray[row + x]]++;
        }
        return hist;
    }

    private static int percentile(int[] hist, int count, double p) {
        long target = (long) (count * p);
        long cum = 0;
        for (int v = 0; v < 256; v++) {
            cum += hist[v];
            if (cum >= target) return v;
        }
        return 255;
    }

    private static int[] crop(int[] gray, int w, int h, Rectangle b) {
        int x0 = Math.max(0, b.x);
        int y0 = Math.max(0, b.y);
        int x1 = Math.min(w, b.x + b.width);
        int y1 = Math.min(h, b.y + b.height);
        if (x1 <= x0 || y1 <= y0) return null;
        int bw = x1 - x0;
        int bh = y1 - y0;
        int[] local = new int[bw * bh];
        for (int y = y0; y < y1; y++) {
            System.arraycopy(gray, y * w + x0, local, (y - y0) * bw, bw);
        }
        return local;
    }

    private static double medianOfList(List<Double> values) {
        double[] arr = new double[values.size()];
        for (int i = 0; i < arr.length; i++) arr[i] = values.get(i);
        Arrays.sort(arr);
        return arr[arr.length / 2];
    }

    /** Linear-interpolated quantile of a sorted array. */
    private static double quantile(double[] sorted, double q) {
        if (sorted.length == 0) return 0;
        if (sorted.length == 1) return sorted[0];
        double pos = q * (sorted.length - 1);
        int lo = (int) Math.floor(pos);
        int hi = Math.min(sorted.length - 1, lo + 1);
        double frac = pos - lo;
        return sorted[lo] * (1 - frac) + sorted[hi] * frac;
    }

    /**
     * Coefficient of variation of a sample.  Package-private so
     * {@link MrcReadiness} scores stroke-width consistency the same way the
     * {@code strokeWidthCV} feature does.
     */
    static double cvOfList(List<Double> values) {
        double mean = 0;
        for (double v : values) mean += v;
        mean /= values.size();
        double var = 0;
        for (double v : values) var += (v - mean) * (v - mean);
        var = Math.max(0, var / values.size());
        return mean > EPS ? Math.sqrt(var) / mean : 0;
    }

    private static double clamp01(double v) {
        return Math.max(0, Math.min(1, v));
    }
}