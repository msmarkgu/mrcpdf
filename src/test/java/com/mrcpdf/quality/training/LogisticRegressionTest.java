package com.mrcpdf.quality.training;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Random;

import org.junit.jupiter.api.Test;

/**
 * Phase-2 tests for {@link LogisticRegression}: SGD fit separates an
 * engineered noisy dataset, predictions stay in (0,1), and the AUC + threshold
 * helpers behave on a perfect classifier.
 */
class LogisticRegressionTest {

    /** Engineered data: label 1 when x0 + 3*x1 > 2.5, minus additive noise. */
    private static final class Data {
        final double[][] x;
        final int[] y;

        Data(long seed, int n, double noiseSigma) {
            Random rng = new Random(seed);
            x = new double[n][2];
            y = new int[n];
            for (int i = 0; i < n; i++) {
                x[i][0] = rng.nextDouble();
                x[i][1] = rng.nextDouble();
                double z = x[i][0] + 3 * x[i][1] - 2.5 + rng.nextGaussian() * noiseSigma;
                y[i] = z > 0 ? 1 : 0;
            }
        }
    }

    private static double[] scores(LogisticRegression lr, double[][] x, int[] y) {
        double[] out = new double[x.length];
        for (int i = 0; i < x.length; i++) out[i] = lr.predict(x[i]);
        return out;
    }

    @Test
    void fitSeparatesEngineeredData() {
        Data train = new Data(1, 2000, 0.25);
        Data val = new Data(7, 1000, 0.25);
        LogisticRegression lr = LogisticRegression.fit(train.x, train.y, 200, 0.05, 0.001, 64, 42);
        double[] scores = scores(lr, val.x, val.y);
        double auc = LogisticRegression.auc(val.y, scores);
        assertTrue(auc > 0.80, "AUC should be well above chance, got " + auc);
        for (double s : scores) {
            assertTrue(s > 0 && s < 1, "predictions must be in (0,1), got " + s);
        }
    }

    @Test
    void aucIsHalfForUselessScores() {
        int n = 200;
        int[] y = new int[n];
        double[] s = new double[n];
        Random rng = new Random(3);
        for (int i = 0; i < n; i++) {
            y[i] = rng.nextInt(2);
            s[i] = rng.nextDouble();
        }
        double auc = LogisticRegression.auc(y, s);
        assertTrue(auc > 0.40 && auc < 0.60, "random scores should give ~0.5 AUC, got " + auc);
    }

    @Test
    void recallThresholdOnPerfectScores() {
        int n = 100;
        int[] y = new int[n];
        double[] s = new double[n];
        for (int i = 0; i < n; i++) {
            y[i] = i % 2 == 0 ? 1 : 0;
            s[i] = y[i] == 1 ? 0.9 : 0.1;
        }
        double t = LogisticRegression.thresholdForRecall(y, s, 0.9);
        int tp = 0, pos = 0;
        for (int i = 0; i < n; i++) {
            if (y[i] == 1) {
                pos++;
                if (s[i] >= t) tp++;
            }
        }
        assertTrue(tp >= (int) Math.ceil(0.9 * pos), "recall at threshold must meet target");
        assertTrue(t < 0.9, "threshold must trade off, not sit at 1.0");
    }
}