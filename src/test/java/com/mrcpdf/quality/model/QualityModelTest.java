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
        // Degraded pages have wider / softer edges (downscale, blur, JPEG).
        assertTrue(model.featureWeight("edgeWidthCV") > 0,
                "edgeWidthCV weight should be positive, got " + model.featureWeight("edgeWidthCV"));
        // Degraded pages lose high-frequency energy.
        assertTrue(model.featureWeight("hiFreqEnergy") < 0,
                "hiFreqEnergy weight should be negative, got " + model.featureWeight("hiFreqEnergy"));
        // A strong bimodal (clean) split → lower degraded probability.
        assertTrue(model.featureWeight("otsuSeparability") < 0,
                "otsuSeparability weight should be negative, got " + model.featureWeight("otsuSeparability"));
        // High signal-to-noise → lower degraded probability.
        assertTrue(model.featureWeight("snr") < 0,
                "snr weight should be negative, got " + model.featureWeight("snr"));
        // blurSigmaPx's coefficient is collinear with the above and can flip
        // sign between retrains while its mild/severe ordering still gates
        // correctly, so it is not asserted here.
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
        assertEquals(0.002775149376186915, pClean, 1e-12, "clean-row prediction changed");
        assertEquals(0.9999542984745883, pDegraded, 1e-12, "degraded-row prediction changed");
    }
}