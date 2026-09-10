package com.mrcpdf.quality.training;

import java.util.Arrays;
import java.util.Random;

/**
 * Binary logistic regression fitted by minibatch SGD with L2 regularization,
 * trained on already-standardized features.
 *
 * Label convention: {@code y[i] == 1} means {@code degraded} (the MRC-skip
 * class); {@code predict(x)} is the probability of degradedness.  Thresholds
 * are chosen on a validation split at a target degraded recall.
 */
public final class LogisticRegression {

    private final double[] weights;
    private final double bias;

    public LogisticRegression(double[] weights, double bias) {
        this.weights = weights.clone();
        this.bias = bias;
    }

    public double[] weights() {
        return weights.clone();
    }

    public double bias() {
        return bias;
    }

    /** Probability of the positive (degraded) class. */
    public double predict(double[] features) {
        double z = bias;
        for (int i = 0; i < weights.length; i++) {
            z += weights[i] * features[i];
        }
        return 1.0 / (1.0 + Math.exp(-Math.max(-745.0, Math.min(745.0, z))));
    }

    public boolean predictBinary(double[] features, double threshold) {
        return predict(features) >= threshold;
    }

    /**
     * Convenience that mirrors how the scorer gates: a prediction at or above
     * the given threshold means "degraded → keep full fidelity".
     */
    public boolean gate(double[] features, double threshold) {
        return predictBinary(features, threshold);
    }

    /**
     * Fits weights by minibatch SGD with L2 penalty on the weights (bias
     * unregularized).  Features must already be standardized.
     */
    public static LogisticRegression fit(double[][] x, int[] y,
                                         int epochs, double lr,
                                         double lambda, int batchSize, long seed) {
        int n = x.length;
        if (n == 0) throw new IllegalArgumentException("Empty training set");
        int d = x[0].length;
        double[] w = new double[d];
        double b = 0;
        Random rng = new Random(seed);

        int batch = Math.min(Math.max(1, batchSize), n);
        for (int epoch = 0; epoch < epochs; epoch++) {
            int[] order = shuffledIndices(n, rng);
            for (int s = 0; s < n; s += batch) {
                int end = Math.min(s + batch, n);
                double[] gW = new double[d];
                double gB = 0;
                for (int k = s; k < end; k++) {
                    int i = order[k];
                    double p = sigmoid(dot(w, b, x[i]));
                    double err = p - y[i];
                    for (int j = 0; j < d; j++) gW[j] += err * x[i][j];
                    gB += err;
                }
                int m = end - s;
                double lrBatch = lr * (double) batch / m;
                for (int j = 0; j < d; j++) {
                    w[j] -= lrBatch * (gW[j] / batch + lambda * w[j]);
                }
                b -= lrBatch * (gB / batch);
            }
        }
        return new LogisticRegression(w, b);
    }

    private static double dot(double[] w, double b, double[] x) {
        double z = b;
        for (int j = 0; j < w.length; j++) z += w[j] * x[j];
        return z;
    }

    private static double sigmoid(double z) {
        return 1.0 / (1.0 + Math.exp(-Math.max(-745.0, Math.min(745.0, z))));
    }

    private static int[] shuffledIndices(int n, Random rng) {
        int[] order = new int[n];
        for (int i = 0; i < n; i++) order[i] = i;
        for (int i = n - 1; i > 0; i--) {
            int j = rng.nextInt(i + 1);
            int tmp = order[i];
            order[i] = order[j];
            order[j] = tmp;
        }
        return order;
    }

    // ──────────────────────────────────────────────────────────────────────
    // Evaluation
    // ──────────────────────────────────────────────────────────────────────

    /** Area under the ROC curve via averaged ranks; positive is {@code y==1}. */
    public static double auc(int[] y, double[] scores) {
        int n = y.length;
        int np = 0;
        for (int v : y) if (v == 1) np++;
        int nn = n - np;
        if (np == 0 || nn == 0) return 0.5;

        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) order[i] = i;
        Arrays.sort(order, (a, b) -> {
            int c = Double.compare(scores[a], scores[b]);
            return c != 0 ? c : Integer.compare(a, b);
        });

        double[] rank = new double[n];
        int i = 0;
        while (i < n) {
            int j = i;
            while (j < n && scores[order[j]] == scores[order[i]]) j++;
            double avg = (i + j + 1) / 2.0;
            for (int k = i; k < j; k++) rank[order[k]] = avg;
            i = j;
        }

        double sumRanks = 0;
        for (int idx = 0; idx < n; idx++) {
            if (y[idx] == 1) sumRanks += rank[idx];
        }
        return (sumRanks - np * (np + 1) / 2.0) / (np * nn);
    }

    /**
     * Highest decision threshold that still yields at least {@code recall}
     * degraded-recalled on the given scores; falls back to 0.5 when no
     * positive exists. {@code y[i] == 1} is degraded.
     */
    public static double thresholdForRecall(int[] y, double[] scores, double recall) {
        int n = y.length;
        int[] pos = new int[n];
        int[] neg = new int[n];
        int np = 0, nn = 0;
        for (int i = 0; i < n; i++) {
            if (y[i] == 1) pos[np++] = i;
            else neg[nn++] = i;
        }
        if (np == 0) return 0.5;
        double[] posScores = new double[np];
        for (int i = 0; i < np; i++) posScores[i] = scores[pos[i]];
        Arrays.sort(posScores);
        // Thresholds at each positive's score; pick the largest one that
        // recalls >= target (larger threshold ⇒ fewer recalls ⇒ we take the
        // first from the top that satisfies the constraint).
        int target = (int) Math.ceil(recall * np);
        for (int i = np - 1; i >= 0; i--) {
            int recalledAbove = np - i;
            if (recalledAbove >= target) {
                return posScores[i] - 1e-9;
            }
        }
        return posScores[0] - 1e-9;
    }
}