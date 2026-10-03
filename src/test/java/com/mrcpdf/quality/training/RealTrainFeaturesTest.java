package com.mrcpdf.quality.training;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.mrcpdf.quality.model.FeatureExtractor;

/**
 * The real-train feature vector's shape and the imputation rule behind it.
 *
 * <p>Two things must stay true or the trained model silently reads the wrong
 * columns.  First, the readiness sub-scores are appended after the image and
 * text features, because the scorer and every report slice the vector by prefix
 * ({@code subList(0, n)}).  Second, only the 3 conditional text columns are
 * imputable: the readiness sub-scores are measured on every page, and replacing
 * one with a population median would erase exactly the OCR-readiness signal the
 * class=good analysis found was missing.
 */
class RealTrainFeaturesTest {

    private static final int IMAGE = FeatureExtractor.IMAGE_FEATURE_NAMES.length;
    private static final int TEXT = FeatureExtractor.TEXT_FEATURE_NAMES.length;
    private static final int READY = RealTrainDump.READINESS_FEATURE_NAMES.length;

    @Test
    void featureNamesAreImageThenTextThenReadiness() {
        List<String> names = RealTrainDump.featureNames();
        assertEquals(IMAGE + TEXT + READY, names.size());
        assertEquals(List.of(FeatureExtractor.IMAGE_FEATURE_NAMES), names.subList(0, IMAGE));
        assertEquals(List.of(FeatureExtractor.TEXT_FEATURE_NAMES), names.subList(IMAGE, IMAGE + TEXT));
        assertEquals(List.of(RealTrainDump.READINESS_FEATURE_NAMES),
                names.subList(IMAGE + TEXT, names.size()));
    }

    /**
     * A row whose text extraction failed gets the successful rows' text medians,
     * and nothing else.  The readiness columns of <em>both</em> rows must be
     * bit-for-bit unchanged; if imputation walked the whole vector they would be
     * replaced by text medians, which is the bug this pins.
     */
    @Test
    void readinessColumnsSurviveTextImputation() {
        int width = IMAGE + TEXT + READY;
        double[][] f = new double[2][width];
        f[0][IMAGE] = 2;
        f[0][IMAGE + 1] = 4;
        f[0][IMAGE + 2] = 6;
        f[1][IMAGE] = Double.NaN;
        f[1][IMAGE + 1] = Double.NaN;
        f[1][IMAGE + 2] = Double.NaN;
        for (int r = 0; r < READY; r++) {
            f[0][IMAGE + TEXT + r] = 0.1 * (r + 1);
            f[1][IMAGE + TEXT + r] = 0.9 + 0.1 * r;
        }

        int missing = RealTrainDump.imputeMissingText(f, new boolean[]{true, false});

        assertEquals(1, missing);
        assertEquals(2, f[1][IMAGE], 1e-12);
        assertEquals(4, f[1][IMAGE + 1], 1e-12);
        assertEquals(6, f[1][IMAGE + 2], 1e-12);
        for (int r = 0; r < READY; r++) {
            assertEquals(0.1 * (r + 1), f[0][IMAGE + TEXT + r], 1e-12,
                    "readiness of a successful row must not change");
            assertEquals(0.9 + 0.1 * r, f[1][IMAGE + TEXT + r], 1e-12,
                    "readiness of an imputed row must not be overwritten with a text median");
        }
    }
}
