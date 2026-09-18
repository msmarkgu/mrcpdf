package com.mrcpdf.quality.training;

/**
 * Maps a {@link ScannerProfile} to the "synthetic quality" score described in
 * {@code notes/simulate-realworld-scans.md}.  Each effect's severity is
 * normalized to [0,1] against its realistic maximum and combined by perceptual
 * weights: foreground-impacting effects (blur, resolution loss, ink spread,
 * contrast) dominate over background-only corruption (noise, JPEG, shading,
 * paper, bleed, streaks, geometry), so crisp text on a dirty background keeps a
 * high score — the corpus's automatic hard negative.
 *
 * <p>Score = {@code 1 - Σ w·sev}; label = {@code score < LABEL_CUTOFF}.  The
 * score is a normalized quality in [0,1] (1 = pristine, 0 = unreadable).  The
 * {@link ScannerTier} draw uses the inverse of these formulas so the score
 * distribution of each tier lands near its archetype center.
 */
public final class SeverityScore {

    /**
     * Readability cutoff: pages below this synthetic quality skip MRC.  Tuned
     * against the tier draw distribution so the label boundary falls inside the
     * OLD_PHOTOCOPY tier and ≈45% of the degraded corpus lands label-1 (the
     * 10-20 events/feature target for the 11-feature gate model).
     */
    public static final double LABEL_CUTOFF = 0.58;

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

    /** Weighted degradation severity in [0,1]. */
    public static double severity(ScannerProfile p) {
        Severities s = severities(p);
        return W_BLUR * s.blur() + W_MOTION * s.motion() + W_SCALE * s.scale()
                + W_INK * s.ink() + W_CONTRAST * s.contrast() + W_NOISE * s.noise()
                + W_JPEG * s.jpeg() + W_ILLUM * s.illum() + W_PAPER * s.paper()
                + W_BLEED * s.bleed() + W_STREAKS * s.streaks() + W_SKEW * s.skew()
                + W_CURL * s.curl();
    }

    /** Synthetic quality score in [0,1]; 1.0 = pristine, 0.0 = unreadable. */
    public static double score(ScannerProfile p) {
        double s = severity(p);
        return clamp(1 - s);
    }

    /** Binary gate prior: 1 (skip MRC) when the synthetic quality falls below the cutoff. */
    public static int label(double score) {
        return score < LABEL_CUTOFF ? 1 : 0;
    }

    public static int label(ScannerProfile p) {
        return label(score(p));
    }

    private static double clamp(double v) {
        return Math.max(0, Math.min(1, v));
    }
}