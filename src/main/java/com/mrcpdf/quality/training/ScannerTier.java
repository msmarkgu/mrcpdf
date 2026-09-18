package com.mrcpdf.quality.training;

/**
 * Quality archetypes from {@code notes/simulate-realworld-scans.md}, each a
 * parameter range in the synthetic quality space.  A degraded corpus page
 * draws a {@link ScannerProfile} from one tier: the tier's archetype center
 * pins the target degradation severity, individual effects get jitter and a
 * "drop" chance so same-tier pages spread realistically (and the label
 * boundary — score around {@link SeverityScore#LABEL_CUTOFF} — is crossed by
 * some pages of the middle tiers rather than a single hard step).
 *
 * <p>The inverse mappings here round-trip through {@link SeverityScore}, so
 * {@code SeverityScore.score(draw(rng))} lands near the tier's {@code center}
 * on average.  Scoring is validated empirically by the corpus verification
 * tool; tweak the draw weights / jitter if a tier drifts.
 */
public enum ScannerTier {

    EXCELLENT_OFFICE("ex90", 0.90),
    HIGH_QUALITY_MFP("mf80", 0.80),
    AVERAGE_OFFICE("of70", 0.70),
    CHEAP_SCANNER("ch60", 0.60),
    OLD_PHOTOCOPY("pc50", 0.50),
    POOR_FAX("fx40", 0.40),
    BARELY_READABLE("br30", 0.30),
    OCR_STRUGGLES("oc20", 0.20);

    /** Relative draw weight per tier; label-1 pages ≈ 45% of degraded corpus. */
    static final double[] DRAW_WEIGHTS = {0.08, 0.14, 0.16, 0.15, 0.14, 0.11, 0.11, 0.11};

    /** Scoring weights keyed to the sev array order (blur, motion, scale, ink, contrast, noise, jpeg, illum, paper, bleed, streaks, skew, curl). */
    private static final double[] W = {
            SeverityScore.W_BLUR, SeverityScore.W_MOTION, SeverityScore.W_SCALE,
            SeverityScore.W_INK, SeverityScore.W_CONTRAST, SeverityScore.W_NOISE,
            SeverityScore.W_JPEG, SeverityScore.W_ILLUM, SeverityScore.W_PAPER,
            SeverityScore.W_BLEED, SeverityScore.W_STREAKS, SeverityScore.W_SKEW,
            SeverityScore.W_CURL
    };

    private final String slug;
    private final double center;

    ScannerTier(String slug, double center) {
        this.slug = slug;
        this.center = center;
    }

    public String slug() {
        return slug;
    }

    /** Archetype center quality in [0,1]. */
    public double center() {
        return center;
    }

    /**
     * Draws a concrete degradation profile from this tier's parameter ranges.
     *
     * <p>The tier's severity budget {@code (100-center)/100} is carried by the
     * foreground quality drivers (blur, downscale, ink spread, contrast):
     * picking one or several of them and setting each to
     * {@code target/ΣW_heavy} makes the scored severity land on {@code target}
     * exactly (the inverse of {@link SeverityScore#severity}) until the
     * per-effect cap at 1.0 saturates strong tiers.  Background texture
     * (motion, JPEG, shading, paper, bleed, streaks, skew, curl) is added
     * independently at a small fixed severity so mid images stay legible but
     * look realistic.  Deterministic for a given {@code rng}.
     */
    public ScannerProfile draw(java.util.Random rng) {
        double target = 1.0 - center;

        int heavyCount = 1 + (int) Math.round(3.0 * target);
        if (target > 0.55) heavyCount = 4;
        if (heavyCount > 4) heavyCount = 4;
        int[] heavy = pickHeavy(heavyCount, rng);
        double heavyW = 0;
        for (int idx : heavy) heavyW += W[idx];

        double[] sev = new double[W.length];
        for (int idx : heavy) {
            double jitter = 0.8 + 0.4 * rng.nextDouble();
            sev[idx] = target * jitter / heavyW;
        }

        // Independent background texture at a small fixed severity.
        int texCount = rng.nextDouble() < 0.30 + 0.45 * target ? 1 : 0;
        if (rng.nextDouble() < 0.4 * target) texCount++;
        int[] tex = pickCheap(texCount, rng);
        for (int idx : tex) {
            sev[idx] = 0.4 + 0.3 * rng.nextDouble();
        }

        // Sensor noise is endemic to degraded scans: guarantee a floor on the
        // sub-cutoff tiers so noiseSigma/snr actually move with degradation
        // instead of being swamped by clean-page JPEG ring residue.
        if (target >= 0.45 && sev[5] < 0.30 + 0.30 * target) {
            sev[5] = 0.30 + 0.30 * target;
        }

        return toProfile(sev, rng);
    }

    private static int[] pickHeavy(int k, java.util.Random rng) {
        int[] pool = {2, 0, 3, 4}; // scale, blur, ink, contrast
        return pickFrom(pool, k, rng);
    }

    private static int[] pickCheap(int k, java.util.Random rng) {
        int[] pool = {1, 5, 6, 7, 8, 9, 10, 11, 12}; // motion, noise, jpeg, illum, paper, bleed, streaks, skew, curl
        if (k <= 0) return new int[0];
        return pickFrom(pool, Math.min(k, pool.length), rng);
    }

    private static int[] pickFrom(int[] pool, int k, java.util.Random rng) {
        int[] out = new int[k];
        int m = pool.length;
        int[] rem = pool.clone();
        for (int j = 0; j < k; j++) {
            int pick = rng.nextInt(m - j);
            out[j] = rem[pick];
            rem[pick] = rem[m - j - 1];
        }
        return out;
    }

    private static ScannerProfile toProfile(double[] sev, java.util.Random rng) {
        double blurS = clamp1(sev[0]);
        double motionS = clamp1(sev[1]);
        double scaleS = clamp1(sev[2]);
        double inkS = clamp1(sev[3]);
        double contrastS = clamp1(sev[4]);
        double noiseS = clamp1(sev[5]);
        double jpegS = clamp1(sev[6]);
        double illumS = clamp1(sev[7]);
        double paperS = clamp1(sev[8]);
        double bleedS = clamp1(sev[9]);
        double streakS = clamp1(sev[10]);
        double skewS = clamp1(sev[11]);
        double curlS = clamp1(sev[12]);

        double blurSigma = SeverityScore.MAX_BLUR * blurS;
        double motionLen = SeverityScore.MAX_MOTION * motionS;
        double dscale = 1 - scaleS / 2;
        double downscale = dscale < 0.5 ? 0.5 : (dscale > 1 ? 1 : dscale);
        int inkSpread = 0;
        if (inkS > 0.15) {
            int mag = inkS > 0.55 ? 2 : 1;
            inkSpread = (rng.nextBoolean() ? 1 : -1) * mag;
        }
        double blackLevel = SeverityScore.MAX_BLACK_LEVEL * contrastS * (0.7 + 0.3 * rng.nextDouble());
        double whiteLevel = SeverityScore.MAX_WHITE_LEVEL * contrastS * (0.7 + 0.3 * rng.nextDouble());
        double gamma = 1 + (1.4 - 1) * contrastS * (0.6 + 0.4 * rng.nextDouble());
        if (rng.nextBoolean()) gamma = 1 / gamma;
        if (contrastS == 0) gamma = 1;
        double noiseSigma = SeverityScore.MAX_NOISE * noiseS;
        double saltPepper = SeverityScore.MAX_SALT_PEPPER * noiseS;
        double fixedPattern = SeverityScore.MAX_FIXED_PATTERN * noiseS;
        float jpegQuality = (float) clampQ(1 - SeverityScore.MAX_JPEG_DROP * jpegS);
        double illumination = SeverityScore.MAX_ILLUM * illumS;
        double paperOpacity = SeverityScore.MAX_PAPER * paperS;
        double bleedThrough = SeverityScore.MAX_BLEED * bleedS;
        int streakCount = (int) Math.round(SeverityScore.MAX_STREAKS * 0.7 * streakS);
        int dustCount = (int) Math.round(SeverityScore.MAX_DUST * 0.3 * streakS);
        double skewDeg = SeverityScore.MAX_SKEW * skewS;
        double curl = SeverityScore.MAX_CURL * curlS;

        return new ScannerProfile(curl, skewDeg, downscale, blurSigma, motionLen,
                illumination, paperOpacity, bleedThrough, noiseSigma, saltPepper,
                fixedPattern, streakCount, dustCount, blackLevel, whiteLevel,
                gamma, inkSpread, jpegQuality);
    }

    private static double clamp1(double v) {
        return Math.max(0, Math.min(1, v));
    }

    private static double clampQ(double q) {
        return Math.max(0.45, Math.min(1.0, q));
    }
}