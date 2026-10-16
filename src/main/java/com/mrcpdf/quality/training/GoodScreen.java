package com.mrcpdf.quality.training;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The good-vs-rest screen, computed from out-of-fold predictions.
 *
 * <p>The screen exists because "is this scan worth OCR-ing?" is a two-way
 * question, while the model is trained three-way. Nothing new has to be fitted:
 * the good head of a one-vs-rest triple <em>is</em> a good-vs-rest classifier
 * already, so the screen is a threshold on that head's probability rather than
 * an argmax over the three. That distinction is the whole point --
 *
 * <pre>
 *   argmax rule:  good iff pGood &gt; max(pMiddle, pBad)   (stricter: must beat both)
 *   screen rule:  good iff pGood &gt;= threshold            (one number to set)
 * </pre>
 *
 * <p>and it is why the operating point is a product decision rather than a
 * modelling one. On the 500-row corpus, F1 moves by less than 0.03 across
 * thresholds 0.3-0.8, so the sweep is reported rather than optimised: picking the
 * sweep's argmax and reporting that number as the model's score would be fitting
 * the threshold on the same predictions it is then judged by.
 *
 * <p>Package-private and static so the screen can be tested directly against
 * hand-computed confusion counts, and reused by both the cross-validation report
 * and the fitted artifact.
 */
final class GoodScreen {

    /** The class index of "good"; see {@link RealTrainDump#CLASS_NAMES}. */
    static final int GOOD = 0;

    private GoodScreen() {
    }

    /**
     * Counts for one threshold: {@code tp} good pages called good, {@code fp}
     * not-good pages called good, and so on.
     */
    record Counts(int tp, int fp, int fn, int tn) {
        int n() {
            return tp + fp + fn + tn;
        }

        double precision() {
            return tp + fp == 0 ? 0.0 : tp / (double) (tp + fp);
        }

        double recall() {
            return tp + fn == 0 ? 0.0 : tp / (double) (tp + fn);
        }

        double f1() {
            double p = precision();
            double r = recall();
            return p + r == 0 ? 0.0 : 2 * p * r / (p + r);
        }
    }

    /** One row of the threshold sweep: the counts a given threshold produces. */
    record SweepPoint(double threshold, Counts counts) {
    }

    /**
     * The whole screen for one feature set: pooled counts at the chosen
     * threshold, the same per fold (a pooled number alone hides a threshold that
     * only works on some folds), the threshold-free AUC, and the sweep.
     */
    record Screen(Counts pooled, double auc, Counts[] perFold, double minFoldPrecision,
                  SweepPoint[] sweep, int argmaxDisagreements) {
    }

    /** Pooled counts at one threshold, over every error row given. */
    static Counts countsAt(RealTrainCrossVal.ErrorRow[] errors, double threshold) {
        int tp = 0;
        int fp = 0;
        int fn = 0;
        int tn = 0;
        for (RealTrainCrossVal.ErrorRow e : errors) {
            boolean truthGood = e.truth() == GOOD;
            boolean calledGood = e.pGood() >= threshold;
            if (truthGood && calledGood) tp++;
            else if (truthGood) fn++;
            else if (calledGood) fp++;
            else tn++;
        }
        return new Counts(tp, fp, fn, tn);
    }

    /**
     * Threshold-free ranking quality of the good head: how well {@code pGood}
     * orders good pages above the rest, independent of where the threshold sits.
     */
    static double auc(RealTrainCrossVal.ErrorRow[] errors) {
        int[] y = new int[errors.length];
        double[] scores = new double[errors.length];
        for (int i = 0; i < errors.length; i++) {
            y[i] = errors[i].truth() == GOOD ? 1 : 0;
            scores[i] = errors[i].pGood();
        }
        return LogisticRegression.auc(y, scores);
    }

    /**
     * How often the screen and the 3-class argmax disagree on these rows.
     *
     * <p>Reported rather than asserted: at a threshold low enough that
     * {@code pGood >= threshold} fires for every row the two rules coincide, and
     * as the threshold rises the screen can only call fewer rows good than the
     * argmax does. It is the size of the gap that shows what the threshold is
     * actually buying.
     */
    static int argmaxDisagreements(RealTrainCrossVal.ErrorRow[] errors, double threshold) {
        int n = 0;
        for (RealTrainCrossVal.ErrorRow e : errors) {
            if ((e.pGood() >= threshold) != (e.pred() == GOOD)) n++;
        }
        return n;
    }

    /** The sweep over {@code thresholds}, in the order given. */
    static SweepPoint[] sweep(RealTrainCrossVal.ErrorRow[] errors, double[] thresholds) {
        SweepPoint[] out = new SweepPoint[thresholds.length];
        for (int i = 0; i < thresholds.length; i++) {
            out[i] = new SweepPoint(thresholds[i], countsAt(errors, thresholds[i]));
        }
        return out;
    }

    /** The default sweep reported alongside the screen. */
    static final double[] DEFAULT_SWEEP =
            {0.3, 0.4, 0.5, 0.6, 0.7, 0.8};

    /**
     * Assembles the screen. {@code minFoldPrecision} is the worst per-fold
     * precision at the chosen threshold: a screen whose precision collapses on
     * one fold is not a screen, it is an average.
     */
    static Screen of(RealTrainCrossVal.ErrorRow[] errors, int folds, double threshold,
                     double[] sweepThresholds) {
        Counts[] perFold = new Counts[folds];
        double minPrec = Double.POSITIVE_INFINITY;
        for (int f = 0; f < folds; f++) {
            List<RealTrainCrossVal.ErrorRow> inFold = new ArrayList<>();
            for (RealTrainCrossVal.ErrorRow e : errors) {
                if (e.fold() == f) inFold.add(e);
            }
            perFold[f] = countsAt(inFold.toArray(new RealTrainCrossVal.ErrorRow[0]), threshold);
            minPrec = Math.min(minPrec, perFold[f].precision());
        }
        return new Screen(countsAt(errors, threshold), auc(errors), perFold, minPrec,
                sweep(errors, sweepThresholds), argmaxDisagreements(errors, threshold));
    }

    /** One line per sweep point, for the report. */
    static String renderSweep(SweepPoint[] points) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("%-9s %6s %6s %6s %7s %7s %7s%n",
                "pGood>=", "P", "R", "F1", "tp", "fp", "fn"));
        for (SweepPoint p : points) {
            sb.append(String.format(Locale.ROOT, "%-9.2f %6.3f %6.3f %6.3f %7d %7d %7d%n",
                    p.threshold(), p.counts().precision(), p.counts().recall(), p.counts().f1(),
                    p.counts().tp(), p.counts().fp(), p.counts().fn()));
        }
        return sb.toString();
    }
}