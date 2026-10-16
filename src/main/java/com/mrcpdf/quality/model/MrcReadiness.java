package com.mrcpdf.quality.model;

import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.mrcpdf.model.TextBlock;

/**
 * The Human-Perceptual MRC Readiness Index, {@code R_MRC}, from
 * notes/gemini-page-readability-score.md §4.
 *
 * <p>Where {@code 1 - WER} measures how well a page OCRs <em>against known
 * ground truth</em>, {@code R_MRC} measures how ready the page's own pixels are
 * for lossy MRC compression.  That distinction matters for two reasons: ground
 * truth does not exist at runtime, and a ground-truth comparison is only as
 * trustworthy as the extracted reference text.
 *
 * <p>The score fuses four orthogonal dimensions, each in [0,1] with 1.0 pristine:
 * glyph-local optical sharpness ({@code S_visual}), binarization/mask stability
 * ({@code S_mask}), Tesseract's intrinsic recognition quality ({@code S_ocr}),
 * and background cleanliness ({@code S_bg}).  They are combined with a weighted
 * geometric mean and then a bottleneck penalty, because legibility follows
 * Liebig's law of the minimum: spotless margins cannot rescue illegible
 * characters, and a linear model would happily let them offset each other.
 *
 * <pre>
 *   R_geom  = S_visual^0.28 * S_mask^0.28 * S_ocr^0.24 * S_bg^0.20
 *   kappa   = min(S_visual, S_mask, S_ocr, S_bg)
 *   R_MRC   = R_geom * (kappa &gt;= 0.35 ? 1 : (kappa/0.35)^1.5)
 * </pre>
 *
 * <p>All glyph-local metrics are measured strictly inside OCR word boxes, which
 * is what distinguishes this from the whole-page features in
 * {@link FeatureExtractor}: a page that is 80% white margin and 20% destroyed
 * text must not score well.
 */
public final class MrcReadiness {

    // ── Aggregation (design doc §4.2) ────────────────────────────────────────
    static final double W_VISUAL = 0.28;
    static final double W_MASK = 0.28;
    static final double W_OCR = 0.24;
    static final double W_BG = 0.20;
    /** Below this, any single dimension is disqualifying. */
    public static final double TAU_CRIT = 0.35;
    /** Aggressive-MRC boundary (design doc §7). */
    public static final double GOOD_ABOVE = 0.70;
    /** Fidelity-fallback boundary (design doc §7). */
    public static final double BAD_BELOW = 0.45;

    // ── Sub-score weights (design doc §4.1) ──────────────────────────────────
    static final double V_BLUR = 0.45, V_CONTRAST = 0.35, V_SWCV = 0.20;
    static final double K_MARGIN = 0.40, K_FRAG = 0.40, K_SPECKLE = 0.20;
    static final double O_CONF_MEAN = 0.35, O_CONF_TAIL = 0.25, O_DISPERSION = 0.20,
            O_LEX = 0.20;
    /**
     * Usable glyph boxes at which the OCR evidence gate reaches full credit.
     * See {@link #mEvidence} for why this is a floor on evidence rather than a
     * tuned quality threshold.
     */
    static final int EVIDENCE_MIN_BOXES = 50;
    static final double B_NOISE = 0.60, B_UNIFORM = 0.40;

    // ── Normalization constants (design doc §4.1) ───────────────────────────
    /** Components at or below this size count as speckle, not glyphs. */
    static final int SPECKLE_MAX_PX = 4;
    /** Intensity distance from the Sauvola threshold counted as ambiguous. */
    static final double MARGIN_TOLERANCE = 6.0;
    static final double DISPERSION_REF = 0.5;
    static final int BG_TILE = 64;
    static final double EPS = 1e-9;
    /** Minimum usable OCR boxes before glyph-local metrics mean anything. */
    public static final int MIN_GLYPH_BOXES = 2;

    private MrcReadiness() {
    }

    /**
     * Every intermediate value behind the composite, so a score can be audited
     * rather than merely trusted.  {@code glyphBoxes} exposes how many OCR boxes
     * backed the glyph-local dimensions; a low count means those dimensions were
     * measured on little or no text.
     */
    public record Breakdown(
            double mBlur, double mContrast, double mSwcv, double sVisual,
            double mMargin, double mFrag, double mSpeckle, double sMask,
            double mConfMean, double mConfTail, double mDispersion, double mLex, double sOcr,
            double mNoise, double mUniform, double sBg,
            double rGeom, double kappa, double bottleneck, double score,
            int glyphBoxes) {

        /** MRC policy tier for this page (design doc §7). */
        public String tier() {
            if (score >= GOOD_ABOVE) return "good";
            if (score >= BAD_BELOW) return "middle";
            return "bad";
        }
    }

    /**
     * The uncalibrated page measurements, in their natural units.
     *
     * <p>Separating these from the sub-scores is what makes the calibration in
     * {@link MrcRefs} trustworthy: the harness measures the very same quantities
     * the scorer consumes, so the reference distribution cannot drift away from
     * the metric as two hand-maintained copies of the same formula would.
     */
    public record Raw(
            double blurResidual,
            double contrast,
            double strokeWidthCv,
            double ambiguousFraction,
            double compsPerChar,
            double specklePerMpx,
            double noiseSigma,
            double tileSigma) {
    }

    /**
     * Fuses four sub-scores by the design-doc formula.  Exposed so the
     * aggregation algebra (geometric mean, bottleneck, tiering) can be tested
     * directly, without synthesizing a page that happens to hit given values.
     */
    public static Breakdown aggregate(double sVisual, double sMask, double sOcr, double sBg) {
        return aggregate(sVisual, sMask, sOcr, sBg, 0);
    }

    /**
     * As {@link #aggregate(double, double, double, double)}, but with an explicit
     * glyph-box count.
     *
     * <p>The four-sub-score form has no page behind it, so it reports zero boxes.
     * That is fine for testing the aggregation algebra, but it makes the box count
     * untestable in isolation, and the count gates the steering value in
     * {@code ScoreSampler} -- the safety that stops a page degraded past
     * recognition from reporting a clean steering value.  So the count is
     * overridable here rather than left to a synthetic raster.
     */
    public static Breakdown aggregate(double sVisual, double sMask, double sOcr, double sBg,
                                      int glyphBoxes) {
        double v = clamp01(sVisual), k = clamp01(sMask);
        double o = clamp01(sOcr), b = clamp01(sBg);

        double rGeom = Math.pow(Math.max(v, EPS), W_VISUAL)
                * Math.pow(Math.max(k, EPS), W_MASK)
                * Math.pow(Math.max(o, EPS), W_OCR)
                * Math.pow(Math.max(b, EPS), W_BG);
        double kappa = Math.min(Math.min(v, k), Math.min(o, b));
        double bottleneck = bottleneck(kappa);
        return new Breakdown(v, v, v, v, k, k, k, k, o, o, o, o, o, b, b, b,
                rGeom, kappa, bottleneck, clamp01(rGeom * bottleneck), glyphBoxes);
    }

    /** The OCR sub-score implied by a set of word confidences. */
    public static double sOcrOf(List<TextBlock> boxes) {
        List<TextBlock> usable = usableBoxes(boxes, Integer.MAX_VALUE, Integer.MAX_VALUE);
        return combinedOcr(mOcr(usable), usable.size());
    }

    /**
     * Combines the four OCR quality terms and gates them on evidence.
     *
     * <p>The four terms measure the quality of what the engine <em>found</em>.
     * {@link #mEvidence} measures whether it found anything at all.  Those are
     * different kinds of quantity, so evidence multiplies rather than averages:
     * an additive term could not demote a blank page, because on a page with a
     * single crisply-read glyph all four quality terms are ~1.0 and would
     * average to ~1.0 on their own.
     *
     * <p>Without this gate the metric <em>rewarded blankness</em>.  A page whose
     * text had degraded past the point of recognition was scored like a pristine
     * blank sheet: the pixel dimensions saw a smooth, uncluttered, ink-free page
     * and the confidence statistics were taken over a sample of one box.  On the
     * 500-page pilot that produced four pages labelled {@code good} on which
     * Tesseract recovered 1-3 words.
     */
    private static double combinedOcr(double[] ocr, int boxCount) {
        double weighted = O_CONF_MEAN * ocr[0] + O_CONF_TAIL * ocr[1]
                + O_DISPERSION * ocr[2] + O_LEX * ocr[3];
        return clamp01(weighted * mEvidence(boxCount));
    }

    /**
     * Scales to 0 as the engine stops finding text, reaching full credit at
     * {@link #EVIDENCE_MIN_BOXES}.
     *
     * <p>The threshold is not tuned.  Every page below 50 usable boxes on the
     * pilot was completely unreadable (40/40 and 71/71 with word F1 &lt; 0.3),
     * while the <em>least</em> readable clean page carried 398 boxes, so any
     * value from 30 to 200 gives identical results with an order of magnitude
     * of margin either way.  It is a floor on evidence, not a quality threshold:
     * pages at or above it are bit-for-bit unaffected.
     */
    static double mEvidence(int boxCount) {
        if (boxCount <= 0) return 0.0;
        return Math.min(1.0, boxCount / (double) EVIDENCE_MIN_BOXES);
    }

    /** Scores a page from its grayscale raster and the OCR words found on it. */
    public static Breakdown compute(int[] gray, int w, int h, List<TextBlock> ocrBoxes) {
        SauvolaMask.Result mask = SauvolaMask.compute(gray, w, h);
        return compute(gray, w, h, ocrBoxes, mask);
    }

    /**
     * Scores a page against a binarization the caller already has, so the MRC
     * pipeline can pass the mask it built for segmentation instead of paying for
     * a second Sauvola pass (design doc §8.0, "late gate").
     */
    public static Breakdown compute(int[] gray, int w, int h, List<TextBlock> ocrBoxes,
                                    SauvolaMask.Result mask) {
        List<TextBlock> boxes = usableBoxes(ocrBoxes, w, h);
        boolean[] textRegion = textRegionMask(boxes, w, h);
        Raw raw = measure(gray, w, h, boxes, textRegion, mask);
        return score(raw, boxes, w, h);
    }

    /**
     * Scores an already-measured page.  Every dimension except the OCR block
     * comes from {@code raw}, so a caller that already has the measurements
     * (the calibration tool) can re-score under different reference constants
     * without paying for OCR and masking again.  {@code boxes} must already be
     * filtered by {@link #usableBoxes}.
     */
    public static Breakdown score(Raw raw, List<TextBlock> boxes, int w, int h) {

        // Calibrated against a measured clean-scan baseline: 1.0 is "as sharp as
        // a fresh scan of a real page", not "as sharp as a perfect raster".
        double mBlur = MrcRefs.higherIsBetter(raw.blurResidual(),
                MrcRefs.BLUR_GOOD, MrcRefs.BLUR_BAD);
        double mContrast = MrcRefs.higherIsBetter(raw.contrast(),
                MrcRefs.CONTRAST_GOOD, MrcRefs.CONTRAST_BAD);
        double mSwcv = MrcRefs.lowerIsBetter(raw.strokeWidthCv(),
                MrcRefs.SWCV_GOOD, MrcRefs.SWCV_BAD);
        double sVisual = clamp01(V_BLUR * mBlur + V_CONTRAST * mContrast + V_SWCV * mSwcv);

        double mMargin = MrcRefs.lowerIsBetter(raw.ambiguousFraction(),
                MrcRefs.MARGIN_AMBIGUOUS_GOOD, MrcRefs.MARGIN_AMBIGUOUS_BAD);
        double mFrag = clamp01(Math.exp(-Math.pow(
                (raw.compsPerChar() - MrcRefs.FRAG_CENTER) / MrcRefs.FRAG_SPREAD, 2)));
        double mSpeckle = MrcRefs.lowerIsBetter(raw.specklePerMpx(),
                MrcRefs.SPECKLE_GOOD, MrcRefs.SPECKLE_BAD);
        double sMask = clamp01(K_MARGIN * mMargin + K_FRAG * mFrag + K_SPECKLE * mSpeckle);

        double[] ocr = mOcr(boxes);
        double mConfMean = ocr[0], mConfTail = ocr[1], mDispersion = ocr[2], mLex = ocr[3];
        double sOcr = combinedOcr(ocr, boxes.size());

        // Calibrated like the other dimensions.  The previous absolute
        // references (NOISE_REF=15, UNIFORM_REF=30) sat an order of magnitude
        // above anything this corpus produces, so both terms scored ~0.97 on
        // every page and 20% of the aggregate weight carried no signal.
        double mNoise = MrcRefs.lowerIsBetter(raw.noiseSigma(),
                MrcRefs.NOISE_GOOD, MrcRefs.NOISE_BAD);
        double mUniform = MrcRefs.lowerIsBetter(raw.tileSigma(),
                MrcRefs.UNIFORM_GOOD, MrcRefs.UNIFORM_BAD);
        double sBg = clamp01(B_NOISE * mNoise + B_UNIFORM * mUniform);

        // Every dimension is floored just above zero: the geometric mean must
        // stay finite (and a genuinely destroyed page must score near zero, not
        // be silently rescued by the epsilon).
        double rGeom = Math.pow(Math.max(sVisual, EPS), W_VISUAL)
                * Math.pow(Math.max(sMask, EPS), W_MASK)
                * Math.pow(Math.max(sOcr, EPS), W_OCR)
                * Math.pow(Math.max(sBg, EPS), W_BG);

        double kappa = Math.min(Math.min(sVisual, sMask), Math.min(sOcr, sBg));
        double bottleneck = bottleneck(kappa);
        double score = clamp01(rGeom * bottleneck);

        return new Breakdown(mBlur, mContrast, mSwcv, sVisual,
                mMargin, mFrag, mSpeckle, sMask,
                mConfMean, mConfTail, mDispersion, mLex, sOcr,
                mNoise, mUniform, sBg,
                rGeom, kappa, bottleneck, score, boxes.size());
    }

    /**
     * Bottleneck penalty for the weakest dimension, floored above zero.
     *
     * <p>The geometric mean is floored at {@link #EPS} for the same reason, but
     * the bottleneck was not, so any single dimension landing exactly on 0 gave
     * {@code kappa = 0} and therefore {@code bottleneck = 0} -- annihilating the
     * three dimensions that were fine.  On the pilot that pinned 150 of 500
     * pages to exactly {@code R_mrc = 0}, spread across all nine degradation
     * tiers, which destroyed the ranking the tier assignment depends on.
     *
     * <p>Flooring keeps the penalty steep but continuous, so a page just past
     * the floor still sorts above one well past it.
     */
    private static double bottleneck(double kappa) {
        if (kappa >= TAU_CRIT) return 1.0;
        return Math.pow(Math.max(kappa, EPS) / TAU_CRIT, 1.5);
    }

    /** Glyph boxes this scorer will actually look at. */
    public static List<TextBlock> usable(List<TextBlock> ocrBoxes, int w, int h) {
        return usableBoxes(ocrBoxes, w, h);
    }

    /**
     * Measures the raw page quantities the sub-scores are calibrated from.
     *
     * <p>Exposed so {@code MrcCalibration} can collect the distribution these
     * take over a corpus without reimplementing any of the metric bodies.
     */
    public static Raw measure(int[] gray, int w, int h, List<TextBlock> ocrBoxes,
                              SauvolaMask.Result mask) {
        List<TextBlock> boxes = usableBoxes(ocrBoxes, w, h);
        return measure(gray, w, h, boxes, textRegionMask(boxes, w, h), mask);
    }

    private static Raw measure(int[] gray, int w, int h, List<TextBlock> boxes,
                               boolean[] textRegion, SauvolaMask.Result mask) {
        double blur = blurResidual(gray, w, h, textRegion);
        double contrast = contrast(gray, w, h, boxes);
        double cv = strokeWidthCv(gray, w, h, boxes, mask);
        double ambiguous = ambiguousFraction(gray, w, h, boxes, mask);
        double comps = compsPerChar(w, h, boxes, mask);
        double speckle = specklePerMpx(w, h, textRegion, mask);
        double[] bg = backgroundSigma(gray, w, h, textRegion, mask);
        return new Raw(blur, contrast, cv, ambiguous, comps, speckle, bg[0], bg[1]);
    }

    /**
     * Measures only the background pair {@code {noiseSigma, tileSigma}} that
     * {@code sBg} is built from.
     *
     * <p>These are the two dimensions that need no glyph boxes to evaluate, so a
     * caller calibrating the {@code sBg} endpoints can sweep a whole population
     * without paying for OCR -- which is the entire cost of a normal
     * measurement.  The text region is passed as empty, which makes the
     * statistics page-wide rather than glyph-local; that is the right choice
     * when the question is "how noisy is this page's paper" rather than "how
     * noisy is this text".
     *
     * @return {@code {noiseSigma, tileSigma}}
     */
    public static double[] backgroundOnly(int[] gray, int w, int h, SauvolaMask.Result mask) {
        boolean[] noText = new boolean[w * h];
        return backgroundSigma(gray, w, h, noText, mask);
    }

    // ── Dimension 1: glyph-local sharpness & contrast ───────────────────────

    /**
     * Crete's re-blur metric, restricted to the glyph regions: mildly low-pass
     * the image, then take the 90th percentile of {@code |I - blur(I)|}.  A crisp
     * edge loses a lot of energy under a second blur; an already-blurred edge
     * loses little, so the statistic is monotone in blur width by construction.
     *
     * <p>Deviation from notes/gemini-page-readability-score.md §4.1.1, which
     * specifies an edge ratio {@code |grad|/(|lap|+eps)}.  That form is unusable
     * here, and two of its failure modes are worth recording:
     * <ul>
     *   <li>As written it is scale-inverted.  For a Gaussian-blurred step the
     *       gradient falls as {@code 1/sigma} while the Laplacian falls as
     *       {@code 1/sigma^2}, so {@code |lap|/|grad|} <em>grows</em> with blur
     *       (Bruce's metric) — the written ratio moves the wrong way.</li>
     *   <li>Correcting the orientation to {@code |lap|/|grad|} still is not
     *       monotone on real 8-bit rasters.  A discrete 3x3 Laplacian is
     *       dominated by the shape of the glyph corner, not the blur width, and
     *       measured on a synthetic page it fell from 0.50 (hard edge) to 0.17 at
     *       sigma=0.5px, i.e. the score rose as the image was blurred.</li>
     * </ul>
     * Crete's form has no such failure mode, so the substitute is used and this
     * note kept so the deviation is not mistaken for an oversight.
     * {@code MrcReadinessTest.blurResponseIsMonotoneInSigma} guards it.
     */
    private static double blurResidual(int[] gray, int w, int h, boolean[] textRegion) {
        double[] diffs = new double[4096];
        int count = 0;
        for (int y = 1; y < h - 1; y++) {
            int row = y * w;
            for (int x = 1; x < w - 1; x++) {
                int i = row + x;
                if (!textRegion[i]) continue;
                int sum = (gray[i - w - 1] + 2 * gray[i - w] + gray[i - w + 1]
                        + 2 * gray[i - 1] + 4 * gray[i] + 2 * gray[i + 1]
                        + gray[i + w - 1] + 2 * gray[i + w] + gray[i + w + 1]) / 16;
                if (count == diffs.length) diffs = Arrays.copyOf(diffs, count * 2);
                diffs[count++] = Math.abs(gray[i] - sum);
            }
        }
        if (count == 0) return 0.0;
        double[] used = Arrays.copyOf(diffs, count);
        Arrays.sort(used);
        return used[Math.min(count - 1, (int) Math.round(0.90 * (count - 1)))];
    }

    /** Median per-word Michelson contrast between local ink and local paper. */
    private static double contrast(int[] gray, int w, int h, List<TextBlock> boxes) {
        List<Double> cs = new ArrayList<>();
        for (TextBlock tb : boxes) {
            Rectangle b = tb.getBbox();
            int[] hist = new int[256];
            int n = 0;
            for (int y = b.y; y < b.y + b.height; y++) {
                int row = y * w;
                for (int x = b.x; x < b.x + b.width; x++) {
                    hist[gray[row + x]]++;
                    n++;
                }
            }
            if (n < 16) continue;
            int theta = com.mrcpdf.util.ImageOps.otsuStats(histSample(hist, n)).threshold();

            long inkN = 0, paperN = 0, inkSum = 0, paperSum = 0;
            for (int v = 0; v < 256; v++) {
                if (v <= theta) {
                    inkN += hist[v];
                    inkSum += (long) v * hist[v];
                } else {
                    paperN += hist[v];
                    paperSum += (long) v * hist[v];
                }
            }
            if (inkN == 0 || paperN == 0) continue;
            double muInk = (double) inkSum / inkN;
            double muPaper = (double) paperSum / paperN;
            cs.add((muPaper - muInk) / (muPaper + muInk + EPS));
        }
        if (cs.isEmpty()) return 0.0;
        return median(cs);
    }

    /**
     * Coefficient of variation of per-word median stroke width.  Reuses the
     * chamfer distance transform behind the {@code strokeWidthCV} feature, so the
     * metric agrees with the trained feature by construction.
     *
     * @return the coefficient of variation, or 0 if fewer than two words are
     *         measurable
     */
    private static double strokeWidthCv(int[] gray, int w, int h, List<TextBlock> boxes,
                                        SauvolaMask.Result mask) {
        List<Double> widths = new ArrayList<>();
        for (TextBlock tb : boxes) {
            Rectangle b = tb.getBbox();
            int bw = b.width, bh = b.height;
            boolean[] fg = new boolean[bw * bh];
            int[] local = new int[bw * bh];
            for (int y = 0; y < bh; y++) {
                int src = (b.y + y) * w + b.x;
                int dst = y * bw;
                for (int x = 0; x < bw; x++) {
                    local[dst + x] = gray[src + x];
                    fg[dst + x] = mask.foreground()[src + x];
                }
            }
            double sw = FeatureExtractor.strokeWidthEstimate(fg, bw, bh, local);
            if (sw > 0) widths.add(sw);
        }
        if (widths.size() < 2) return 0.0;
        return FeatureExtractor.cvOfList(widths);
    }

    // ── Dimension 2: binarization & mask stability ───────────────────────────

    /**
     * Fraction of stroke pixels sitting within {@link #MARGIN_TOLERANCE} of the
     * local Sauvola threshold.  These are the pixels a little noise erases, so a
     * high fraction predicts stroke dropout once the page is segmented.
     */
    private static double ambiguousFraction(int[] gray, int w, int h, List<TextBlock> boxes,
                                            SauvolaMask.Result mask) {
        int[] threshold = mask.threshold();
        long ambiguous = 0, strokes = 0;
        for (TextBlock tb : boxes) {
            Rectangle b = tb.getBbox();
            for (int y = b.y; y < b.y + b.height; y++) {
                int row = y * w;
                for (int x = b.x; x < b.x + b.width; x++) {
                    int i = row + x;
                    if (!mask.foreground()[i]) continue;
                    strokes++;
                    if (Math.abs(gray[i] - threshold[i]) <= MARGIN_TOLERANCE) ambiguous++;
                }
            }
        }
        if (strokes == 0) return 1.0;
        return (double) ambiguous / strokes;
    }

    /**
     * Connected components per character inside each word box.  Well-formed Latin
     * text sits near 1.15 (dots on i/j, punctuation add a little); much above
     * that means strokes have shattered, much below that characters have fused.
     */
    private static double compsPerChar(int w, int h, List<TextBlock> boxes,
                                       SauvolaMask.Result mask) {
        List<Double> ratios = new ArrayList<>();
        for (TextBlock tb : boxes) {
            Rectangle b = tb.getBbox();
            int bw = b.width, bh = b.height;
            boolean[] fg = new boolean[bw * bh];
            boolean any = false;
            for (int y = 0; y < bh; y++) {
                int src = (b.y + y) * w + b.x;
                int dst = y * bw;
                for (int x = 0; x < bw; x++) {
                    fg[dst + x] = mask.foreground()[src + x];
                    if (fg[dst + x]) any = true;
                }
            }
            if (!any) continue;
            int cc = FeatureExtractor.countComponents(fg, bw, bh);
            int chars = Math.max(1, alphanumericLength(tb.getWord()));
            ratios.add((double) cc / chars);
        }
        if (ratios.isEmpty()) return 0.0;
        return median(ratios);
    }

    /**
     * Sub-4-pixel blobs per megapixel of non-text background.  Speckle in the
     * mask is what inflates a JBIG2 symbol dictionary into millions of singletons.
     *
     * <p>Numerator and denominator are restricted to the same pixels.  The
     * numerator used to count every sub-4px component on the page while the
     * denominator counted only pixels outside the OCR text region, so the ratio
     * was really "page content density" wearing a noise metric's name: a page of
     * dense small type produced a large numerator and a shrunken denominator at
     * the same time, and the measured clean baseline came out at 850/MP.  A
     * glyph fragment is not speckle, and counting it made the band depend on how
     * much text a document happened to contain.
     */
    private static double specklePerMpx(int w, int h, boolean[] textRegion,
                                        SauvolaMask.Result mask) {
        SauvolaMask.Labels labels = SauvolaMask.label(mask.foreground(), w, h);
        long blobs = 0;
        long bgArea = 0;
        int[] sizes = labels.sizes();
        for (int i = 0; i < mask.foreground().length; i++) {
            // Text is not background: exclude it from both sides of the ratio.
            if (textRegion[i]) continue;
            if (mask.foreground()[i]) {
                if (labels.id()[i] > 0 && sizes[labels.id()[i]] <= SPECKLE_MAX_PX) blobs++;
            } else {
                bgArea++;
            }
        }
        if (bgArea == 0) return 0.0;
        double megapixels = bgArea / 1_000_000.0;
        return megapixels > 0 ? blobs / megapixels : 0.0;
    }

    // ── Dimension 3: Tesseract intrinsic quality ─────────────────────────────

    /** @return {@code {mConfMean, mConfTail, mDispersion, mLex}} */
    private static double[] mOcr(List<TextBlock> boxes) {
        if (boxes.isEmpty()) return new double[]{0.0, 0.0, 0.0, 0.0};
        int n = boxes.size();
        double[] conf = new double[n];
        double sum = 0;
        int valid = 0;
        for (int i = 0; i < n; i++) {
            conf[i] = Math.max(0.0, Math.min(100.0, boxes.get(i).getConfidence()));
            sum += conf[i];
            if (isLexicallyValid(boxes.get(i).getWord())) valid++;
        }
        double mean = sum / n;

        double[] sorted = conf.clone();
        Arrays.sort(sorted);
        double tail = sorted[Math.max(0, (int) Math.floor(0.10 * (n - 1)))];

        double sq = 0;
        for (double c : conf) sq += (c - mean) * (c - mean);
        double std = n > 1 ? Math.sqrt(sq / (n - 1)) : 0.0;
        double cv = std / (mean + EPS);

        return new double[]{
                clamp01(mean / 100.0),
                clamp01(tail / 100.0),
                clamp01(1.0 - Math.min(1.0, cv / DISPERSION_REF)),
                (double) valid / n
        };
    }

    /**
     * A word counts as lexically valid when it is at least two characters long
     * and more than 80% alphanumeric.  This is what catches the characteristic
     * degradation signature of punctuation soup like {@code "7,)(} or
     * {@code "2,...,2,)"} in place of real words.
     */
    private static boolean isLexicallyValid(String word) {
        if (word == null) return false;
        int total = 0, alnum = 0;
        for (int i = 0; i < word.length(); i++) {
            char c = word.charAt(i);
            if (Character.isWhitespace(c)) continue;
            total++;
            if (Character.isLetterOrDigit(c)) alnum++;
        }
        return total >= 2 && (double) alnum / total > 0.80;
    }

    // ── Dimension 4: background cleanliness ─────────────────────────────────

    /** @return {@code {noiseSigma, tileMeanSigma}} in gray levels */
    private static double[] backgroundSigma(int[] gray, int w, int h, boolean[] textRegion,
                                            SauvolaMask.Result mask) {
        int tilesX = Math.max(1, (w + BG_TILE - 1) / BG_TILE);
        int tilesY = Math.max(1, (h + BG_TILE - 1) / BG_TILE);
        double[] tileSum = new double[tilesX * tilesY];
        long[] tileN = new long[tilesX * tilesY];
        double nSum = 0, nSum2 = 0;
        long nCount = 0;

        for (int y = 0; y < h; y++) {
            int row = y * w;
            int ty = Math.min(tilesY - 1, y / BG_TILE);
            for (int x = 0; x < w; x++) {
                int i = row + x;
                if (textRegion[i] || mask.foreground()[i]) continue;
                int tx = Math.min(tilesX - 1, x / BG_TILE);
                int t = ty * tilesX + tx;
                tileSum[t] += gray[i];
                tileN[t]++;

                double residue = gray[i] - median3(gray, w, h, x, y);
                nSum += residue;
                nSum2 += residue * residue;
                nCount++;
            }
        }

        double noiseSigma = 0;
        if (nCount > 1) {
            double mean = nSum / nCount;
            noiseSigma = Math.sqrt(Math.max(0.0, nSum2 / nCount - mean * mean));
        }

        double tileMean = 0, tileMean2 = 0;
        int used = 0;
        for (int t = 0; t < tileN.length; t++) {
            if (tileN[t] < BG_TILE * BG_TILE / 4) continue;
            double m = tileSum[t] / tileN[t];
            tileMean += m;
            tileMean2 += m * m;
            used++;
        }
        double tileSigma = 0;
        if (used > 1) {
            double mean = tileMean / used;
            tileSigma = Math.sqrt(Math.max(0.0, tileMean2 / used - mean * mean));
        }

        return new double[]{noiseSigma, tileSigma};
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    /** OCR words with boxes that are actually usable for glyph-local metrics. */
    static List<TextBlock> usableBoxes(List<TextBlock> blocks, int w, int h) {
        List<TextBlock> out = new ArrayList<>();
        if (blocks == null) return out;
        for (TextBlock tb : blocks) {
            Rectangle b = tb.getBbox();
            if (b == null || b.width < 4 || b.height < 3) continue;
            if (b.x < 0 || b.y < 0 || b.x + b.width > w || b.y + b.height > h) continue;
            if (b.width > 0.8 * w || b.height > 0.8 * h) continue;
            out.add(tb);
        }
        return out;
    }

    /** Union of the glyph boxes: where text is expected to live. */
    static boolean[] textRegionMask(List<TextBlock> boxes, int w, int h) {
        boolean[] region = new boolean[w * h];
        for (TextBlock tb : boxes) {
            Rectangle b = tb.getBbox();
            int x1 = Math.min(w, b.x + b.width), y1 = Math.min(h, b.y + b.height);
            for (int y = Math.max(0, b.y); y < y1; y++) {
                int row = y * w;
                for (int x = Math.max(0, b.x); x < x1; x++) region[row + x] = true;
            }
        }
        return region;
    }

    private static int alphanumericLength(String s) {
        if (s == null) return 0;
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            if (Character.isLetterOrDigit(s.charAt(i))) n++;
        }
        return n;
    }

    private static int[] histSample(int[] hist, int n) {
        int[] out = new int[n];
        int k = 0;
        for (int v = 0; v < 256; v++) {
            for (int c = 0; c < hist[v] && k < n; c++) out[k++] = v;
        }
        return out;
    }

    private static double median3(int[] gray, int w, int h, int cx, int cy) {
        int x0 = Math.max(0, cx - 1), x1 = Math.min(w - 1, cx + 1);
        int y0 = Math.max(0, cy - 1), y1 = Math.min(h - 1, cy + 1);
        double[] win = new double[9];
        int k = 0;
        for (int y = y0; y <= y1; y++) {
            for (int x = x0; x <= x1; x++) win[k++] = gray[y * w + x];
        }
        Arrays.sort(win);
        return win[4];
    }

    private static double median(List<Double> values) {
        double[] a = new double[values.size()];
        for (int i = 0; i < a.length; i++) a[i] = values.get(i);
        Arrays.sort(a);
        return a[a.length / 2];
    }

    private static double clamp01(double v) {
        if (Double.isNaN(v)) return 0.0;
        return Math.max(0.0, Math.min(1.0, v));
    }
}
