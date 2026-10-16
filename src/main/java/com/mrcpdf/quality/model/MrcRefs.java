package com.mrcpdf.quality.model;

/**
 * The two-band reference points that turn the raw page measurements in
 * {@link MrcReadiness.Raw} into calibrated sub-scores.
 *
 * <h2>Why the references are measured rather than assumed</h2>
 *
 * The first version of this scorer referenced each metric to an idealized raster
 * — a mathematically perfect page, a hard synthetic edge, a stroke-width CV of
 * 0. The corpus never contains that raster.  Every page, including the tier
 * labelled {@code clean}, is a <em>scan</em>: {@code SyntheticCorpus.cleanScan}
 * puts it through a sigma=0.5 Gaussian and additive noise, and it is then encoded
 * to JPEG at quality 0.92.  That baseline is not a small perturbation:
 *
 * <pre>
 *                 blur    contrast   comps/char   speckle/Mpx
 *   sharp raster   0.88      0.83         0.94            30
 *   clean scan     0.35      0.29         0.48          850
 * </pre>
 *
 * Referenced to the sharp raster, a clean scan scored {@code MASK = 0.01..0.12}
 * on every document.  Since {@code kappa} is the <em>minimum</em> of the four
 * dimensions, the weakest one set the composite: 100% of a 500-page pilot was
 * classified {@code bad}, clean pages included.
 *
 * <p>So the bands are re-referenced to a scan: {@link #BLUR_GOOD} and friends are
 * percentiles of the clean-scan distribution over the 125 real base pages, and
 * the {@code _BAD} points come from a fixed reference photocopy.  A sub-score of
 * 1.0 therefore means "as good as a fresh scan of a real page" and 0.0 means "as
 * bad as the reference photocopy" — not "as good as a perfect raster", which no
 * page will ever be.
 *
 * <h2>Provenance</h2>
 *
 * Measured by {@code com.mrcpdf.quality.training.MrcCalibration} over the 125
 * pages of {@code build/real-base-pages.pdf} (25 documents, 5 pages each) at
 * 300 DPI, each passed through {@code cleanScan} and encoded at
 * {@code SyntheticCorpus.LABEL_JPEG_QUALITY}.  The reference photocopy is
 * sigma=2.0 blur plus sigma=1.5 noise over the same rasters.  Both distributions
 * are recorded in {@code notes/mrc-calibration.md}; re-running the tool after a
 * change to the corpus or the metrics requires regenerating these constants.
 *
 * <p>{@code MrcReadinessTest.referencesAreOrdered} guards the invariants the
 * mapping relies on, and {@code calibrationConstantsArePinned} keeps the numbers
 * from drifting silently.
 */
public final class MrcRefs {

    private MrcRefs() {
    }

    // ── Visual dimension ────────────────────────────────────────────────────

    /** p90 re-blur residual of a clean scan: the "fully sharp" end of the band. */
    static final double BLUR_GOOD = 7.0;
    /** Residual of the reference photocopy. */
    static final double BLUR_BAD = 2.0;

    /** Median per-word Michelson contrast of a clean scan. */
    static final double CONTRAST_GOOD = 0.086082;
    /** Median per-word Michelson contrast of the reference photocopy. */
    static final double CONTRAST_BAD = 0.017659;

    /** Stroke-width CV of a clean scan; lower is more consistent, so this is the
     *  good end of a lower-is-better band. */
    static final double SWCV_GOOD = 0.039604;
    /** Stroke-width CV of the reference photocopy. */
    static final double SWCV_BAD = 0.210152;

    // ── Mask dimension ──────────────────────────────────────────────────────

    /** Fraction of stroke pixels sitting on the Sauvola threshold in a clean scan. */
    static final double MARGIN_AMBIGUOUS_GOOD = 0.114716;
    /** Same fraction in the reference photocopy. */
    static final double MARGIN_AMBIGUOUS_BAD = 1.0;

    /**
     * Connected components per character that a well-formed scan produces, and
     * the distance at which the score has decayed to {@code e^-1}.
     *
     * <p>Unlike the other metrics this one is two-sided: strokes that shatter
     * give more components per character, strokes that fuse give fewer, and both
     * are degradation.  So it keeps a Gaussian around a measured centre rather
     * than a linear band.
     */
    static final double FRAG_CENTER = 3.0;
    static final double FRAG_SPREAD = 2.0;

    /**
     * Sub-4-pixel blobs per megapixel outside the text region: the good, low end
     * is zero, because a well-formed scan has no isolated ink in its margins.
     *
     * <p>This counts non-text pixels only, on both sides of the ratio. The
     * earlier version counted every such component on the page, so the clean
     * median was 867 and its p10..p90 spread ran 92..6749 — it was measuring
     * document content (figures, tables and rules make plenty of legitimate
     * small components), not scan quality, and a destroyed page measured
     * <em>less</em> speckle than a clean one because downscaling erases specks.
     * With the text region excluded the clean median is 0.39 and the debris
     * anchor's is 0.99, so the band finally points the right way.
     *
     * <p>Still not a fine-noise detector: additive Gaussian noise and
     * single-pixel salt-and-pepper are absorbed by the Sauvola background
     * estimate and never survive as foreground components. What this catches is
     * clumped non-text debris — dust, toner, dirt. See the "Known limitation"
     * section of {@code notes/mrc-calibration.md}.
     */
    static final double SPECKLE_GOOD = 0.000000;
    /** Same density in the debris anchor (see MrcCalibration.debrisProfile). */
    static final double SPECKLE_BAD = 62.179165;

    // ── Background dimension ────────────────────────────────────────────────

    /** Robust noise sigma of a clean scan; lower is cleaner. */
    static final double NOISE_GOOD = 0.263484;
    /**
     * Same for the 99th percentile over <em>every</em> degraded anchor, not just
     * the destroyed one.
     *
     * <p>This was 0.942372, the 90th percentile of the destroyed anchor alone,
     * and that was plainly wrong: the mid-scale photocopy reaches 2.03, so an
     * ordinary old photocopy overshot the endpoint of the "worthless background"
     * by more than double.  Because {@code sBg} has no bottleneck of its own, a
     * page past both of its endpoints contributed 0 to the geometric mean, and
     * 120 of the 500 pilot pages were pinned to {@code R_mrc = 0} that way.
     */
    static final double NOISE_BAD = 2.447642;

    /** Robust tile-to-tile sigma of a clean scan (illumination uniformity). */
    static final double UNIFORM_GOOD = 0.069338;
    /**
     * 99th percentile of tileSigma over every degraded anchor.  The debris
     * anchor's tail is what sets this: its p90 is 1.80, p95 6.18 and max 15.66,
     * so the old 1.469858 sat below the body of the distribution rather than at
     * its edge.
     */
    static final double UNIFORM_BAD = 9.034715;

    /** Scale by which a metric must move to fall to zero. */
    static double higherIsBetter(double v, double good, double bad) {
        if (!(good > bad)) return clamp01(v);
        return clamp01((v - bad) / (good - bad));
    }

    /** Scale by which a metric must move, to rise to one. */
    static double lowerIsBetter(double v, double good, double bad) {
        if (!(bad > good)) return clamp01(v);
        return clamp01((bad - v) / (bad - good));
    }

    private static double clamp01(double v) {
        if (Double.isNaN(v)) return 0.0;
        return Math.max(0.0, Math.min(1.0, v));
    }
}
