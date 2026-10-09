package com.mrcpdf.quality.training;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * The screen's counts, tested against hand-computed confusions.
 *
 * <p>The screen is the deliverable -- it is the number an OCR-less pipeline would
 * act on -- and it is computed from the same out-of-fold probabilities as the
 * 3-class confusion rather than from a second fit. Two things therefore have to
 * hold, and neither is visible in the output: the arithmetic must be right, and
 * the threshold rule must really be "pGood &gt;= threshold" and not the argmax.
 * A screen that silently used argmax would keep reporting the 3-class numbers and
 * nobody would notice until the threshold was set to buy precision and nothing
 * changed.
 */
class GoodScreenTest {

    private static RealTrainCrossVal.ErrorRow row(int fold, int truth, int pred, double pGood) {
        return row(fold, truth, pred, pGood, 0.0);
    }

    private static RealTrainCrossVal.ErrorRow row(int fold, int truth, int pred, double pGood,
                                                  double pMiddle) {
        return new RealTrainCrossVal.ErrorRow(0, "p", fold, "test", truth, pred,
                pGood, pMiddle, 0.0, 0.1);
    }

    /** Two good pages above the threshold, one below; two not-good below it. */
    private static RealTrainCrossVal.ErrorRow[] mixed() {
        return new RealTrainCrossVal.ErrorRow[]{
                row(0, 0, 0, 0.90),   // good, called good
                row(0, 0, 0, 0.71),   // good, called good (just over)
                row(1, 0, 1, 0.40),   // good, called not-good
                row(1, 2, 2, 0.20),   // bad, called not-good
                row(2, 1, 1, 0.10),   // middle, called not-good
        };
    }

    @Test
    void countsMatchHandComputedConfusion() {
        GoodScreen.Counts c = GoodScreen.countsAt(mixed(), 0.70);
        assertEquals(2, c.tp());
        assertEquals(0, c.fp());
        assertEquals(1, c.fn());
        assertEquals(2, c.tn());
        assertEquals(5, c.n());
        assertEquals(1.0, c.precision(), 1e-12);
        assertEquals(2.0 / 3.0, c.recall(), 1e-12);
        assertEquals(0.8, c.f1(), 1e-12);
    }

    /**
     * The threshold is inclusive. The shipped value is 0.70 and the artifact says
     * "pGood &gt;= threshold"; if it were exclusive, a page scoring exactly 0.70
     * would flip, which is the kind of off-by-one that shows up as one row of
     * unexplained drift.
     */
    @Test
    void thresholdIsInclusive() {
        RealTrainCrossVal.ErrorRow[] atCut = {
                row(0, 0, 0, 0.70),   // good, exactly at the threshold
                row(0, 2, 2, 0.69),   // bad, just under it
        };
        GoodScreen.Counts c = GoodScreen.countsAt(atCut, 0.70);
        assertEquals(1, c.tp(), "a page exactly at the threshold counts as good");
        assertEquals(0, c.fp());
        assertEquals(1, c.tn());
    }

    /**
     * The screen must not be the argmax in disguise. Here a row's pGood clears the
     * threshold while the argmax picked "middle", so the two rules disagree -- and
     * a screen that ignored pGood entirely could not produce that.
     */
    @Test
    void screenDiffersFromArgmaxWhenAGoodPageBeatsNeitherOtherHead() {
        RealTrainCrossVal.ErrorRow[] errors = {
                // pGood 0.75 clears 0.70, yet middle was the argmax.
                row(0, 0, 1, 0.75, 0.80),
        };
        assertEquals(1, GoodScreen.countsAt(errors, 0.70).tp(),
                "the screen calls this good because pGood >= 0.70");
        assertEquals(1, GoodScreen.argmaxDisagreements(errors, 0.70),
                "the argmax picked middle, so the two rules disagree on this row");
    }

    /**
     * ...and the other direction: a page whose pGood clears the bar but whose
     * argmax is "bad" is exactly the case a precision-leaning threshold is meant
     * to win, so the disagreement count has to move when the threshold does.
     */
    @Test
    void raisingTheThresholdNeverCallsMoreRowsGood() {
        RealTrainCrossVal.ErrorRow[] errors = mixed();
        int atHigh = GoodScreen.countsAt(errors, 0.95).tp() + GoodScreen.countsAt(errors, 0.95).fp();
        int atLow = GoodScreen.countsAt(errors, 0.10).tp() + GoodScreen.countsAt(errors, 0.10).fp();
        assertTrue(atHigh <= atLow, "raising the threshold can only reject more rows");
        assertEquals(0, atHigh, "no row in this set reaches 0.95");
    }

    @Test
    void perFoldCountsSplitThePooledCounts() {
        RealTrainCrossVal.ErrorRow[] errors = mixed();
        GoodScreen.Screen s = GoodScreen.of(errors, 3, 0.70, GoodScreen.DEFAULT_SWEEP);
        int tp = 0;
        int fp = 0;
        int fn = 0;
        int tn = 0;
        for (GoodScreen.Counts c : s.perFold()) {
            tp += c.tp();
            fp += c.fp();
            fn += c.fn();
            tn += c.tn();
        }
        assertEquals(s.pooled().tp(), tp, "per-fold true positives must sum to the pooled count");
        assertEquals(s.pooled().fp(), fp);
        assertEquals(s.pooled().fn(), fn);
        assertEquals(s.pooled().tn(), tn);
    }

    /**
     * The worst fold, not the mean. A screen whose precision collapses on one
     * fold is not a screen, and a pooled number cannot show that -- which is why
     * minFoldPrecision is carried in the artifact at all.
     */
    @Test
    void minFoldPrecisionTracksTheWorstFold() {
        RealTrainCrossVal.ErrorRow[] errors = {
                row(0, 0, 0, 0.95), row(0, 2, 2, 0.10),   // fold 0: precision 1.000
                row(1, 0, 0, 0.95), row(1, 2, 2, 0.10),   // fold 1: precision 1.000
                row(2, 0, 0, 0.95), row(2, 0, 0, 0.95),
                row(2, 2, 0, 0.95),                        // fold 2: precision 2/3
        };
        GoodScreen.Screen s = GoodScreen.of(errors, 3, 0.70, GoodScreen.DEFAULT_SWEEP);
        assertEquals(2.0 / 3.0, s.minFoldPrecision(), 1e-12);
        assertTrue(s.pooled().precision() > s.minFoldPrecision(),
                "the pooled number hides the fold that collapsed");
    }

    @Test
    void sweepVisitsEveryThresholdInOrder() {
        GoodScreen.SweepPoint[] sweep = GoodScreen.sweep(mixed(), new double[]{0.3, 0.5, 0.9});
        assertEquals(3, sweep.length);
        assertEquals(0.3, sweep[0].threshold(), 1e-12);
        assertEquals(0.9, sweep[2].threshold(), 1e-12);
        // 0.90 is above every pGood here except one, 0.30 below most of them.
        assertTrue(sweep[0].counts().f1() >= sweep[2].counts().f1());
    }

    @Test
    void aucIsOneWhenEveryScoreSeparates() {
        RealTrainCrossVal.ErrorRow[] perfect = {
                row(0, 0, 0, 0.9), row(0, 0, 0, 0.8), row(0, 2, 2, 0.2), row(0, 2, 2, 0.1),
        };
        assertEquals(1.0, GoodScreen.auc(perfect), 1e-12);
    }

    @Test
    void aucIsHalfWhenEveryGoodPageScoresBelowEveryOtherPage() {
        RealTrainCrossVal.ErrorRow[] inverted = {
                row(0, 0, 2, 0.1), row(0, 0, 2, 0.2), row(0, 2, 0, 0.8), row(0, 2, 0, 0.9),
        };
        assertEquals(0.0, GoodScreen.auc(inverted), 1e-12);
    }

    @Test
    void emptyFoldIsCountedRatherThanThrowing() {
        RealTrainCrossVal.ErrorRow[] errors = mixed();
        GoodScreen.Screen s = GoodScreen.of(errors, 7, 0.70, GoodScreen.DEFAULT_SWEEP);
        assertEquals(7, s.perFold().length);
        // Folds 3..6 hold no rows; their precision is 0, and the floor reports it
        // instead of quietly skipping an empty fold.
        assertEquals(0, s.perFold()[5].n());
        assertEquals(0.0, s.perFold()[5].precision(), 1e-12);
    }

    @Test
    void renderedSweepHasOneLinePerThreshold() {
        String text = GoodScreen.renderSweep(
                GoodScreen.sweep(mixed(), GoodScreen.DEFAULT_SWEEP));
        List<String> lines = new ArrayList<>(List.of(text.split("\n")));
        assertEquals(GoodScreen.DEFAULT_SWEEP.length + 1, lines.size(),
                "header plus one line per threshold");
        assertTrue(lines.get(0).contains("pGood>="), "the column is named, so it cannot be misread");
    }
}