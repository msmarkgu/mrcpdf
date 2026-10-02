package com.mrcpdf.quality.training;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * Invariants of the grouped K-fold split and of the shared confusion metrics.
 *
 * <p>The split is the load-bearing part: if a base page ever straddled two
 * folds, the CV number would silently become the optimistic row-level number
 * that {@link RealTrainCrossVal} exists to avoid. The synthetic data below
 * gives every base page all three classes, so "every fold has every class"
 * holds for any non-empty fold and does not depend on the greedy balance
 * happening to work out.
 */
class RealTrainCrossValTest {

    private static final int N_CLASSES = ClassMetrics.N_CLASSES;

    /** 20 base pages, 4 variants each, all three classes inside every page. */
    private static String[] docs() {
        String[] docs = new String[80];
        for (int d = 0; d < 20; d++) {
            for (int j = 0; j < 4; j++) {
                docs[d * 4 + j] = "p" + (d + 1);
            }
        }
        return docs;
    }

    private static int[] labels() {
        int[] y = new int[80];
        for (int d = 0; d < 20; d++) {
            for (int j = 0; j < 4; j++) {
                y[d * 4 + j] = (d + j) % N_CLASSES;
            }
        }
        return y;
    }

    @Test
    void foldsAreDisjointAndCoverEveryRow() {
        int[][] folds = RealTrainCrossVal.groupedFolds(docs(), labels(), 5, 42);
        boolean[] seen = new boolean[80];
        int total = 0;
        for (int[] fold : folds) {
            assertTrue(fold.length > 0, "a fold must not be empty");
            for (int i : fold) {
                assertTrue(!seen[i], "row " + i + " appears in more than one fold");
                seen[i] = true;
                total++;
            }
        }
        assertEquals(80, total, "folds must cover every row exactly once");
    }

    @Test
    void noBasePageStraddlesTwoFolds() {
        String[] docs = docs();
        int[][] folds = RealTrainCrossVal.groupedFolds(docs, labels(), 5, 42);
        // fold index per doc, filled from the first row of each doc seen.
        java.util.Map<String, Integer> foldOfDoc = new java.util.HashMap<>();
        for (int f = 0; f < folds.length; f++) {
            for (int i : folds[f]) {
                Integer prev = foldOfDoc.put(docs[i], f);
                if (prev != null) {
                    assertEquals(prev.intValue(), f,
                            "base page " + docs[i] + " appears in folds " + prev + " and " + f);
                } else {
                    foldOfDoc.put(docs[i], f);
                }
            }
        }
        // All 4 variants of each page must be in that same fold.
        for (int f = 0; f < folds.length; f++) {
            Set<String> inFold = new LinkedHashSet<>();
            for (int i : folds[f]) inFold.add(docs[i]);
            for (String d : inFold) {
                int count = 0;
                for (String doc : docs) if (doc.equals(d)) count++;
                int inThisFold = 0;
                for (int i : folds[f]) if (docs[i].equals(d)) inThisFold++;
                assertEquals(count, inThisFold, "page " + d + " is split across folds");
            }
        }
    }

    @Test
    void everyFoldContainsEveryClass() {
        int[][] folds = RealTrainCrossVal.groupedFolds(docs(), labels(), 5, 42);
        int[] y = labels();
        for (int f = 0; f < folds.length; f++) {
            boolean[] present = new boolean[N_CLASSES];
            for (int i : folds[f]) present[y[i]] = true;
            for (int c = 0; c < N_CLASSES; c++) {
                assertTrue(present[c], "fold " + f + " is missing class " + c);
            }
        }
    }

    @Test
    void sameSeedGivesTheSameFolds() {
        int[][] a = RealTrainCrossVal.groupedFolds(docs(), labels(), 5, 42);
        int[][] b = RealTrainCrossVal.groupedFolds(docs(), labels(), 5, 42);
        assertEquals(a.length, b.length);
        for (int f = 0; f < a.length; f++) {
            assertArrayEquals(a[f], b[f], "fold " + f + " is not deterministic for a fixed seed");
        }
    }

    @Test
    void tooManyFoldsIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> RealTrainCrossVal.groupedFolds(docs(), labels(), 21, 42),
                "asking for more folds than base pages must fail loudly, not silently");
    }

    @Test
    void classMetricsMatchHandComputedConfusion() {
        int[] truth = {0, 0, 1, 1, 2, 2};
        int[] pred = {0, 1, 1, 2, 2, 2};
        double[][] probs = {
                {1, 0, 0},
                {0, 1, 0},
                {0, 1, 0},
                {0, 0, 1},
                {0, 0, 1},
                {0, 0, 1},
        };
        ClassMetrics.Scores m = ClassMetrics.of(truth, pred, probs);

        assertArrayEquals(new int[]{1, 1, 0, 0, 1, 1, 0, 0, 2}, m.confusion());
        assertArrayEquals(new int[]{2, 2, 2}, m.truthCounts());
        assertEquals(4.0 / 6.0, m.accuracy(), 1e-12);
        assertArrayEquals(new double[]{1.0, 0.5, 2.0 / 3.0}, m.precision(), 1e-12);
        assertArrayEquals(new double[]{0.5, 0.5, 1.0}, m.recall(), 1e-12);
        assertArrayEquals(new double[]{2.0 / 3.0, 0.5, 0.8}, m.f1(), 1e-12);
        assertEquals((2.0 / 3.0 + 0.5 + 0.8) / 3.0, m.macroF1(), 1e-12);
        // Ties between a positive and a negative score count as 0.5, so none of
        // the three classes is perfectly separable even though the argmax is.
        assertArrayEquals(new double[]{0.75, 0.625, 0.875}, m.auc(), 1e-12);
    }

    @Test
    void classMetricsRejectsOutOfRangeClasses() {
        assertThrows(IllegalArgumentException.class,
                () -> ClassMetrics.of(new int[]{0}, new int[]{3}, new double[][]{{0, 0, 0}}));
    }
}
