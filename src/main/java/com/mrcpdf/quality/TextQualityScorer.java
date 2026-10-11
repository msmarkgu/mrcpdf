package com.mrcpdf.quality;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.mrcpdf.model.TextBlock;
import com.mrcpdf.quality.model.FeatureExtractor;
import com.mrcpdf.quality.model.QualityModel;
import com.mrcpdf.util.ImageOps;

/**
 * No-reference per-page text-quality scorer for gating MRC compression.
 *
 * Produces a composite in [0, 1] (higher = better) as the weighted geometric
 * mean of six quality groups — Sharpness, Contrast, Noise, Structure,
 * Perceptual, OCR — with two weight sets:
 *
 *   OCR text layer present:  0.30 / 0.20 / 0.15 / 0.20 / 0.10 / 0.05
 *   whole-page fallback:     0.40 / 0.27 / 0.20 / 0.13 (Noise/Perceptual)
 *
 * Weights are provisional Phase-1 defaults (see notes/per-page-quality-score.md).
 *
 * The scorer reads an existing searchable text layer when it carries a real
 * confidence signal and usable word boxes; it never runs OCR itself.  When the
 * layer is absent — or its confidence is the constant 100.0 emitted by a
 * non-OCR PDFBox-derived layer — it falls back to whole-page metrics that
 * omit the Structure and OCR groups.
 *
 * Phase-1 calibrations are intentionally conservative: sub-metrics that are
 * not yet estimated hold a neutral 1.0, and sub-metric calibration constants
 * are package-private so the tuning pass (Phase 2 logistic regression) can
 * re-calibrate without design changes.
 *
 * Detection semantics tested: clean text scores well above default threshold,
 * blurred / noisy / broken-stroke / blank pages score below it, and clean vs
 * degraded ordering holds in both text-layer and whole-page modes.
 */
public class TextQualityScorer {

    /** Default gate threshold used by the pipeline when settings omit it. */
    public static final double DEFAULT_GATE_THRESHOLD = 0.5;

    /** Weighted geometric mean floor: a zero group collapses the composite. */
    private static final double LOG_FLOOR = 1e-4;

    /** Minimum gradient magnitude to count a pixel as a text edge. */
    static final double EDGE_MIN_MAG = 30.0;

    /** Reference for the exponential high-frequency-energy transform. */
    static final double HF_ENERGY_REF = 8.0;

    /**
     * Reference mapping for the Laplacian-over-gradient crispness ratio.
     * 2.2 keeps the clean synthetic fixture (median ratio ~0.43) at ~0.94 rather
     * than clamping at 1.0, so clean pages and mildly blurred pages stay
     * distinguishable; a page at the previous 3.0 reference would have hit the
     * clamp wherever the ratio exceeded 1/3.
     */
    static final double CRISPNESS_REF = 2.2;

    /** Background tile side, in pixels, for the uniformity surface. */
    static final int BG_TILE = 64;

    /** How far below the local background a pixel still counts as background. */
    static final int BG_TOLERANCE = 12;

    /** Reference residual std dev (0-255) for background-uniformity scoring. */
    static final double BG_UNIFORMITY_REF = 20.0;

    /** Minimum amplitude fraction used to normalize SNR (halves a 120-amplitude signal). */
    static final double SNR_AMP_REF = 0.2;

    /** Per-glyph sharpness variance reference CV. */
    static final double GLYPH_SHARPNESS_CV_REF = 1.0;

    /** Stroke-width uniformity reference CV. */
    static final double STROKE_WIDTH_CV_REF = 1.5;

    /** Circle-consistency factor for HF-focus (phase-congruency proxy). */
    static final double HF_BG_SCALE = 2.0;

    /** OCR confidence-consistency reference CV. */
    static final double OCR_CV_REF = 3.0;

    /** Minimum number of usable word boxes before text-layer scoring is trusted. */
    static final int MIN_GLYPH_BOXES = 2;

    private static final double[] TEXT_LAYER_WEIGHTS = {0.30, 0.20, 0.15, 0.20, 0.10, 0.05};
    private static final double[] FALLBACK_WEIGHTS = {0.40, 0.27, 0.20, 0.13};

    private final double dpi; // reserved for DPI-scaled estimates in later phases
    private final QualityModel model; // Phase-2 gate model, or null

    public TextQualityScorer() {
        this(300, null);
    }

    public TextQualityScorer(double dpi) {
        this(dpi, null);
    }

    /** Constructs a scorer with an optional Phase-2 gate model attached. */
    public TextQualityScorer(double dpi, QualityModel model) {
        this.dpi = Math.max(1, dpi);
        this.model = model;
    }

    public double getDpi() {
        return dpi;
    }

    /** The attached gate model, or null when geometric gating is in effect. */
    public QualityModel getModel() {
        return model;
    }

    /**
     * Scores the rendered page.  {@code textBlocks} may be null or empty; the
     * scorer detects whether the layer carries a usable OCR signal and selects
     * text-layer (glyph) or whole-page mode accordingly.
     */
    public TextQualityScore compute(BufferedImage image, List<TextBlock> textBlocks) {
        int w = image.getWidth();
        int h = image.getHeight();
        if (w < 3 || h < 3) {
            return new TextQualityScore(0, 0, 1, 0, 0, 1, 0,
                    1, 1, 0, 1, 1, 0, 0, 1, 1, 0, 1, 0, "Blank", false, -1, -1);
        }

        int[] gray = ImageOps.toGrayscale(image);
        Gradient grad = gradient(gray, w, h);
        ImageOps.OtsuStats otsu = ImageOps.otsuStats(gray);

        List<TextBlock> layer = validTextBlocks(textBlocks, w, h);
        List<Rectangle> boxes = boxesOf(layer);
        boolean usesTextLayer = layer.size() >= MIN_GLYPH_BOXES && hasInformedConfidence(textBlocks);

        // ── Sharpness ──
        double crisp = clamp(CRISPNESS_REF * edgeCrispness(grad), 0, 1);
        double highFreq = 1 - Math.exp(-grad.meanAbsLap / HF_ENERGY_REF);
        double glyphSharpnessVar = 1.0;
        double sharpness;
        if (usesTextLayer) {
            glyphSharpnessVar = perGlyphSharpnessVariance(grad, w, h, boxes);
            sharpness = (crisp + highFreq + glyphSharpnessVar) / 3.0;
        } else {
            sharpness = (crisp + highFreq) / 2.0;
        }

        // ── Contrast ──
        double globalContrast = usesTextLayer
                ? perGlyphContrast(gray, w, h, boxes, otsu)
                : wholePageContrast(gray, otsu);
        double contrast = (otsu.separability() + globalContrast) / 2.0;

        // ── Noise ──
        BgSurface bgSurface = estimateBackground(gray, w, h);
        double residualStd = bgSurface.residualStd(gray, BG_TOLERANCE);
        double backgroundUniformity = clamp(1 - residualStd / BG_UNIFORMITY_REF, 0, 1);
        double amplitude = fgBgAmplitude(gray, otsu);
        double snr = amplitude > 0
                ? clamp(1 - residualStd / Math.max(amplitude * SNR_AMP_REF, 8.0), 0, 1)
                : 0;
        double noise = (backgroundUniformity + snr) / 2.0;

        // ── Structure (text-layer mode only) ──
        double fragmentation = 1.0;
        double strokeWidthUniformity = 1.0;
        double structure = 1.0;
        if (usesTextLayer) {
            PerGlyph glyphStats = perGlyphStructure(gray, w, h, layer);
            fragmentation = medianOfList(glyphStats.fragmentation);
            strokeWidthUniformity = glyphStats.strokeWidthUniformity();
            structure = (fragmentation + strokeWidthUniformity) / 2.0;
        }

        // ── Perceptual: high-frequency focus proxy for phase congruency ──
        double phaseCongruency = hfFocus(grad, gray, otsu);

        // ── OCR ──
        double ocrConfidence = 1.0;
        double ocrConsistency = 1.0;
        double ocr = 1.0;
        if (usesTextLayer) {
            double[] confs = boxConfidences(layer);
            ocrConfidence = clamp(median(confs) / 100.0, 0, 1);
            ocrConsistency = clamp(1 - Math.min(1, cv(confs) * OCR_CV_REF), 0, 1);
            ocr = (ocrConfidence + ocrConsistency) / 2.0;
        }

        // ── Composite: weighted geometric mean over active groups ──
        double[] active;
        double[] weights;
        String[] names;
        if (usesTextLayer) {
            active = new double[]{sharpness, contrast, noise, structure, phaseCongruency, ocr};
            weights = TEXT_LAYER_WEIGHTS;
            names = new String[]{"Sharpness", "Contrast", "Noise", "Structure", "Perceptual", "OCR"};
        } else {
            active = new double[]{sharpness, contrast, noise, phaseCongruency};
            weights = FALLBACK_WEIGHTS;
            names = new String[]{"Sharpness", "Contrast", "Noise", "Perceptual"};
        }

        double composite = 0;
        String weakest = null;
        double weakestScore = Double.MAX_VALUE;
        for (int i = 0; i < active.length; i++) {
            double g = clamp(active[i], 1e-4, 1);
            composite += weights[i] * Math.log(Math.max(g, LOG_FLOOR));
            if (g < weakestScore) {
                weakestScore = g;
                weakest = names[i];
            }
        }
        composite = Math.exp(composite);

        // ── Phase-2 model gate ──
        double modelProbability = -1;
        double modelThreshold = model != null ? model.threshold() : -1;
        if (model != null) {
            double[] features = modelFeatures(gray, w, h, layer, usesTextLayer);
            if (features != null) {
                modelProbability = model.predict(features);
            }
        }

        return new TextQualityScore(crisp, highFreq, glyphSharpnessVar,
                otsu.separability(), globalContrast,
                backgroundUniformity, snr,
                fragmentation, strokeWidthUniformity,
                phaseCongruency, ocrConfidence, ocrConsistency,
                sharpness, contrast, noise, structure, phaseCongruency, ocr,
                composite, composite <= LOG_FLOOR ? "Blank" : weakest, usesTextLayer,
                modelProbability, modelThreshold);
    }

    /**
     * Model feature vector for the page, or null when the attached model's
     * variant cannot be evaluated (text-layer variant without an informative
     * layer).  The image-only variant reuses {@code gray} directly, so no
     * second grayscale pass is needed.
     */
    private double[] modelFeatures(int[] gray, int w, int h,
                                   List<TextBlock> layer, boolean usesTextLayer) {
        if (model.isImageOnly()) {
            return FeatureExtractor.extractImage(gray, w, h, dpi);
        }
        if (!usesTextLayer || layer.size() < 2) return null;
        float[] mag = FeatureExtractor.gradientMagnitudes(gray, w, h);
        double[] text = FeatureExtractor.extractText(gray, mag, w, h, layer);
        if (text == null) return null;
        double[] image = FeatureExtractor.extractImage(gray, w, h, dpi);
        double[] all = new double[image.length + text.length];
        System.arraycopy(image, 0, all, 0, image.length);
        System.arraycopy(text, 0, all, image.length, text.length);
        return all;
    }

    // ──────────────────────────────────────────────────────────────────────
    // Gradient
    // ──────────────────────────────────────────────────────────────────────

    private record Gradient(float[] mag, float[] lap, double meanAbsLap) {
    }

    /** Sobel gradient magnitude + 8-neighbour Laplacian absolute energy. */
    private static Gradient gradient(int[] gray, int w, int h) {
        int n = Math.max(1, gray.length);
        float[] mag = new float[n];
        float[] lap = new float[n];
        double sumAbs = 0;
        for (int y = 1; y < h - 1; y++) {
            int row = y * w;
            for (int x = 1; x < w - 1; x++) {
                int idx = row + x;
                int a = gray[idx - w - 1], bv = gray[idx - w], c = gray[idx - w + 1];
                int d = gray[idx - 1], e = gray[idx], f = gray[idx + 1];
                int g = gray[idx + w - 1], hh = gray[idx + w], ii = gray[idx + w + 1];
                int gx = (c + 2 * f + ii) - (a + 2 * d + g);
                int gy = (g + 2 * hh + ii) - (a + 2 * bv + c);
                float m = (float) Math.sqrt(gx * gx + gy * gy);
                mag[idx] = m;
                float l = Math.abs((a + bv + c + d + f + g + hh + ii) - 8 * e);
                lap[idx] = l;
                sumAbs += l;
            }
        }
        return new Gradient(mag, lap, sumAbs / n);
    }

    /**
     * Median of the Laplacian-over-gradient ratio at strong edges.  Sharp step
     * edges concentrate second-derivative energy locally, blurred edges spread
     * it over many pixels lowering the ratio.
     */
    private static double edgeCrispness(Gradient grad) {
        double[] ratios = new double[grad.mag.length];
        int count = 0;
        for (int i = 0; i < grad.mag.length; i++) {
            double m = grad.mag[i];
            if (m <= EDGE_MIN_MAG) continue;
            double l = grad.lap[i];
            ratios[count++] = l / (l + m + 1e-9);
        }
        return count == 0 ? 0 : median(ratios, count);
    }

    // ──────────────────────────────────────────────────────────────────────
    // Glyph-box helpers
    // ──────────────────────────────────────────────────────────────────────

    /** Filters the text layer to word boxes the scorer can analyse. */
    static List<TextBlock> validTextBlocks(List<TextBlock> textBlocks, int w, int h) {
        List<TextBlock> out = new ArrayList<>();
        if (textBlocks == null || textBlocks.isEmpty()) return out;
        for (TextBlock tb : textBlocks) {
            Rectangle b = tb.getBbox();
            if (b == null) continue;
            if (b.width < 4 || b.height < 3) continue;
            if (b.x < 0 || b.y < 0) continue;
            if (b.x + b.width > w || b.y + b.height > h) continue;
            if (b.width > 0.8 * w || b.height > 0.8 * h) continue; // whole-page region, not a word
            out.add(tb);
        }
        return out;
    }

    private static List<Rectangle> boxesOf(List<TextBlock> layer) {
        List<Rectangle> boxes = new ArrayList<>(layer.size());
        for (TextBlock tb : layer) boxes.add(tb.getBbox());
        return boxes;
    }

    /**
     * A text layer counts as informative only if it carries a real confidence
     * signal: the PDFBox-derived layer emitted by this pipeline pins every
     * confidence to exactly 100.0, while a genuine OCR result (Tesseract etc.)
     * varies.  Confidence is null-safe (null treated as non-OCR -> fallback).
     */
    private static boolean hasInformedConfidence(List<TextBlock> textBlocks) {
        if (textBlocks == null) return false;
        boolean seen = false;
        boolean differs = false;
        for (TextBlock tb : textBlocks) {
            double c = tb.getConfidence();
            if (Math.abs(c - 100.0) > 1e-6) differs = true;
            seen = true;
        }
        return seen && differs;
    }

    /** Mean |Laplacian| inside each box (interior pixels only); CV across boxes. */
    private static double perGlyphSharpnessVariance(Gradient grad, int w, int h, List<Rectangle> boxes) {
        List<Double> means = new ArrayList<>();
        for (Rectangle b : boxes) {
            int x0 = Math.max(1, b.x);
            int y0 = Math.max(1, b.y);
            int x1 = Math.min(w - 2, b.x + b.width - 1);
            int y1 = Math.min(h - 2, b.y + b.height - 1);
            if (x1 <= x0 || y1 <= y0) continue;
            double sum = 0;
            long count = 0;
            for (int y = y0; y <= y1; y++) {
                int row = y * w;
                for (int x = x0; x <= x1; x++) {
                    sum += grad.lap[row + x];
                    count++;
                }
            }
            if (count > 0) means.add(sum / count);
        }
        if (means.size() < 3) return 1.0;
        double cv = cvOf(means);
        return clamp(1 - Math.min(1, cv / GLYPH_SHARPNESS_CV_REF), 0, 1);
    }

    /** Median per-box Michelson contrast (p10/p90 stroke vs background levels). */
    private static double perGlyphContrast(int[] gray, int w, int h, List<Rectangle> boxes,
                                           ImageOps.OtsuStats global) {
        List<Double> values = new ArrayList<>();
        for (Rectangle b : boxes) {
            int[] local = crop(gray, w, h, b);
            if (local == null) continue;
            int[] hist = new int[256];
            for (int p : local) hist[p]++;
            int p10 = percentile(hist, local.length, 0.10);
            int p90 = percentile(hist, local.length, 0.90);
            double fg = p10;
            double bg = p90;
            double michelson = (bg - fg) / (bg + fg + 1e-9);
            values.add(clamp(michelson, 0, 1));
        }
        if (values.isEmpty()) return wholePageContrast(gray, global);
        return medianOfList(values);
    }

    /** Whole-page Michelson contrast from the global Otsu split. */
    private static double wholePageContrast(int[] gray, ImageOps.OtsuStats otsu) {
        double fgSum = 0;
        double bgSum = 0;
        long fgCount = 0;
        long bgCount = 0;
        for (int p : gray) {
            if (p <= otsu.threshold()) {
                fgSum += p;
                fgCount++;
            } else {
                bgSum += p;
                bgCount++;
            }
        }
        if (fgCount == 0 || bgCount == 0) return 0;
        double fg = fgSum / fgCount;
        double bg = bgSum / bgCount;
        return clamp((bg - fg) / (bg + fg + 1e-9), 0, 1);
    }

    /**
     * Per-glyph structure: connected-component fragmentation and stroke-width
     * coefficients.  Broken/ragged glyphs raise both, lowering the scores.
     */
    /**
     * Per-glyph structure: connected-component fragmentation and stroke-width
     * coefficients.  A clean word box naturally contains one component per
     * character, so fragmentation scores the component count against the
     * expected glyph count (the word length).  Broken/ragged glyphs split into
     * extra components and widen the stroke-width spread, lowering both scores.
     */
    private static PerGlyph perGlyphStructure(int[] gray, int w, int h, List<TextBlock> layer) {
        List<Double> fragmentation = new ArrayList<>();
        List<Double> widths = new ArrayList<>();
        for (TextBlock tb : layer) {
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
            int word = tb.getWord() == null ? 1 : Math.max(1, tb.getWord().length());
            // Words with diacritics/dots (e.g. 'i', 'j') legitimately exceed the
            // character count; the +0.5 offsets tolerate a few extra components.
            fragmentation.add(clamp((word + 0.5) / (cc + 0.5), 0, 1));

            double width = strokeWidthEstimate(fg, cw, ch);
            if (width > 0) widths.add(width);
        }
        if (fragmentation.isEmpty()) {
            return new PerGlyph(new ArrayList<>(), 1.0);
        }
        double uniformity = 1.0;
        if (widths.size() >= 3) {
            double cv = stdOfList(widths) / meanOfList(widths);
            uniformity = clamp(1 - Math.min(1, cv / STROKE_WIDTH_CV_REF), 0, 1);
        }
        return new PerGlyph(fragmentation, uniformity);
    }

    record PerGlyph(List<Double> fragmentation, double strokeWidthUniformity) {
    }

    /**
 * Chamfer (3-4) Euclidean distance transform; returns the p80 ink-pixel
 * distance doubled (a stroke-width estimate).
 *
 * <p>Seed on the BACKGROUND and read the result back at the FOREGROUND: each
 * ink pixel's distance to the nearest background pixel is its local
 * half-stroke.  Seeding and reading the same set instead (the previous code)
 * made every sampled distance 0, so the width was always 0 and
 * {@code strokeWidthUniformity} silently fell back to its neutral value --
 * the same seeding defect noted in {@code FeatureExtractor}.
 */
static double strokeWidthEstimate(boolean[] fg, int bw, int bh) {
        int n = bw * bh;
        int INF = 1 << 29;
        int[] d = new int[n];
        Arrays.fill(d, INF);
        for (int i = 0; i < n; i++) {
            if (!fg[i]) d[i] = 0;
        }
        // Pass 1: top-left -> bottom-right
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
        // Pass 2: bottom-right -> top-left
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
        // Collect foreground distances, take p80 -> half-width*2
        double[] dists = new double[fg.length];
        int count = 0;
        for (int i = 0; i < fg.length; i++) {
            if (fg[i] && d[i] < INF) dists[count++] = d[i];
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
                    if (Math.abs(nx - x) <= 1 && Math.abs(ny - y) <= 1 && fg[nb] && !visited[nb]) {
                        visited[nb] = true;
                        stack[sp++] = nb;
                    }
                }
            }
        }
        return count;
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

    // ──────────────────────────────────────────────────────────────────────
    // Noise
    // ──────────────────────────────────────────────────────────────────────

    /** Tile-approximated local background surface (per-tile p90, bilinear map). */
    private static BgSurface estimateBackground(int[] gray, int w, int h) {
        int tilesX = Math.max(1, (w + BG_TILE - 1) / BG_TILE);
        int tilesY = Math.max(1, (h + BG_TILE - 1) / BG_TILE);
        int[][] levels = new int[tilesY][tilesX];
        for (int ty = 0; ty < tilesY; ty++) {
            int sy = ty * BG_TILE;
            int ey = Math.min(sy + BG_TILE, h);
            for (int tx = 0; tx < tilesX; tx++) {
                int sx = tx * BG_TILE;
                int ex = Math.min(sx + BG_TILE, w);
                int[] hist = new int[256];
                for (int y = sy; y < ey; y++) {
                    int row = y * w;
                    for (int x = sx; x < ex; x++) {
                        hist[gray[row + x]]++;
                    }
                }
                int count = (ex - sx) * (ey - sy);
                levels[ty][tx] = percentile(hist, count, 0.95);
            }
        }
        return new BgSurface(levels, BG_TILE, w, h);
    }

    /**
     * Holds only the small tile-level grid; the per-pixel background surface is
     * evaluated on the fly so no full-page array is allocated (page-sized images
     * at 300 DPI would otherwise cost ~64 MB per worker).
     */
    private record BgSurface(int[][] levels, int tile, int w, int h) {

        /** Local bilinear background estimate at (x, y). */
        double localBg(int x, int y) {
            int tilesX = levels[0].length;
            int tilesY = levels.length;
            int ty0 = Math.min(tilesY - 1, y / tile);
            int ty1 = Math.min(tilesY - 1, ty0 + 1);
            double fy = ty0 == ty1 ? 0 : (y - ty0 * tile) / (double) tile;
            int tx0 = Math.min(tilesX - 1, x / tile);
            int tx1 = Math.min(tilesX - 1, tx0 + 1);
            double fx = tx0 == tx1 ? 0 : (x - tx0 * tile) / (double) tile;
            double top = levels[ty0][tx0] * (1 - fx) + levels[ty0][tx1] * fx;
            double bot = levels[ty1][tx0] * (1 - fx) + levels[ty1][tx1] * fx;
            return top * (1 - fy) + bot * fy;
        }

        /** Std dev of the residual (gray - local bg) over background pixels. */
        double residualStd(int[] gray, int tolerance) {
            double sum = 0;
            double sumSq = 0;
            long count = 0;
            for (int y = 0; y < h; y++) {
                int row = y * w;
                for (int x = 0; x < w; x++) {
                    double bg = localBg(x, y);
                    int gv = gray[row + x];
                    if (gv < bg - tolerance) continue;
                    double r = gv - bg;
                    sum += r;
                    sumSq += r * r;
                    count++;
                }
            }
            if (count < 2) return 0;
            double mean = sum / count;
            double var = Math.max(0, sumSq / count - mean * mean);
            return Math.sqrt(var);
        }
    }

    private static double fgBgAmplitude(int[] gray, ImageOps.OtsuStats otsu) {
        double fgSum = 0;
        double bgSum = 0;
        long fgCount = 0;
        long bgCount = 0;
        for (int p : gray) {
            if (p <= otsu.threshold()) {
                fgSum += p;
                fgCount++;
            } else {
                bgSum += p;
                bgCount++;
            }
        }
        if (fgCount == 0 || bgCount == 0) return 0;
        return Math.max(0, bgSum / bgCount - fgSum / fgCount);
    }

    // ──────────────────────────────────────────────────────────────────────
    // Perceptual
    // ──────────────────────────────────────────────────────────────────────

    /**
     * High-frequency focus proxy for phase congruency: how concentrated the
     * edge energy is on the strokes vs. spread into the background.  Ringing,
     * halos and background noise spread high frequencies outward (lowering the
     * ratio); crisp focused text keeps it on the glyphs.
     */
    private static double hfFocus(Gradient grad, int[] gray, ImageOps.OtsuStats otsu) {
        double fgSum = 0;
        double bgSum = 0;
        long fgCount = 0;
        long bgCount = 0;
        for (int i = 0; i < gray.length; i++) {
            double l = grad.lap[i];
            if (gray[i] <= otsu.threshold()) {
                fgSum += l;
                fgCount++;
            } else {
                bgSum += l;
                bgCount++;
            }
        }
        if (fgCount == 0) return 0;
        double fgHf = fgSum / fgCount;
        double bgHf = bgCount > 0 ? bgSum / bgCount : 0;
        return fgHf / (fgHf + HF_BG_SCALE * bgHf + 1e-9);
    }

    // ──────────────────────────────────────────────────────────────────────
    // OCR
    // ──────────────────────────────────────────────────────────────────────

    private static double[] boxConfidences(List<TextBlock> layer) {
        double[] out = new double[layer.size()];
        for (int i = 0; i < out.length; i++) out[i] = layer.get(i).getConfidence();
        return out;
    }

    // ──────────────────────────────────────────────────────────────────────
    // Statistics / percentile helpers
    // ──────────────────────────────────────────────────────────────────────

    private static int percentile(int[] hist, int count, double p) {
        int target = (int) (count * p);
        int cum = 0;
        for (int v = 0; v < 256; v++) {
            cum += hist[v];
            if (cum >= target) return v;
        }
        return 255;
    }

    private static double median(double[] values) {
        return median(values, values.length);
    }

    private static double median(double[] values, int count) {
        if (count == 0) return 0;
        double[] copy = Arrays.copyOf(values, count);
        Arrays.sort(copy);
        return copy[copy.length / 2];
    }

    private static double medianOfList(List<Double> values) {
        double[] arr = new double[values.size()];
        for (int i = 0; i < arr.length; i++) arr[i] = values.get(i);
        return median(arr);
    }

    private static double meanOfList(List<Double> values) {
        if (values.isEmpty()) return 0;
        double sum = 0;
        for (double v : values) sum += v;
        return sum / values.size();
    }

    private static double stdOfList(List<Double> values) {
        double mean = meanOfList(values);
        double sumSq = 0;
        for (double v : values) sumSq += (v - mean) * (v - mean);
        return Math.sqrt(sumSq / values.size());
    }

    private static double cvOf(List<Double> values) {
        double mean = meanOfList(values);
        return mean > 0 ? stdOfList(values) / mean : 0;
    }

    private static double cv(double[] values) {
        if (values.length == 0) return 0;
        double mean = 0;
        for (double v : values) mean += v;
        mean /= values.length;
        if (mean == 0) return 0;
        double var = 0;
        for (double v : values) var += (v - mean) * (v - mean);
        return Math.sqrt(var / values.length) / mean;
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}