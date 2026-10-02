package com.mrcpdf.quality.training;

/**
 * Confusion-derived classification metrics for the 3-class good/middle/bad
 * readability model, shared by {@link RealTrainEval} and
 * {@link RealTrainCrossVal} so the two reports cannot drift apart.
 *
 * <p>Classes are fixed at good=0, middle=1, bad=2, matching
 * {@link RealTrainDump#CLASS_NAMES}.  Precision/recall/F1 are the standard
 * one-vs-rest definitions over an argmax confusion matrix.
 */
final class ClassMetrics {

    static final int N_CLASSES = 3;

    private ClassMetrics() {
    }

    /** Metrics measured over one set of predictions. */
    record Scores(int[] confusion, double[] precision, double[] recall, double[] f1,
                  double[] auc, double accuracy, double macroF1, int[] truthCounts) {
    }

    /**
     * Computes metrics from argmax predictions. {@code truth[i]}/{@code pred[i]}
     * are class indices; {@code probs[i][c]} is the model's probability that row
     * {@code i} belongs to class {@code c} (only used for the per-class AUC).
     */
    static Scores of(int[] truth, int[] pred, double[][] probs) {
        int n = truth.length;
        int[] confusion = new int[N_CLASSES * N_CLASSES];
        int[] truthCounts = new int[N_CLASSES];
        int correct = 0;
        for (int i = 0; i < n; i++) {
            int t = truth[i];
            int p = pred[i];
            if (t < 0 || t >= N_CLASSES || p < 0 || p >= N_CLASSES) {
                throw new IllegalArgumentException("class index out of range at row " + i
                        + ": truth=" + t + " pred=" + p);
            }
            confusion[t * N_CLASSES + p]++;
            truthCounts[t]++;
            if (t == p) correct++;
        }
        double accuracy = n == 0 ? 0 : (double) correct / n;

        double[] precision = new double[N_CLASSES];
        double[] recall = new double[N_CLASSES];
        double[] f1 = new double[N_CLASSES];
        double macro = 0;
        for (int c = 0; c < N_CLASSES; c++) {
            int tp = confusion[c * N_CLASSES + c];
            int fp = 0;
            int fn = 0;
            for (int k = 0; k < N_CLASSES; k++) {
                if (k != c) {
                    fp += confusion[k * N_CLASSES + c];
                    fn += confusion[c * N_CLASSES + k];
                }
            }
            precision[c] = tp + fp == 0 ? 0 : (double) tp / (tp + fp);
            recall[c] = tp + fn == 0 ? 0 : (double) tp / (tp + fn);
            f1[c] = precision[c] + recall[c] == 0 ? 0
                    : 2 * precision[c] * recall[c] / (precision[c] + recall[c]);
            macro += f1[c];
        }
        macro /= N_CLASSES;

        double[] auc = new double[N_CLASSES];
        for (int c = 0; c < N_CLASSES; c++) {
            int[] yc = new int[n];
            double[] sc = new double[n];
            for (int i = 0; i < n; i++) {
                yc[i] = truth[i] == c ? 1 : 0;
                sc[i] = probs[i][c];
            }
            auc[c] = LogisticRegression.auc(yc, sc);
        }

        return new Scores(confusion, precision, recall, f1, auc, accuracy, macro, truthCounts);
    }
}
