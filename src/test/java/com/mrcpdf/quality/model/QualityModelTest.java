package com.mrcpdf.quality.model;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;

/**
 * Phase-2 tests for the committed {@link QualityModel} artifact.
 *
 * Verifies JSON round-tripping, artifact presence and shape, the coefficient
 * sign invariants that make the model physically sensible, and golden
 * prediction values so the committed artifact acts as a regression lock
 * (changing the extractor or corpus without retraining must fail here).
 */
class QualityModelTest {

    private static final String[] GOLDEN_CLEAN = {
            // a clean corpus row (doc0, label 0) from the scanner-simulated 300-DPI corpus
            "2.666667", "0.129994", "12.829327", "10.089706", "0.918952",
            "0.700092", "0.996078", "0.005152", "3.586328", "8.118833", "0.070324"
    };
    private static final String[] GOLDEN_DEGRADED = {
            // a degraded corpus row (doc0, label 1) from the scanner-simulated 300-DPI corpus
            "6.245275", "0.863158", "7.487757", "4.691683", "0.537629",
            "0.014238", "0.062745", "0.016595", "1.008388", "3.854570", "0.752910"
    };

    private static double[] parse(String[] s) {
        double[] out = new double[s.length];
        for (int i = 0; i < s.length; i++) out[i] = Double.parseDouble(s[i]);
        return out;
    }

    @Test
    void roundTripsThroughJson() {
        QualityModel m = new QualityModel(QualityModel.VARIANT_IMAGE_ONLY,
                List.of(FeatureExtractor.IMAGE_FEATURE_NAMES),
                new double[11], new double[11], new double[11], new double[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11},
                new double[]{0.1, 0.2, 0.3, 0.4, 0.5, 0.6, 0.7, 0.8, 0.9, 1.0, 1.1},
                -0.3, 0.55, 0.91, 0.9, 0.72);
        QualityModel back = QualityModel.load(new ByteArrayInputStream(m.toJson().getBytes(StandardCharsets.UTF_8)));
        assertEquals(QualityModel.VARIANT_IMAGE_ONLY, back.variant());
        assertEquals(List.of(FeatureExtractor.IMAGE_FEATURE_NAMES), back.featureNames());
        assertArrayEquals(m.weights(), back.weights(), 1e-12);
        assertEquals(m.bias(), back.bias(), 1e-12);
        assertEquals(m.threshold(), back.threshold(), 1e-12);
        assertEquals(m.auc(), back.auc(), 1e-12);
        double p = m.predict(parse(GOLDEN_CLEAN));
        assertTrue(p > 0 && p < 1, "predict in (0,1): " + p);
        assertEquals(p, back.predict(parse(GOLDEN_CLEAN)), 1e-12, "load must reproduce runs");
        assertEquals(m.gate(parse(GOLDEN_CLEAN)), back.gate(parse(GOLDEN_CLEAN)));
    }

    @Test
    void bundledArtifactExists() {
        QualityModel model = QualityModel.loadDefault();
        Assumptions.assumeTrue(model != null, "committed artifact absent — run gradle trainQualityModel");
        assertEquals(QualityModel.VARIANT_IMAGE_ONLY, model.variant());
        assertEquals(List.of(FeatureExtractor.IMAGE_FEATURE_NAMES), model.featureNames());
        assertEquals(model.featureNames().size(), model.weights().length);
        assertEquals(model.featureNames().size(), model.means().length);
        assertEquals(model.featureNames().size(), model.stds().length);
        assertTrue(model.auc() > 0.70, "committed model should generalize, AUC=" + model.auc());
        assertTrue(model.recall() >= 0.85, "committed threshold calibrated at ~0.9 recall, got " + model.recall());
    }

    @Test
    void coefficientSignsMatchPhysicalMechanism() {
        QualityModel model = QualityModel.loadDefault();
        Assumptions.assumeTrue(model != null, "committed artifact absent — run gradle trainQualityModel");
        // Degraded (OCR-failing) pages lose high-frequency energy.
        assertTrue(model.featureWeight("hiFreqEnergy") < 0,
                "hiFreqEnergy weight should be negative, got " + model.featureWeight("hiFreqEnergy"));
        // A strong bimodal (clean) split → lower degraded probability.
        assertTrue(model.featureWeight("otsuSeparability") < 0,
                "otsuSeparability weight should be negative, got " + model.featureWeight("otsuSeparability"));
        // Ink spread (blur/bold/streaks) is a hallmark of OCR-failing pages.
        assertTrue(model.featureWeight("inkCoverage") > 0,
                "inkCoverage weight should be positive, got " + model.featureWeight("inkCoverage"));
        // edgeWidthCV and snr are NOT asserted by sign: their univariate rank
        // correlations with the OCR-measured score are correct (see
        // checkSyntheticCorpus), but in the multivariate logistic fit they act
        // as collinear suppressors and flip, exactly like blurSigmaPx. Their
        // per-tier ordering still gates correctly.
    }

    @Test
    void goldenPredictionsLocked() {
        QualityModel model = QualityModel.loadDefault();
        Assumptions.assumeTrue(model != null, "committed artifact absent — run gradle trainQualityModel");
        double pClean = model.predict(parse(GOLDEN_CLEAN));
        double pDegraded = model.predict(parse(GOLDEN_DEGRADED));
        assertTrue(pClean < pDegraded, "degraded vector must score higher");
        // Regression lock on the committed artifact. Regenerate with
        // `gradle trainQualityModel` after any extractor/corpus change, then
        // update these constants from the printed values.
        System.out.println("GOLDEN_CLEAN prediction: " + pClean);
        System.out.println("GOLDEN_DEGRADED prediction: " + pDegraded);
        assertEquals(5.271871468898777E-6, pClean, 1e-12, "clean-row prediction changed");
        assertEquals(0.9992585432742552, pDegraded, 1e-12, "degraded-row prediction changed");
    }

    // ──────────────────────────────────────────────────────────────────────
    // quality-image-screen.json -- the artifact the pipeline actually gates on
    // ──────────────────────────────────────────────────────────────────────

    /**
     * {@link QualityModel#loadScreen} documents itself as the exact negation of
     * the shipped good head: {@code predict(x) == 1 - pGood(x)} with the
     * threshold flipped to match, so {@link QualityModel#gate} keeps its
     * "true ⇒ skip MRC" meaning for the screen as it had for the legacy gate.
     *
     * <p>Both {@code pGood} and the threshold are recomputed here from the raw
     * bytes with this test's own parser and its own sigmoid, so no production
     * parsing or scoring code is shared with the loader under test. If
     * {@code loadScreen} stopped negating, or negated only the weights, or left
     * the threshold pointing at the good side, this fails.
     *
     * <p>The tie falls on the skip side, because {@code gate} is
     * {@code predict >= threshold} and {@code 1 - pGood >= 1 - 0.700} holds at
     * equality. The artifact's own {@code screen.rule} says "good iff
     * pGood >= threshold", so at exactly 0.700 the two disagree by a
     * measure-zero set -- and the conservative reading (skip) is the one the
     * pipeline executes. That direction is pinned by the exact threshold
     * assertion below rather than by a vector engineered to land on equality,
     * which no floating-point construction could do reliably.
     */
    @Test
    void loadScreenIsTheNegationOfTheShippedGoodHead() throws Exception {
        String json = rawScreenArtifact();

        int scalerAt = json.indexOf("\"scaler\"");
        assertTrue(scalerAt > 0, "artifact has no scaler block");
        double[] lo = arrayAfter(json, "winsorizeLower", scalerAt);
        double[] hi = arrayAfter(json, "winsorizeUpper", scalerAt);
        double[] means = arrayAfter(json, "means", scalerAt);
        double[] stds = arrayAfter(json, "stds", scalerAt);

        int headsAt = json.indexOf("\"heads\"");
        int goodHeadAt = json.indexOf("\"good\"", headsAt);
        assertTrue(headsAt > 0 && goodHeadAt > headsAt, "artifact has no good head");
        double headBias = numberAfter(json, "bias", goodHeadAt);
        double[] headWeights = arrayAfter(json, "weights", goodHeadAt);

        int screenAt = json.indexOf("\"screen\"");
        double pGoodThreshold = numberAfter(json, "threshold", screenAt);

        QualityModel screen = QualityModel.loadScreen(
                new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));

        assertEquals(1.0 - pGoodThreshold, screen.threshold(), 0.0,
                "the threshold must be flipped, not reused verbatim");

        double[][] vectors = {
                parse(GOLDEN_CLEAN),          // below the lower winsor bounds
                parse(GOLDEN_DEGRADED),       // a mix of both sides
                means.clone(),                // every z-score 0
                {hi[0] + 1, hi[1] + 1, hi[2] + 1, hi[3] + 1, hi[4] + 1, hi[5] + 1,
                        hi[6] + 1, hi[7] + 1, hi[8] + 1, hi[9] + 1, hi[10] + 1},
        };
        boolean sawGood = false;
        boolean sawSkip = false;
        for (double[] v : vectors) {
            double pGood = pGood(v, lo, hi, means, stds, headWeights, headBias);
            assertEquals(1.0 - pGood, screen.predict(v), 1e-9,
                    "predict must be 1 - pGood for " + java.util.Arrays.toString(v));
            assertEquals(pGood <= pGoodThreshold, screen.gate(v),
                    "gate must fire exactly when pGood <= " + pGoodThreshold
                            + " (pGood=" + pGood + ")");
            sawGood |= pGood > pGoodThreshold;
            sawSkip |= pGood <= pGoodThreshold;
        }
        assertTrue(sawGood && sawSkip,
                "the vectors must straddle the threshold or this test proves nothing");
    }

    /**
     * The tie, isolated: at exactly {@code pGood == threshold} the page is not
     * good, so the gate fires. With {@code screen.threshold} at 0.5 and every
     * z-score at 0 the negated head lands on 0.5 exactly, so this needs no
     * floating-point construction to hit an equality -- {@code gate} is a plain
     * {@code predict >= threshold}. Changing it to {@code >} sends the tie to
     * the good side and fails here; the flip itself is pinned by
     * {@link #loadScreenIsTheNegationOfTheShippedGoodHead}, which needs 0.5 to
     * be indistinguishable from its own negation to pass.
     */
    @Test
    void gateFiresOnAnExactTie() {
        String json = """
                {
                  "featureNames": ["a", "b"],
                  "scaler": {
                    "winsorizeLower": [-1e308, -1e308],
                    "winsorizeUpper": [1e308, 1e308],
                    "means": [0, 0],
                    "stds": [1, 1]
                  },
                  "heads": [{"class": "good", "bias": 0.0, "weights": [1.0, -1.0]}],
                  "screen": {"rule": "good iff pGood >= threshold", "threshold": 0.5}
                }
                """;
        QualityModel screen = QualityModel.loadScreen(
                new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));

        double[] zero = {0.0, 0.0};
        assertEquals(0.5, screen.threshold(), 0.0, "threshold must be 1 - 0.5");
        assertEquals(0.5, screen.predict(zero), 0.0, "an all-zero z-score is sigmoid(0)");
        assertTrue(screen.gate(zero), "an exact tie must fall on the skip side");
    }

    /** The committed screen artifact, straight from the classpath. */
    private static String rawScreenArtifact() throws IOException {
        try (InputStream in = QualityModel.class.getResourceAsStream(QualityModel.SCREEN_RESOURCE)) {
            assertNotNull(in, "screen artifact missing from the classpath -- "
                    + "run ./gradlew trainQualityScreen");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** The numeric value following {@code "key"} in {@code json} at or after {@code from}. */
    private static double numberAfter(String json, String key, int from) {
        int k = json.indexOf("\"" + key + "\"", from);
        assertTrue(k > 0, "artifact has no key \"" + key + "\"");
        int colon = json.indexOf(':', k);
        int start = colon + 1;
        while (start < json.length() && " \t\r\n+,".indexOf(json.charAt(start)) >= 0) start++;
        int end = start;
        while (end < json.length() && "+-0123456789.eE".indexOf(json.charAt(end)) >= 0) end++;
        assertTrue(end > start, "no number after \"" + key + "\"");
        return Double.parseDouble(json.substring(start, end));
    }

    /** The array following {@code "key"} in {@code json} at or after {@code from}. */
    private static double[] arrayAfter(String json, String key, int from) {
        int k = json.indexOf("\"" + key + "\"", from);
        assertTrue(k > 0, "artifact has no key \"" + key + "\"");
        int open = json.indexOf('[', k);
        int close = json.indexOf(']', open);
        assertTrue(open > 0 && close > open, "no array after \"" + key + "\"");
        String[] parts = json.substring(open + 1, close).split(",");
        double[] out = new double[parts.length];
        for (int i = 0; i < parts.length; i++) out[i] = Double.parseDouble(parts[i].trim());
        return out;
    }

    /** pGood: winsorize, z-score, then the good head's logistic. Written here, not imported. */
    private static double pGood(double[] raw, double[] lo, double[] hi, double[] means,
                                double[] stds, double[] weights, double bias) {
        double z = bias;
        for (int i = 0; i < raw.length; i++) {
            double v = Math.max(lo[i], Math.min(hi[i], raw[i]));
            double t = (v - means[i]) / (stds[i] > 1e-9 ? stds[i] : 1.0);
            z += weights[i] * t;
        }
        return 1.0 / (1.0 + Math.exp(-z));
    }
}