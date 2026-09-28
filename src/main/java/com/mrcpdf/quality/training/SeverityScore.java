package com.mrcpdf.quality.training;

/**
 * Normalizes a {@link ScannerProfile} into per-effect severities in [0,1] —
 * the generator-side degradation "intent" used by {@link ScannerTier#draw} to
 * aim a tier at its archetype center and by {@link SyntheticCorpus#variantName}
 * for human-readable variant names.
 *
 * <p>The corpus's actual quality label is NOT derived from these severities
 * anymore: each page is <em>measured</em> by OCR against its known text
 * ({@link ReadabilityOcr}, score = 1 - word error rate), and
 * {@code label = score < LABEL_CUTOFF}.  The composite {@code severity()} sum
 * was retired because hand-weighted parameters do not track what a reader
 * actually sees (see notes/ocr-readable-score.md).
 */
public final class SeverityScore {

    /**
     * Readability cutoff: pages whose OCR-measured readability score falls
     * below this skip MRC.  The threshold has a plain reading — label-1 means
     * OCR recovers under 60% of the printed words — and is chosen so the
     * minority class stays well above the 10-20 events/feature floor for the
     * 11-feature gate model (see notes/ocr-readable-score.md).
     */
    public static final double LABEL_CUTOFF = 0.60;

    // Per-effect realistic maxima (amounts that make a page clearly unreadable).
    static final double MAX_BLUR = 2.8;
    static final double MAX_MOTION = 12.0;
    static final double MAX_NOISE = 12.0;
    static final double MAX_SALT_PEPPER = 0.004;
    static final double MAX_FIXED_PATTERN = 0.35;
    static final double MAX_JPEG_DROP = 0.55;
    static final double MAX_ILLUM = 0.35;
    static final double MAX_PAPER = 0.18;
    static final double MAX_BLEED = 0.12;
    static final double MAX_STREAKS = 8.0;
    static final double MAX_DUST = 6.0;
    static final double MAX_SKEW = 1.8;
    static final double MAX_CURL = 0.25;
    static final double MAX_BLACK_LEVEL = 0.25;
    static final double MAX_WHITE_LEVEL = 0.30;
    static final double MAX_GAMMA_LN = Math.log(1.4);
    static final double MAX_INK = 2.0;

    /** Per-effect weights; must sum to 1.0. */
    static final double W_BLUR = 0.18;
    static final double W_MOTION = 0.04;
    static final double W_SCALE = 0.18;
    static final double W_INK = 0.13;
    static final double W_CONTRAST = 0.12;
    static final double W_NOISE = 0.08;
    static final double W_JPEG = 0.06;
    static final double W_ILLUM = 0.06;
    static final double W_PAPER = 0.045;
    static final double W_BLEED = 0.04;
    static final double W_STREAKS = 0.035;
    static final double W_SKEW = 0.02;
    static final double W_CURL = 0.01;

    private SeverityScore() {
    }

    /** Per-effect severity (0 = absent, 1 = maximal), keyed as (blur, motion, scale, ink, contrast, noise, jpeg, illum, paper, bleed, streaks, skew, curl). */
    public record Severities(double blur, double motion, double scale, double ink, double contrast,
                             double noise, double jpeg, double illum, double paper, double bleed,
                             double streaks, double skew, double curl) {
    }

    public static Severities severities(ScannerProfile p) {
        double blur = clamp(p.blurSigma() / MAX_BLUR);
        double motion = clamp(p.motionBlurLen() / MAX_MOTION);
        double scale = clamp(2 * (1 - p.downscale()));
        double ink = clamp(Math.abs(p.inkSpread()) / MAX_INK);
        double contrast = Math.max(
                Math.max(clamp(p.blackLevel() / MAX_BLACK_LEVEL),
                        clamp(p.whiteLevel() / MAX_WHITE_LEVEL)),
                clamp(Math.abs(Math.log(p.gamma())) / MAX_GAMMA_LN));
        double noise = 0.6 * clamp(p.noiseSigma() / MAX_NOISE)
                + 0.2 * clamp(p.saltPepperFrac() / MAX_SALT_PEPPER)
                + 0.2 * clamp(p.fixedPattern() / MAX_FIXED_PATTERN);
        double jpeg = clamp((1 - p.jpegQuality()) / MAX_JPEG_DROP);
        double illum = clamp(p.illumination() / MAX_ILLUM);
        double paper = clamp(p.paperOpacity() / MAX_PAPER);
        double bleed = clamp(p.bleedThrough() / MAX_BLEED);
        double streaks = 0.7 * clamp(p.streakCount() / MAX_STREAKS)
                + 0.3 * clamp(p.dustCount() / MAX_DUST);
        double skew = clamp(p.skewDeg() / MAX_SKEW);
        double curl = clamp(p.curl() / MAX_CURL);
        return new Severities(blur, motion, scale, ink, contrast, noise, jpeg,
                illum, paper, bleed, streaks, skew, curl);
    }

    /** Weighted degradation severity in [0,1]; retired as a quality label (see class doc). */
    public static double severity(ScannerProfile p) {
        Severities s = severities(p);
        return W_BLUR * s.blur() + W_MOTION * s.motion() + W_SCALE * s.scale()
                + W_INK * s.ink() + W_CONTRAST * s.contrast() + W_NOISE * s.noise()
                + W_JPEG * s.jpeg() + W_ILLUM * s.illum() + W_PAPER * s.paper()
                + W_BLEED * s.bleed() + W_STREAKS * s.streaks() + W_SKEW * s.skew()
                + W_CURL * s.curl();
    }

    /** Binary gate prior: 1 (skip MRC) when the measured readability falls below the cutoff. */
    public static int label(double score) {
        return score < LABEL_CUTOFF ? 1 : 0;
    }

    private static double clamp(double v) {
        return Math.max(0, Math.min(1, v));
    }
}