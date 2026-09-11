package com.mrcpdf.quality.model;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
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
            // a clean corpus row (doc0, label 0) from the realistic 300-DPI corpus
            "2.666667", "107.267544", "12.607473", "10.073622", "0.909296",
            "0.602554", "1.000000", "0.004414", "4.511878", "7.481079", "0.136446"
    };
    private static final String[] GOLDEN_DEGRADED = {
            // a degraded corpus row (doc0, label 1, severe blur)
            "17.483707", "8.395364", "9.715826", "4.182080", "0.854638",
            "0.319435", "0.694118", "0.007669", "0.109336", "14.005147", "0.184973"
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
        // More blur → higher degraded probability.
        assertTrue(model.featureWeight("blurSigmaPx") > 0,
                "blurSigmaPx weight should be positive, got " + model.featureWeight("blurSigmaPx"));
        // A strong bimodal (clean) split → lower degraded probability.
        assertTrue(model.featureWeight("otsuSeparability") < 0,
                "otsuSeparability weight should be negative, got " + model.featureWeight("otsuSeparability"));
        // High signal-to-noise → lower degraded probability.
        assertTrue(model.featureWeight("snr") < 0,
                "snr weight should be negative, got " + model.featureWeight("snr"));
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
        assertEquals(0.37060718680400656, pClean, 1e-12, "clean-row prediction changed");
        assertEquals(0.95705154412455917, pDegraded, 1e-12, "degraded-row prediction changed");
    }
}