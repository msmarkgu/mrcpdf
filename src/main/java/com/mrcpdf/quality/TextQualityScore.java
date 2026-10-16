package com.mrcpdf.quality;

/**
 * Immutable per-page text-quality score produced by {@link TextQualityScorer}.
 *
 * Every sub-metric is normalized to [0, 1] with higher = better.  The visual
 * quality composite is the weighted geometric mean of the active groups; the
 * weakest group is reported as a diagnostic for debugging gate decisions.
 *
 * Phase 1 implements the geometric-mean composite only.  Sub-metrics marked
 * "not yet scored" hold a neutral 1.0 until their estimators land in a later
 * phase, so adding them later never changes the current behavior.
 *
 * Phase 2 adds the model-gate decision: when a {@link TextQualityScorer} was
 * built with a QualityModel artifact, {@link #getModelProbability()} carries
 * the logistic degraded probability and {@link #isModelSkip()} decides the MRC
 * fidelity gate, superseding the geometric threshold for the scorer's gating.
 */
public final class TextQualityScore {

    // ---- sub-metrics (higher = better) ----
    private final double blurSigma;
    private final double highFreqEnergy;
    private final double tileSharpnessVariance;
    private final double otsuSeparability;
    private final double globalContrast;
    private final double backgroundUniformity;
    private final double snr;
    private final double inGlyphFragmentation;
    private final double glyphSolidity;        // not yet scored -> neutral 1.0
    private final double strokeWidthUniformity;
    private final double charEdgeAlignment;    // not yet scored -> neutral 1.0
    private final double phaseCongruency;
    private final double ocrConfidence;
    private final double ocrConsistency;

    // ---- derived ----
    private final double sharpness;
    private final double contrast;
    private final double noise;
    private final double structure;
    private final double perceptual;
    private final double ocr;
    private final double composite;
    private final String weakestGroup;
    private final boolean usesTextLayer;

    // ---- Phase-2 model decision (negative = no model / not applicable) ----
    private final double modelProbability;
    private final double modelThreshold;

    TextQualityScore(double blurSigma, double highFreqEnergy, double tileSharpnessVariance,
                     double otsuSeparability, double globalContrast,
                     double backgroundUniformity, double snr,
                     double inGlyphFragmentation, double strokeWidthUniformity,
                     double phaseCongruency, double ocrConfidence, double ocrConsistency,
                     double sharpness, double contrast, double noise,
                     double structure, double perceptual, double ocr,
                     double composite, String weakestGroup, boolean usesTextLayer,
                     double modelProbability, double modelThreshold) {
        this.blurSigma = blurSigma;
        this.highFreqEnergy = highFreqEnergy;
        this.tileSharpnessVariance = tileSharpnessVariance;
        this.otsuSeparability = otsuSeparability;
        this.globalContrast = globalContrast;
        this.backgroundUniformity = backgroundUniformity;
        this.snr = snr;
        this.inGlyphFragmentation = inGlyphFragmentation;
        this.glyphSolidity = 1.0;
        this.strokeWidthUniformity = strokeWidthUniformity;
        this.charEdgeAlignment = 1.0;
        this.phaseCongruency = phaseCongruency;
        this.ocrConfidence = ocrConfidence;
        this.ocrConsistency = ocrConsistency;
        this.sharpness = sharpness;
        this.contrast = contrast;
        this.noise = noise;
        this.structure = structure;
        this.perceptual = perceptual;
        this.ocr = ocr;
        this.composite = composite;
        this.weakestGroup = weakestGroup;
        this.usesTextLayer = usesTextLayer;
        this.modelProbability = modelProbability;
        this.modelThreshold = modelThreshold;
    }

    public double getBlurSigma() { return blurSigma; }
    public double getHighFreqEnergy() { return highFreqEnergy; }
    public double getTileSharpnessVariance() { return tileSharpnessVariance; }
    public double getOtsuSeparability() { return otsuSeparability; }
    public double getGlobalContrast() { return globalContrast; }
    public double getBackgroundUniformity() { return backgroundUniformity; }
    public double getSnr() { return snr; }
    public double getInGlyphFragmentation() { return inGlyphFragmentation; }
    public double getGlyphSolidity() { return glyphSolidity; }
    public double getStrokeWidthUniformity() { return strokeWidthUniformity; }
    public double getCharEdgeAlignment() { return charEdgeAlignment; }
    public double getPhaseCongruency() { return phaseCongruency; }
    public double getOcrConfidence() { return ocrConfidence; }
    public double getOcrConsistency() { return ocrConsistency; }

    /** Sharpness group score (weight 0.30 text-layer mode / 0.40 whole-page). */
    public double getSharpness() { return sharpness; }
    /** Contrast group score (weight 0.20 / 0.27). */
    public double getContrast() { return contrast; }
    /** Noise group score (weight 0.15 / 0.20). */
    public double getNoise() { return noise; }
    /** Structure group score (weight 0.20, text-layer mode only). */
    public double getStructure() { return structure; }
    /** Perceptual group score (weight 0.10 / 0.13). */
    public double getPerceptual() { return perceptual; }
    /** OCR group score (weight 0.05, text-layer mode only). */
    public double getOcr() { return ocr; }

    /** Weighted geometric mean of the active groups, in [0, 1]. */
    public double getComposite() { return composite; }

    /** Name of the lowest-scoring active group (diagnostic), or null on empty pages. */
    public String getWeakestGroup() { return weakestGroup; }

    /** True when a genuine OCR text layer was present and used for scoring. */
    public boolean isUsesTextLayer() { return usesTextLayer; }

    /**
     * Convenience predicate for gating: skip MRC compression when quality
     * falls below {@code threshold} (fidelity-preserving full-res path).
     */
    public boolean isBelow(double threshold) {
        return composite < threshold;
    }

    /**
     * Phase-2 model decision: degraded probability, or &lt; 0 when no model was
     * attached (e.g. artifact absent, or an uninformative text layer under the
     * text-layer variant).
     */
    public double getModelProbability() { return modelProbability; }

    /** Decision threshold the model was calibrated at (degraded-recall ~0.9). */
    public double getModelThreshold() { return modelThreshold; }

    /** True when the model gate says "degraded → keep fidelity". */
    public boolean isModelSkip() {
        return modelProbability >= 0 && modelProbability >= modelThreshold;
    }
}