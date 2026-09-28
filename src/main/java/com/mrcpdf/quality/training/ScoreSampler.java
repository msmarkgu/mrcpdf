package com.mrcpdf.quality.training;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.Random;

import com.mrcpdf.quality.model.MrcReadiness;

/**
 * Steers a page onto a chosen {@code R_mrc} by degrading exactly one dimension
 * and leaving the other two near-clean.
 *
 * <p>This replaces tier-center aiming, which drew degradation parameters
 * uniformly in an 18-parameter {@link ScannerProfile} space and hoped the
 * measured scores spread out.  They do not: the parameter-to-perception map is
 * nonlinear and saturating, so the corpus collapsed into "too clear or too
 * blur" with an empty middle -- and the empty middle is exactly the band the
 * quality gate has to decide on.  Drawing in measured-score space instead is
 * what fills it (see {@code notes/simulate-realworld-scans.md}).
 *
 * <p><b>Why one dimension.</b>  {@code R_mrc} is a {@code min()} over its four
 * sub-scores, so the only way to reach a mid value is to push one sub-score down
 * to it while the others stay high.  That is also how real scans fail: soft
 * focus, faint-on-dirty-paper, bleed-through -- not "a bit of everything".
 * Uniformly scaling every degradation to hit a target is what emptied the
 * middle band in the first place.
 *
 * <p><b>Why the re-aim loop, and not just the calibration table.</b>  The
 * dimensions are physically coupled.  Fading the page to destabilize the
 * binarization also lowers measured contrast, and additive noise both
 * fragments strokes and dirties the paper.  So the table gets the page into the
 * right neighborhood and the loop finishes the job: it re-measures, finds
 * whichever sub-score is currently the binding minimum, and moves
 * <em>that</em> dimension's driver.  Because the other sub-scores are by
 * definition at or above the minimum, raising the binding one toward the target
 * cannot be foiled by a different dimension silently dropping below it first --
 * and if one does, the next iteration binds on that one instead.
 */
public final class ScoreSampler {

    /** Bottom of the target marginal. Below this a page is destroyed, not merely bad. */
    public static final double DEFAULT_LO = 0.20;
    /** Top of the target marginal. A clean page scores near 1.0, so 0.95 fills the bin. */
    public static final double DEFAULT_HI = 0.95;
    /** Accept a page whose measured steering value lands this close to the target. */
    public static final double DEFAULT_TOLERANCE = 0.05;
    /**
     * Renders per page.  The first attempt comes from the calibration table, so
     * six is generous; a page that has not converged by then is accepted as a
     * miss and counted, rather than retried forever.
     */
    public static final int DEFAULT_MAX_ATTEMPTS = 6;
    /** Proportional step floor, so a severity near 0 still moves and one near 1 still resolves. */
    static final double MIN_STEP = 0.08;
    static final double STEP_GAIN = 0.5;

    private final double tolerance;
    private final int maxAttempts;

    public ScoreSampler() {
        this(DEFAULT_TOLERANCE, DEFAULT_MAX_ATTEMPTS);
    }

    public ScoreSampler(double tolerance, int maxAttempts) {
        this.tolerance = tolerance;
        this.maxAttempts = Math.max(1, maxAttempts);
    }

    public double tolerance() {
        return tolerance;
    }

    public int maxAttempts() {
        return maxAttempts;
    }

    /**
     * Everything one render produced, so the accepted attempt can be reused
     * whole instead of measured a second time.
     *
     * <p>The caller embeds and labels from this, which is what keeps the steer
     * value, the label and the features describing the same pixels.  Measuring
     * once for the sampler and again for the label would either double the OCR
     * cost or, worse, let the two disagree.
     *
     * @param image the degraded raster, before the delivery JPEG
     * @param jpeg  the encoded stream and the pixels it decodes to; the
     *              measurement is taken on {@code decoded()} because JPEG is
     *              lossy and the delivered page is the decoded form
     */
    public record Rendered(BufferedImage image, JpegCodec.FinalImage jpeg,
                           ReadabilityOcr.Result ocr, MrcReadiness.Raw raw,
                           MrcReadiness.Breakdown mrc) {
    }

    /**
     * Renders one profile and reports what it measured.
     *
     * <p>Every attempt costs a full OCR pass, so this is the sampler's unit of
     * work and the reason the calibration table exists.
     */
    public interface Render {
        Rendered render(ScannerProfile profile, Random rng) throws IOException;
    }

    /**
     * What corpus QA needs to know about a steered row, and nothing more.
     *
     * <p>Deliberately not {@link Sample}: a sample holds its {@link Rendered}, and
     * a rendered page is about a megabyte, so keeping the samples to build a
     * histogram at the end of a run would retain half a gigabyte of rasters that
     * were already written to disk. QA reads four numbers per row.
     */
    public interface SteeringRow {

        /** The steering value measured on the page. */
        double steer();

        /** The value the sampler aimed at. */
        double target();

        /** Whether the page converged onto {@link #target()}. */
        boolean onTarget();

        /** Class index from the full {@code R_mrc}: 0 good, 1 middle, 2 bad. */
        int cls();
    }

    /**
     * The dimension a page is steered by.
     *
     * <p>Kept in physical terms rather than as an enum of parameters, so the
     * calibration table, the variant name and the failure report all speak about
     * the same thing the design doc calls {@code S_visual} / {@code S_mask} /
     * {@code S_bg}.
     */
    public enum Dim {
        /** Soft focus and lost resolution: sharpness, contrast, stroke consistency. */
        VISUAL("visual"),
        /** Faint or stroke-unstable toner: binarization margin and fragmentation. */
        MASK("mask"),
        /** Dirty paper: noise, debris, stains, bleed-through, uneven illumination. */
        BG("bg");

        private final String slug;

        Dim(String slug) {
            this.slug = slug;
        }

        public String slug() {
            return slug;
        }

        /** The sub-score of a measurement this dimension is responsible for. */
        public double of(MrcReadiness.Breakdown b) {
            return switch (this) {
                case VISUAL -> b.sVisual();
                case MASK -> b.sMask();
                case BG -> b.sBg();
            };
        }

        /** Which dimension is currently the minimum, i.e. which one caps R_mrc. */
        public static Dim binding(MrcReadiness.Breakdown b) {
            double v = b.sVisual(), k = b.sMask(), g = b.sBg();
            if (v <= k && v <= g) return VISUAL;
            return k <= g ? MASK : BG;
        }
    }

    /**
     * One page's steered result.
     *
     * @param onTarget whether the measured steering value landed within
     *                 {@code tolerance} of the target; a {@code false} here is a
     *                 page whose target this dimension simply cannot reach, and
     *                 the corpus QA counts them rather than hiding them
     * @param attempts how many renders the page actually cost, <em>not</em> which
     *                 attempt produced the accepted image.  The accepted image is
     *                  the best of the lot, so on a page that converged late this
     *                  is larger than the winning index, and it is the number
     *                  that predicts the cost of a corpus run.
     */
    public record Sample(Rendered rendered, Dim aimed, Dim binding, double target,
                         double steer, int attempts, boolean onTarget)
            implements SteeringRow {

        /** Distance the page ended up from its target. */
        public double error() {
            return Math.abs(steer - target);
        }

        @Override
        public int cls() {
            return switch (rendered.mrc().tier()) {
                case "good" -> 0;
                case "middle" -> 1;
                default -> 2;
            };
        }
    }

    /**
     * The value the sampler steers on: the minimum of the three degradable
     * dimensions, gated by whether the page yielded any glyph boxes at all.
     *
     * <p>The gate is not optional.  {@link MrcReadiness}'s full {@code kappa}
     * includes {@code S_ocr}, which is already floored to zero when OCR finds
     * nothing, so the composite is safe.  This metric deliberately excludes
     * {@code S_ocr} (its inputs are fabricated confidences on a synthetic page),
     * which removes that protection -- and the glyph-local metrics it does use
     * are exactly the ones that degenerate over an empty box set.  Stroke-width
     * consistency scores 1.0 on a sample of no strokes and Sauvola margin
     * scores 1.0 on a sample of no ambiguous pixels, so a page degraded past
     * recognition would report a steering value near the clean end and the
     * sampler would happily accept a destroyed page.  A page with no readable
     * text is destroyed, so it reports zero here and the re-aim backs off until
     * words reappear.
     */
    public static double steerKappa(MrcReadiness.Breakdown b) {
        if (b.glyphBoxes() < MrcReadiness.MIN_GLYPH_BOXES) return 0.0;
        return Math.min(b.sVisual(), Math.min(b.sMask(), b.sBg()));
    }

    // ── Driver mapping ───────────────────────────────────────────────────────

    /**
     * The realism jitter applied to every steered page regardless of which
     * dimension is being attacked.
     *
     * <p>Mid-band pages have to look like scans, not like the output of a
     * single-effect filter, so motion blur, JPEG, skew and curl ride along at a
     * small fixed severity.  It is drawn <em>once per page</em> and held fixed
     * across the re-aim attempts: re-rolling it every attempt would add texture
     * noise to the measurement and the loop would be chasing a moving target
     * instead of root-finding along one axis.
     *
     * <p>The geometric warp is kept small rather than zeroed.  Dropping skew and
     * curl would leave every steered page a clean rectangle, which is the "too
     * clean" failure this design exists to avoid, and it costs the 3 conditional
     * text features their box alignment -- a cost already reported around, since
     * {@code RealTrainEval} fits an 11-feature image-only baseline for exactly
     * that reason.
     */
    public record Texture(double motion, float jpegQuality, double illumination,
                          double skewDeg, double curl) {

        public static Texture draw(Random rng) {
            return new Texture(
                    SeverityScore.MAX_MOTION * (0.10 + 0.25 * rng.nextDouble()),
                    (float) (1.0 - SeverityScore.MAX_JPEG_DROP * (0.10 + 0.20 * rng.nextDouble())),
                    SeverityScore.MAX_ILLUM * (0.10 + 0.25 * rng.nextDouble()),
                    (rng.nextBoolean() ? 1 : -1) * SeverityScore.MAX_SKEW * (0.10 + 0.20 * rng.nextDouble()),
                    (rng.nextBoolean() ? 1 : -1) * SeverityScore.MAX_CURL * (0.10 + 0.20 * rng.nextDouble()));
        }
    }

    /**
     * Builds the profile that attacks {@code dim} at {@code severity} in [0,1]
     * and leaves the other two dimensions near their clean values.
     *
     * <p>The parameter choice per dimension follows the design doc's vocabulary
     * table, with one addition worth stating: {@link Dim#BG} deliberately leaves
     * tone and scale alone.  Those are the other dimensions' drivers, and a page
     * whose background noise arrives with a contrast crush has had two
     * dimensions moved at once -- which is precisely the "a bit of everything"
     * the single-dimension rule exists to avoid.  The same reasoning is why
     * {@code MrcCalibration} anchors {@code speckle} on a debris page with no
     * downscale and no tonal change.
     */
    public static ScannerProfile profileFor(Dim dim, double severity, Texture tex) {
        double s = clamp01(severity);
        double blur = 0, black = 0, white = 0;
        int inkSpread = 0;
        double paper = 0, bleed = 0, noise = 0, saltPepper = 0, fixed = 0, illum = 0;
        int streaks = 0, dust = 0;

        switch (dim) {
            case VISUAL -> {
                // Optics only.  Widest calibrated band of the three (blur residual
                // 7.0 on a clean scan down to 2.0 on a photocopy) and the one
                // that moves the fewest other metrics.
                blur = SeverityScore.MAX_BLUR * s;
            }
            case MASK -> {
                // Fading lifts the stroke pixels toward the Sauvola threshold,
                // so they land inside the margin tolerance and the binarization
                // starts dropping and re-growing them.  Faint ink (a negative
                // ink spread, i.e. an erode) thins the strokes so the same
                // happens at the pixel level, which is what drives mFrag.
                white = SeverityScore.MAX_WHITE_LEVEL * s;
                if (s > 0.55) inkSpread = -1;
            }
            case BG -> {
                paper = SeverityScore.MAX_PAPER * s;
                bleed = SeverityScore.MAX_BLEED * s;
                noise = 3.0 * s;
                saltPepper = SeverityScore.MAX_SALT_PEPPER * s;
                fixed = SeverityScore.MAX_FIXED_PATTERN * s;
                illum = SeverityScore.MAX_ILLUM * s;
                streaks = (int) Math.round(SeverityScore.MAX_STREAKS * 0.7 * s);
                dust = (int) Math.round(SeverityScore.MAX_DUST * s);
            }
        }

        return new ScannerProfile(
                tex.curl(), tex.skewDeg(), 1.0, blur, tex.motion(),
                Math.max(illum, tex.illumination()), paper, bleed, noise, saltPepper,
                fixed, streaks, dust, black, white, 1.0, inkSpread, tex.jpegQuality());
    }

    // ── The sampler ───────────────────────────────────────────────────────────

    /**
     * Draws a target from the uniform marginal over [lo, hi].
     *
     * <p>Uniform in {@code R_mrc} rather than in degradation parameters, and the
     * reason is the same one that makes the sampler necessary: uniform in
     * parameter space is not uniform in measured-score space.  It also balances
     * the classes by construction -- [0.70, 0.95], [0.45, 0.70] and [0.20, 0.45]
     * are equal thirds of the range, against the 116/70/314 split an unsteered
     * tier draw produces.
     */
    public static double drawTarget(Random rng, double lo, double hi) {
        if (hi <= lo) return clamp01(lo);
        return clamp01(lo + (hi - lo) * rng.nextDouble());
    }

    /**
     * Renders and re-aims until the page lands on {@code target} or the attempt
     * budget runs out.
     *
     * <p>A miss is returned, not thrown: a page whose target one dimension cannot
     * reach is still a usable training row, and silently dropping it would bias
     * the corpus toward whichever dimensions happen to have wide ranges.  The
     * {@link Sample#onTarget()} flag lets the corpus QA publish the miss rate.
     *
     * @param cal first-guess driver severities; {@code null} falls back to
     *            bisection from a neutral start, which costs roughly twice as
     *            many renders
     * @param rng  a stream owned by the caller for this page alone.  It must not
     *             be the stream the unsteered path uses, or {@code --no-steer}
     *             stops being bit-reproducible.
     */
    public Sample sample(Render render, Random rng, double target, Dim dim,
                         ScoreCalibration.Calibration cal) throws IOException {
        Texture tex = Texture.draw(rng);
        double target01 = clamp01(target);

        double severity = cal == null ? 0.5 : cal.severityFor(dim, target01);
        Dim aimed = dim;
        Sample best = null;
        int made = 0;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            ScannerProfile profile = profileFor(aimed, severity, tex);
            Rendered r = render.render(profile, rng);
            made = attempt;
            double steer = steerKappa(r.mrc());
            double err = Math.abs(steer - target01);
            Dim binding = Dim.binding(r.mrc());

            // `made` rather than `attempt`: the two are the same until the loop
            // runs past a better attempt, and past that point reporting the
            // winner's index would understate what the page cost by up to
            // maxAttempts-1 renders.
            if (best == null || err < best.error()) {
                best = new Sample(r, aimed, binding, target01, steer, made, err <= tolerance);
            }
            if (err <= tolerance) {
                return withAttempts(best, made);
            }

            // Re-aim on whichever dimension is actually capping the page, not the
            // one we started from: the driver we chose may have been dragged under
            // by a side effect (fading also costs contrast, noise also fragments
            // strokes), in which case turning its own knob cannot help.  The
            // table's guess is the right starting point for the new dimension --
            // carrying over a severity tuned for a different parameter mapping
            // would just be a worse guess than the one already in hand.
            if (binding != aimed) {
                aimed = binding;
                severity = cal == null ? severity : cal.severityFor(aimed, target01);
                continue;
            }

            // `aimed` is the binding dimension, so `steer` is that dimension's own
            // sub-score: above the target means it needs *more* degradation, below
            // means it needs less.  Note this is the opposite sense to `steer`
            // alone when the aimed dimension is not binding -- which is why the
            // re-aim above has to `continue` rather than fall through to here.
            double step = Math.max(MIN_STEP, STEP_GAIN * severity);
            severity = clamp01(steer > target01 ? severity + step : severity - step);
        }

        return withAttempts(best, made);
    }

    private static Sample withAttempts(Sample s, int made) {
        return s.attempts() == made ? s
                : new Sample(s.rendered(), s.aimed(), s.binding(), s.target(), s.steer(),
                made, s.onTarget());
    }

    static double clamp01(double v) {
        if (Double.isNaN(v)) return 0.0;
        return Math.max(0.0, Math.min(1.0, v));
    }

    // ── Per-run steering configuration ────────────────────────────────────────

    /**
     * The steering settings for a corpus run, plus the calibration they imply.
     *
     * <p>Shared by reference across every page and every worker of a run.  The
     * curves are cached rather than re-probed because a probe costs
     * {@code 3 * steps} OCR passes: per shard it would dominate the build, and
     * per page it would cost more than the sampling it is meant to accelerate.
     * The first worker to need a curve probes it against its own page and the
     * rest reuse it.
     *
     * <p>That makes the curves a property of the run rather than of the page, which
     * is a real approximation: a dense legal page and a sparse one have different
     * blur responses.  It is the right trade here because the curves only supply
     * the <em>first guess</em> -- the re-aim loop measures the page in front of it
     * and corrects -- so a slightly-off guess costs an extra render rather than a
     * wrong page.
     */
    public static final class Steering {

        private final boolean enabled;
        private final double lo;
        private final double hi;
        private final ScoreSampler sampler;
        private final Dim[] dims;
        private volatile ScoreCalibration.Calibration calibration;

        public Steering(boolean enabled, double lo, double hi, double tolerance,
                        int maxAttempts, Dim[] dims) {
            this.enabled = enabled;
            this.lo = lo;
            this.hi = hi;
            this.sampler = new ScoreSampler(tolerance, maxAttempts);
            this.dims = dims.clone();
        }

        /** Steering off: the corpus is built exactly as it was before this existed. */
        public static Steering off() {
            return new Steering(false, DEFAULT_LO, DEFAULT_HI, DEFAULT_TOLERANCE,
                    DEFAULT_MAX_ATTEMPTS, Dim.values());
        }

        public boolean enabled() {
            return enabled;
        }

        public double lo() {
            return lo;
        }

        public double hi() {
            return hi;
        }

        public ScoreSampler sampler() {
            return sampler;
        }

        /** The calibration, or null if no page has needed one yet. */
        public ScoreCalibration.Calibration calibration() {
            return calibration;
        }

        /** Draws this page's target and the dimension to steer it by. */
        public double drawTarget(Random rng) {
            return ScoreSampler.drawTarget(rng, lo, hi);
        }

        public Dim drawDim(Random rng) {
            return dims[rng.nextInt(dims.length)];
        }

        /**
         * Probes the curves once per run, against whichever page asks first.
         *
         * <p>Synchronized because corpus runs are multi-worker and the probe is
         * not idempotent in wall-clock terms -- a race here would pay for several
         * calibrations concurrently, each competing for the same Tesseract
         * processes the shards are using.
         */
        public synchronized ScoreCalibration.Calibration calibrate(ScoreSampler.Render render,
                                                                    Random rng, int steps,
                                                                    String reference)
                throws IOException {
            if (calibration == null) {
                calibration = ScoreCalibration.probe(render, rng, steps, reference);
            }
            return calibration;
        }
    }
}
