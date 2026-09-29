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

    /**
     * Bottom of the target marginal.
     *
     * <p>This was 0.20, chosen back when the target was the R_mrc composite. The
     * label is now the audited word F1, and the floor had to move with it: on a
     * 375-row build the hit rate rises monotonically with the target, from 50% in
     * the 0.20-0.30 band to 84% in 0.70-0.80. The bottom of the marginal is where
     * text is genuinely near-unreadable, which for most pages means the words are
     * gone rather than degraded -- there is no severity that lands Tesseract's word
     * F1 on an exact value, only a range where it collapses. The lowest label any
     * page reached was 0.054, so this is not a limit of the generator; it is where
     * the response is dense enough to hit. Below that the marginal asks for
     * precision the audit cannot deliver, and the misses are re-aim failures
     * rather than unreachable targets.
     *
     * <p>Raised 0.20 -> 0.30 -> 0.40 across three builds. The hit rate rises
     * monotonically with the target throughout -- 58% at 0.30-0.40 against 80% at
     * 0.70-0.80 -- so every band added to the bottom is a band the sampler
     * struggles to fill, and the marginal is better spent on the range it can
     * hit. At 0.40 the build leaves 303 steered pages, which is still ample, and
     * the clean variant of every base page covers the pristine end at ~0.99, so
     * nothing is lost from the corpus by not degrading pages into illegibility.
     */
    public static final double DEFAULT_LO = 0.40;
    /**
     * Top of the target marginal, set by what the generator can actually deliver.
     *
     * <p>This was 0.95, on the reasoning that a clean page scores near 1.0 so the
     * top bin would be fillable. Measured on a 10-base-page slice, no degraded page
     * exceeded 0.807: targets of 0.83, 0.83, 0.85 and 0.90 all landed between 0.72
     * and 0.76 and were recorded as misses. The realism texture -- motion blur, JPEG
     * quantization, skew, curl -- is applied to every degraded page on purpose, and
     * it puts a ceiling under how clean one can measure. Asking for a 0.90 degraded
     * page is not a hard target, it is a spec the simulator cannot meet, and it
     * spent a fifth of the attempt budget proving it.
     *
     * <p>The pristine end of the range is not a gap: the clean variant of every base
     * page measures around 0.90, which is what that end of the corpus is for.
     *
     * <p>Overridable with {@code --steer-hi} for anyone deliberately asking for
     * out-of-range targets and willing to take the misses.
     */
    public static final double DEFAULT_HI = 0.80;
    /**
     * Accept a page whose measured steering value lands this close to the target.
     *
     * <p>0.10, not the 0.05 this was before the label became the audit. A 0.05
     * band is 3% of a word multiset, and the response is not that precise: pages
     * differ in how far their text degrades at a given severity, so the final
     * error over a 375-row build had a median of 0.063 and only 43% of rows
     * inside 0.05. Chasing the tighter band did not find better targets, it
     * spent the attempt budget (4.9 renders per page, up from 3.4) and still
     * missed 57% of the time. The band has to be wider than the page-to-page
     * spread to mean anything, and 0.10 is where it stops being noise.
     */
    public static final double DEFAULT_TOLERANCE = 0.10;
    /**
     * Renders per page.  The first attempt comes from the calibration table, so
     * six is generous; a page that has not converged by then is accepted as a
     * miss and counted, rather than retried forever.
     */
    public static final int DEFAULT_MAX_ATTEMPTS = 6;
    /** Proportional step floor, so a severity near 0 still moves and one near 1 still resolves. */
    static final double MIN_STEP = 0.08;
    /** Ceiling on the score-space minimum step, so a near-flat response cannot ask
     *  for a severity jump across the whole range. */
    static final double MAX_STEP = 0.5;
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
    public record Sample(Rendered rendered, Dim drawn, Dim aimed, Dim binding, double target,
                         double steer, int attempts, boolean onTarget)
            implements SteeringRow {

        /** Distance the page ended up from its target. */
        public double error() {
            return Math.abs(steer - target);
        }

        /**
         * Whether the re-aim loop moved the page off the dimension it was drawn on.
         *
         * <p>Worth recording rather than inferring: a page drawn as background
         * noise that the loop re-aimed onto visual has degradation in both
         * dimensions, which is the "a bit of everything" the single-dimension rule
         * exists to avoid. Reporting only the converged dimension would make such
         * a page look cleanly single-axis, so the corpus would appear to satisfy
         * the premise when it does not.
         */
        public boolean reaimed() {
            return drawn != aimed;
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
     * The steering value: the page's audited word F1, i.e. the same quantity the
     * corpus is labelled with.
     *
     * <p>This used to be the minimum of the visual, mask and background
     * sub-scores, and that was a mistake that only the corpus build could have
     * revealed. Steering {@code S_visual} down to 0.2 produced a soft, blurry,
     * perfectly legible page: the sub-score is a contrast and sharpness
     * statistic, and at the low end it reports "bad" about a page that OCRs
     * flawlessly. Over the finished 500-row corpus the correlation between
     * {@code S_visual} and the audited word F1 had collapsed to +0.10, against
     * +0.747 in the corpus before steering, and 57 of 93 rows labelled
     * unreadable were readable. The sampler had done its job exactly; the target
     * was not a measure of the thing being labelled.
     *
     * <p>So the value is now the audit itself, which is what makes the label
     * unfalsifiable in the useful direction: the corpus is stratified by ground
     * truth rather than by a proxy that can drift from it. This costs nothing
     * -- the audit falls out of the same Tesseract call that produces
     * {@code S_ocr}, and the rendered page already carries it.
     *
     * <p>The gate stays, for the case the audit cannot cover. A page whose OCR
     * found nothing has no words to compare, so the audit is undefined rather
     * than zero; treating that as anything but destroyed would let the sampler
     * accept a page with no readable text. A page with no readable text scores
     * zero, so the re-aim backs off until words reappear.
     */
    public static double steerValue(ReadabilityOcr.Result ocr, MrcReadiness.Breakdown b) {
        if (b.glyphBoxes() < MrcReadiness.MIN_GLYPH_BOXES) return 0.0;
        double audit = ocr.getAuditScore();
        if (!Double.isFinite(audit)) return 0.0;
        return clamp01(audit);
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

        // A bracket in severity, and the last two measurements for a secant step.
        //
        // The response is monotone -- more severity, lower score -- so the target
        // is a root find and not a search. The rule this replaces took a step
        // proportional to severity, which cannot see how far off the page is: off
        // by 0.4 and off by 0.02 moved the same distance, and near a high-severity
        // target the step stayed around 0.45, so the page oscillated across the
        // target instead of settling on it. On the 375-row corpus that showed up
        // as a flat tail of pages exhausting all six attempts, 25% of them, and as
        // a top bin holding 16 rows where 20 were required.
        double lo = -1.0, hi = 2.0;
        double lastSev = Double.NaN, lastSteer = Double.NaN;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            ScannerProfile profile = profileFor(aimed, severity, tex);
            Rendered r = render.render(profile, rng);
            made = attempt;
            double steer = steerValue(r.ocr(), r.mrc());
            double err = Math.abs(steer - target01);
            Dim binding = Dim.binding(r.mrc());

            // `made` rather than `attempt`: the two are the same until the loop
            // runs past a better attempt, and past that point reporting the
            // winner's index would understate what the page cost by up to
            // maxAttempts-1 renders.
            if (best == null || err < best.error()) {
                best = new Sample(r, dim, aimed, binding, target01, steer, made, err <= tolerance);
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
            // Only follow the binding dimension if it can actually serve the
            // target. Otherwise there is nowhere better to go: the knob that
            // capped the page is useless, and the one in hand is the only
            // remaining way to move the score at all.
            //
            // Skipping this check is how the reachability filter above gets
            // undone one render later. A page whose background stayed clean
            // reports VISUAL as binding -- a fully visual-degraded page still
            // measures 0.892, while an untouched background measures 0.998 -- so
            // the loop would re-aim onto visual and then spend every remaining
            // attempt turning a knob that cannot descend past 0.892, on a target
            // drawn from [0.40, 0.80]. That is where the surviving overshoot
            // misses came from: the page stayed far *above* the target it was
            // supposed to be degraded into.
            boolean canReach = cal == null || cal.curveFor(binding).reachable(target01);
            if (binding != aimed && canReach) {
                aimed = binding;
                severity = cal == null ? severity : cal.severityFor(aimed, target01);
                // The bracket was measured through a different dimension's drivers,
                // so it says nothing about this one. Carrying it over would pin the
                // search into a range the new dimension may not even reach.
                lo = -1.0;
                hi = 2.0;
                lastSev = Double.NaN;
                lastSteer = Double.NaN;
                continue;
            }

            // `aimed` is the binding dimension, so `steer` is that dimension's own
            // sub-score: above the target means it needs *more* degradation, below
            // means it needs less.  Note this is the opposite sense to `steer`
            // alone when the aimed dimension is not binding -- which is why the
            // re-aim above has to `continue` rather than fall through to here.
            boolean tooClean = steer > target01;
            if (tooClean) lo = Math.max(lo, severity);
            else hi = Math.min(hi, severity);

            // The response's slope in score-per-severity, from the last two
            // measurements. Everything below is expressed through it, because the
            // target and the tolerance both live in score space while severity is
            // only the parameter we actually control.
            double slope = Double.isNaN(lastSev)
                    ? Double.NaN
                    : (steer - lastSteer) / (severity - lastSev);
            boolean haveSlope = Double.isFinite(slope) && slope != 0.0;

            double next;
            if (haveSlope) {
                // Secant through the last two measurements. Monotone responses make
                // this the cheap way to close in, and it is the step that actually
                // scales with the error.
                next = severity + (target01 - steer) / slope;
            } else {
                // No slope yet: step in the only direction that can help, and take
                // the table's own gain so the first move matches what the
                // calibration would have suggested.
                next = tooClean
                        ? severity + Math.max(MIN_STEP, STEP_GAIN * severity)
                        : severity - Math.max(MIN_STEP, STEP_GAIN * severity);
            }
            if (!Double.isFinite(next)) {
                next = severity + Math.signum(target01 - steer) * MIN_STEP;
            }

            // A secant can overshoot the bracket on a curved response, which would
            // throw away a bound already paid for. Only clamp against an end we have
            // actually measured: lo < 0 and hi > 1 are the "no bound yet" sentinels.
            if (lo >= 0.0 && next <= lo) {
                next = hi <= 1.0 ? lo + (hi - lo) / 2.0 : lo + MIN_STEP;
            }
            if (hi <= 1.0 && next >= hi) {
                next = lo >= 0.0 ? hi - (hi - lo) / 2.0 : hi - MIN_STEP;
            }

            // Never stand still, and never take a step too small to change the
            // score by the tolerance -- a zero-width step would burn the whole
            // attempt budget on one page instead of recording a miss.
            //
            // The floor is in score space, not severity space, and that distinction
            // is the whole fix. A fixed 0.08 in severity is 0.16 in score on a
            // response twice as steep as the identity, so it exceeds the tolerance
            // in both directions and the loop oscillates between two points it can
            // never land between. Converting the tolerance through the measured
            // slope gives the smallest step still worth taking.
            double minStep = haveSlope
                    ? Math.min(MAX_STEP, tolerance / Math.abs(slope))
                    : MIN_STEP;
            if (Math.abs(next - severity) < minStep) {
                next = severity + (tooClean ? minStep : -minStep);
            }

            lastSev = severity;
            lastSteer = steer;
            severity = clamp01(next);
        }

        return withAttempts(best, made);
    }

    private static Sample withAttempts(Sample s, int made) {
        return s.attempts() == made ? s
                : new Sample(s.rendered(), s.drawn(), s.aimed(), s.binding(), s.target(),
                s.steer(), made, s.onTarget());
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

        /** Test seam: install a calibration without probing for one. */
        void calibrateWithoutRendering(ScoreCalibration.Calibration cal) {
            this.calibration = cal;
        }

        /** The calibration, or null if no page has needed one yet. */
        public ScoreCalibration.Calibration calibration() {
            return calibration;
        }

        /** Draws this page's target and the dimension to steer it by. */
        public double drawTarget(Random rng) {
            return ScoreSampler.drawTarget(rng, lo, hi);
        }

        /**
         * Picks the dimension to attack, weighted by whether it can reach the
         * target at all.
         *
         * <p>Uniform was wrong once the target became the audit, because the
         * dimensions have very different floors. Degrading a page's visual
         * quality does almost nothing to word legibility -- the audit reads 0.892
         * on a fully visual-degraded page -- so aiming VISUAL at a low target
         * produced a page that stayed readable, and the sampler burned all six
         * attempts re-aiming a knob that cannot turn. On a 375-row build VISUAL
         * was drawn for 80% of pages and could not go below 0.241, while BG
         * reached 0.133; 109 of 137 misses landed <em>above</em> their target,
         * which is the signature of attacking a dimension that cannot degrade
         * legibility far enough.
         *
         * <p>So a dimension is only eligible if the calibration says its response
         * covers the target. With no calibration yet the choice stays uniform --
         * the table has not been measured, and guessing from priors would just
         * move the same problem somewhere less visible.
         */
        public Dim drawDim(Random rng, double target) {
            if (calibration == null || dims.length == 1) {
                return dims[rng.nextInt(dims.length)];
            }
            Dim[] eligible = new Dim[dims.length];
            int n = 0;
            for (Dim d : dims) {
                if (calibration.curveFor(d).reachable(target)) {
                    eligible[n++] = d;
                }
            }
            return n == 0 ? dims[rng.nextInt(dims.length)] : eligible[rng.nextInt(n)];
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

        /**
         * Installs a calibration built from several reference pages.
         *
         * <p>Preferred to {@link #calibrate} when the caller can see more than one
         * page, because a curve measured on one page is a single draw from how the
         * corpus responds, and the sampler then has to spend attempts correcting
         * for pages that are not that page.
         */
        public synchronized void calibrateFrom(java.util.List<ScoreCalibration.Calibration> parts) {
            if (calibration == null && !parts.isEmpty()) {
                calibration = ScoreCalibration.Calibration.merge(parts);
            }
        }
    }
}
