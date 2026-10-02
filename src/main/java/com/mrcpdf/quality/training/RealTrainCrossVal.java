package com.mrcpdf.quality.training;

import com.mrcpdf.quality.model.FeatureExtractor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Grouped K-fold cross-validation of the good/middle/bad readability model on
 * the corpus produced by {@link RealTrainDump}.
 *
 * <p>{@link RealTrainEval} reports a single 80/20 split, so its number depends
 * on which 100 rows the split happened to pick.  CV fits and scores every row
 * instead: the 500 rows are partitioned into {@code --cv} folds (5 by default),
 * each fold is predicted by a model fitted on the other folds, and the held-out
 * predictions are pooled.  Fold assignment is <em>grouped by base page</em>: the
 * 4 variants of a page share its font, layout and text, so a row-level K-fold
 * would put near-duplicates on both sides and inflate the score.  This is the
 * CV form of {@code evalRealTrainModel}'s grouped split.
 *
 * <p>Folds are stratified at the group level by a greedy assignment that
 * balances the per-class row totals (the scikit-learn StratifiedGroupKFold
 * criterion), because base pages are mostly mixed-class -- only a handful are
 * single-class -- so majority-class stratification alone would be coarse.
 *
 * <p>Cut points are fixed absolute thresholds, passed in from the caller and
 * never derived per fold; deriving them per fold would both make folds
 * incomparable and leak the held-out scores into their own classes.
 */
public final class RealTrainCrossVal {

    private static final int N_CLASSES = ClassMetrics.N_CLASSES;
    private static final int IMAGE_FEATURES = FeatureExtractor.IMAGE_FEATURE_NAMES.length;

    private RealTrainCrossVal() {
    }

    /** Fold-level figures used for the spread across folds. */
    private record FoldMetrics(int nTest, int[] truthCounts, double accuracy, double macroF1) {
    }

    /** Everything measured for one feature set. */
    private record CvResult(String featureLabel, int nFeatures, int n,
                            ClassMetrics.Scores pooled, FoldMetrics[] perFold,
                            int[] foldDocCounts) {
    }

    public static void main(String[] args) throws Exception {
        Path features = Path.of("build/real-train-features.csv");
        Path map = Path.of("build/real-train-map.csv");
        Path outJson = Path.of("build/real-train-cv.json");
        Path report = Path.of("build/real-train-cv-report.txt");
        long seed = 42;
        int k = 5;
        int epochs = 400;
        double lr = 0.5;
        double lambda = 0.01;
        int batch = 64;
        Double goodAbove = 0.90;
        Double badBelow = 0.60;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--features" -> features = Path.of(args[++i]);
                case "--map" -> map = Path.of(args[++i]);
                case "--out-json" -> outJson = Path.of(args[++i]);
                case "--report" -> report = Path.of(args[++i]);
                case "--cv" -> k = Integer.parseInt(args[++i]);
                case "--seed" -> seed = Long.parseLong(args[++i]);
                case "--epochs" -> epochs = Integer.parseInt(args[++i]);
                case "--lr" -> lr = Double.parseDouble(args[++i]);
                case "--lambda" -> lambda = Double.parseDouble(args[++i]);
                case "--batch" -> batch = Integer.parseInt(args[++i]);
                case "--good-above" -> goodAbove = Double.parseDouble(args[++i]);
                case "--bad-below" -> badBelow = Double.parseDouble(args[++i]);
                default -> throw new IllegalArgumentException("Unknown flag: " + args[i]);
            }
        }
        if (goodAbove == null || badBelow == null) {
            throw new IllegalArgumentException("--good-above and --bad-below are both required; "
                    + "CV must use fixed cuts, never per-fold terciles");
        }
        if (goodAbove <= badBelow) {
            throw new IllegalArgumentException("--good-above must exceed --bad-below");
        }

        List<String> names = RealTrainDump.featureNames();
        Dataset ds = Dataset.fromCsv(features, names);
        double[] scores = RealTrainEval.readScores(map);
        int n = ds.size();
        if (scores.length != n) {
            throw new IOException("Row count mismatch: features=" + n + " map=" + scores.length);
        }

        String[] docs = new String[n];
        double[][] raw = new double[n][];
        for (int i = 0; i < n; i++) {
            docs[i] = ds.rows().get(i).doc();
            raw[i] = ds.rows().get(i).features();
        }
        int[] y = new int[n];
        for (int i = 0; i < n; i++) y[i] = RealTrainEval.bucket(scores[i], goodAbove, badBelow);

        int nGroups = unique(docs).size();
        if (k < 2) throw new IllegalArgumentException("--cv must be at least 2");
        if (k > nGroups) {
            throw new IllegalArgumentException("--cv " + k + " exceeds " + nGroups + " base pages");
        }

        int allFeatures = names.size();
        List<CvResult> results = List.of(
                crossValidate(allFeatures + " features (image+text)", raw, docs, y, names,
                        allFeatures, k, seed, epochs, lr, lambda, batch),
                crossValidate(IMAGE_FEATURES + " features (image only)", raw, docs, y, names,
                        IMAGE_FEATURES, k, seed, epochs, lr, lambda, batch));

        String text = renderReport(results, n, nGroups, k, seed, goodAbove, badBelow);
        Files.createDirectories(report.toAbsolutePath().getParent());
        Files.writeString(report, text);
        System.out.print(text);
        System.out.println("report  " + report);

        Files.createDirectories(outJson.toAbsolutePath().getParent());
        Files.writeString(outJson, toJson(results, names, IMAGE_FEATURES, k, seed, goodAbove, badBelow));
        System.out.println("cv json " + outJson);
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Cross-validation
    // ──────────────────────────────────────────────────────────────────────────

    static CvResult crossValidate(String featureLabel, double[][] raw, String[] docs, int[] y,
                                  List<String> names, int nFeatures, int k, long seed,
                                  int epochs, double lr, double lambda, int batch) {
        int n = raw.length;
        int[][] folds = groupedFolds(docs, y, k, seed);
        List<String> used = new ArrayList<>(names.subList(0, nFeatures));

        int[] allTruth = new int[n];
        int[] allPred = new int[n];
        double[][] allProbs = new double[n][N_CLASSES];
        FoldMetrics[] perFold = new FoldMetrics[k];
        int[] foldDocCounts = new int[k];
        int cursor = 0;

        boolean[] isTest = new boolean[n];
        for (int f = 0; f < k; f++) {
            int[] testIdx = folds[f];
            for (int i : testIdx) isTest[i] = true;
            List<Integer> trList = new ArrayList<>(n - testIdx.length);
            for (int i = 0; i < n; i++) {
                if (!isTest[i]) trList.add(i);
            }
            for (int i : testIdx) isTest[i] = false;
            int[] trainIdx = toInt(trList);

            Dataset trainDs = slice(raw, docs, trainIdx, nFeatures, used);
            Dataset testDs = slice(raw, docs, testIdx, nFeatures, used);
            Dataset.Stats stats = trainDs.fitStats(0.01, 0.99);
            double[][] xTr = Dataset.matrix(trainDs, stats);
            double[][] xTe = Dataset.matrix(testDs, stats);

            LogisticRegression[] heads = new LogisticRegression[N_CLASSES];
            for (int c = 0; c < N_CLASSES; c++) {
                int[] yc = new int[trainIdx.length];
                for (int i = 0; i < trainIdx.length; i++) yc[i] = y[trainIdx[i]] == c ? 1 : 0;
                heads[c] = LogisticRegression.fit(xTr, yc, epochs, lr, lambda, batch,
                        seed + f * N_CLASSES + c);
            }

            int[] fTruth = new int[testIdx.length];
            int[] fPred = new int[testIdx.length];
            double[][] fProbs = new double[testIdx.length][N_CLASSES];
            for (int i = 0; i < testIdx.length; i++) {
                double best = -1;
                int bestC = 0;
                for (int c = 0; c < N_CLASSES; c++) {
                    double p = heads[c].predict(xTe[i]);
                    fProbs[i][c] = p;
                    if (p > best) {
                        best = p;
                        bestC = c;
                    }
                }
                fTruth[i] = y[testIdx[i]];
                fPred[i] = bestC;
                allTruth[cursor] = fTruth[i];
                allPred[cursor] = bestC;
                allProbs[cursor] = fProbs[i];
                cursor++;
            }
            ClassMetrics.Scores fm = ClassMetrics.of(fTruth, fPred, fProbs);
            perFold[f] = new FoldMetrics(testIdx.length, fm.truthCounts(),
                    fm.accuracy(), fm.macroF1());
            foldDocCounts[f] = unique(docs, testIdx).size();
        }

        ClassMetrics.Scores pooled = ClassMetrics.of(allTruth, allPred, allProbs);
        return new CvResult(featureLabel, nFeatures, n, pooled, perFold, foldDocCounts);
    }

    /**
     * Partitions rows into {@code k} folds, keeping every row of a base page in
     * one fold and greedily balancing per-class row totals across folds.
     *
     * <p>Groups are shuffled deterministically from {@code seed}; for each group
     * (in shuffled order) the fold that minimises the squared deviation of its
     * per-class share from {@code 1/k} is chosen.  Package-private so the fold
     * invariants can be tested directly.
     */
    static int[][] groupedFolds(String[] docs, int[] y, int k, long seed) {
        Map<String, List<Integer>> byDoc = new LinkedHashMap<>();
        List<String> uniq = new ArrayList<>(unique(docs));
        uniq.sort(Comparator.naturalOrder());
        for (String d : uniq) byDoc.put(d, new ArrayList<>());
        for (int i = 0; i < docs.length; i++) byDoc.get(docs[i]).add(i);
        if (k > uniq.size()) {
            throw new IllegalArgumentException("k=" + k + " exceeds " + uniq.size() + " groups");
        }

        List<String> order = new ArrayList<>(uniq);
        Collections.shuffle(order, new Random(seed));

        int[][] foldCounts = new int[k][N_CLASSES];
        Map<String, Integer> foldOf = new LinkedHashMap<>();
        for (String d : order) {
            int[] cnt = new int[N_CLASSES];
            for (int i : byDoc.get(d)) cnt[y[i]]++;
            int bestF = 0;
            double bestScore = Double.POSITIVE_INFINITY;
            for (int f = 0; f < k; f++) {
                double sc = 0;
                for (int c = 0; c < N_CLASSES; c++) {
                    int total = 0;
                    for (int ff = 0; ff < k; ff++) total += foldCounts[ff][c];
                    total += cnt[c];
                    double frac = (foldCounts[f][c] + cnt[c]) / (double) Math.max(1, total);
                    double d2 = frac - 1.0 / k;
                    sc += d2 * d2;
                }
                if (sc < bestScore) {
                    bestScore = sc;
                    bestF = f;
                }
            }
            for (int c = 0; c < N_CLASSES; c++) foldCounts[bestF][c] += cnt[c];
            foldOf.put(d, bestF);
        }

        List<List<Integer>> rows = new ArrayList<>(k);
        for (int f = 0; f < k; f++) rows.add(new ArrayList<>());
        for (int i = 0; i < docs.length; i++) rows.get(foldOf.get(docs[i])).add(i);
        int[][] out = new int[k][];
        for (int f = 0; f < k; f++) out[f] = toInt(rows.get(f));
        return out;
    }

    private static Dataset slice(double[][] raw, String[] docs, int[] idx, int nFeatures,
                                 List<String> names) {
        List<Dataset.Row> rows = new ArrayList<>(idx.length);
        for (int i : idx) {
            rows.add(new Dataset.Row(docs[i], 0, java.util.Arrays.copyOf(raw[i], nFeatures)));
        }
        return Dataset.of(names, rows);
    }

    private static int[] toInt(List<Integer> v) {
        int[] out = new int[v.size()];
        for (int i = 0; i < out.length; i++) out[i] = v.get(i);
        return out;
    }

    private static Set<String> unique(String[] docs) {
        return new LinkedHashSet<>(java.util.Arrays.asList(docs));
    }

    private static Set<String> unique(String[] docs, int[] idx) {
        Set<String> s = new LinkedHashSet<>();
        for (int i : idx) s.add(docs[i]);
        return s;
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Output
    // ──────────────────────────────────────────────────────────────────────────

    private static String renderReport(List<CvResult> results, int n, int nGroups, int k,
                                       long seed, double goodAbove, double badBelow) {
        StringBuilder sb = new StringBuilder();
        sb.append("Good/middle/bad readability model -- grouped ").append(k)
                .append("-fold cross-validation\n");
        sb.append("=".repeat(78)).append('\n');
        sb.append("label          audited word F1 (Tesseract vs the text the generator printed)\n");
        sb.append("features       14 = 11 image + 3 conditional text; 11 = image-only baseline\n");
        sb.append(String.format(Locale.ROOT,
                "thresholds     absolute good>=%.3f, bad<%.3f (fixed; never derived per fold)%n",
                goodAbove, badBelow));
        sb.append("folds          ").append(k).append(", grouped by base page: all ")
                .append(nGroups == 0 ? 0 : n / nGroups)
                .append(" variants of a page share one fold\n");
        sb.append("stratification greedy per-class balance across folds (StratifiedGroupKFold)\n");
        sb.append("seed           ").append(seed).append('\n');
        sb.append("corpus         ").append(n).append(" rows over ").append(nGroups)
                .append(" base pages\n\n");

        sb.append("summary (pooled out-of-fold, n=").append(n).append(")\n");
        sb.append(String.format("%-28s %6s %8s %8s %7s %7s%n",
                "features", "acc", "macroF1", "goodF1", "midF1", "badF1"));
        sb.append("-".repeat(78)).append('\n');
        for (CvResult r : results) {
            sb.append(String.format(Locale.ROOT, "%-28s %6.3f %8.3f %8.3f %7.3f %7.3f%n",
                    r.featureLabel(), r.pooled().accuracy(), r.pooled().macroF1(),
                    r.pooled().f1()[0], r.pooled().f1()[1], r.pooled().f1()[2]));
        }

        sb.append("\nper-fold macroF1 (accuracy in parentheses)\n");
        sb.append(String.format("%-5s %6s", "fold", "nTest"));
        for (CvResult r : results) sb.append(String.format("  %-24s", r.featureLabel()));
        sb.append('\n');
        for (int f = 0; f < k; f++) {
            sb.append(String.format("%-5d %6d", f, results.get(0).perFold()[f].nTest()));
            for (CvResult r : results) {
                FoldMetrics fm = r.perFold()[f];
                sb.append(String.format(Locale.ROOT, "  %-24s",
                        String.format(Locale.ROOT, "%.3f (%.3f)", fm.macroF1(), fm.accuracy())));
            }
            sb.append('\n');
        }
        sb.append(String.format("%-5s %6s", "mean", ""));
        for (CvResult r : results) {
            sb.append(String.format(Locale.ROOT, "  %-24s",
                    String.format(Locale.ROOT, "%.3f (%.3f)", meanMacro(r), meanAcc(r))));
        }
        sb.append('\n');
        sb.append(String.format("%-5s %6s", "std", ""));
        for (CvResult r : results) {
            sb.append(String.format(Locale.ROOT, "  %-24s",
                    String.format(Locale.ROOT, "%.3f (%.3f)", stdMacro(r), stdAcc(r))));
        }
        sb.append('\n');

        for (CvResult r : results) {
            sb.append('\n');
            sb.append("=== ").append(r.featureLabel()).append(" -- pooled out-of-fold (n=")
                    .append(r.n()).append(") ===\n");
            sb.append("test class counts  ");
            for (int c = 0; c < N_CLASSES; c++) {
                sb.append(RealTrainDump.CLASS_NAMES[c]).append('=')
                        .append(r.pooled().truthCounts()[c]).append("  ");
            }
            sb.append('\n');
            sb.append("                P      R      F1     AUC\n");
            for (int c = 0; c < N_CLASSES; c++) {
                sb.append(String.format(Locale.ROOT, "%-8s %.3f  %.3f  %.3f  %.3f%n",
                        RealTrainDump.CLASS_NAMES[c], r.pooled().precision()[c],
                        r.pooled().recall()[c], r.pooled().f1()[c], r.pooled().auc()[c]));
            }
            sb.append("confusion (rows = truth, cols = predicted)\n");
            sb.append("             ");
            for (int c = 0; c < N_CLASSES; c++) {
                sb.append(String.format("%8s", RealTrainDump.CLASS_NAMES[c]));
            }
            sb.append('\n');
            for (int t = 0; t < N_CLASSES; t++) {
                sb.append(String.format("%-12s", RealTrainDump.CLASS_NAMES[t]));
                for (int p = 0; p < N_CLASSES; p++) {
                    sb.append(String.format("%8d", r.pooled().confusion()[t * N_CLASSES + p]));
                }
                sb.append('\n');
            }
            sb.append("folds (held-out base pages)   ");
            for (int f = 0; f < k; f++) sb.append(String.format("%4d", r.foldDocCounts()[f]));
            sb.append('\n');
            sb.append("folds (truth class counts)\n");
            sb.append(String.format("%-5s %6s", "fold", "nTest"));
            for (int c = 0; c < N_CLASSES; c++) {
                sb.append(String.format("%8s", RealTrainDump.CLASS_NAMES[c]));
            }
            sb.append('\n');
            for (int f = 0; f < k; f++) {
                FoldMetrics fm = r.perFold()[f];
                sb.append(String.format("%-5d %6d", f, fm.nTest()));
                for (int c = 0; c < N_CLASSES; c++) {
                    sb.append(String.format("%8d", fm.truthCounts()[c]));
                }
                sb.append('\n');
            }
        }
        return sb.toString();
    }

    private static double meanAcc(CvResult r) {
        return mean(r, true);
    }

    private static double meanMacro(CvResult r) {
        return mean(r, false);
    }

    private static double mean(CvResult r, boolean acc) {
        double s = 0;
        for (FoldMetrics fm : r.perFold()) s += acc ? fm.accuracy() : fm.macroF1();
        return s / r.perFold().length;
    }

    private static double stdAcc(CvResult r) {
        return std(r, true);
    }

    private static double stdMacro(CvResult r) {
        return std(r, false);
    }

    private static double std(CvResult r, boolean acc) {
        double m = mean(r, acc);
        double s = 0;
        for (FoldMetrics fm : r.perFold()) {
            double d = (acc ? fm.accuracy() : fm.macroF1()) - m;
            s += d * d;
        }
        return Math.sqrt(s / r.perFold().length);
    }

    private static String toJson(List<CvResult> results, List<String> names, int imageFeatures,
                                 int k, long seed, double goodAbove, double badBelow) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"label\": \"audited word F1 (Tesseract vs the text the generator printed)\",\n");
        sb.append("  \"classes\": [\"good\", \"middle\", \"bad\"],\n");
        sb.append("  \"method\": \"grouped K-fold cross-validation\",\n");
        sb.append("  \"grouping\": \"base page (doc); all variants of a page held out together\",\n");
        sb.append("  \"stratified\": true,\n");
        sb.append("  \"folds\": ").append(k).append(",\n");
        sb.append("  \"seed\": ").append(seed).append(",\n");
        sb.append(String.format(Locale.ROOT,
                "  \"thresholds\": {\"mode\": \"absolute\", \"goodAbove\": %.3f, \"badBelow\": %.3f},%n",
                goodAbove, badBelow));
        sb.append("  \"featureNames\": [");
        for (int i = 0; i < names.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append('"').append(names.get(i)).append('"');
        }
        sb.append("],\n");
        sb.append("  \"imageFeatureCount\": ").append(imageFeatures).append(",\n");
        sb.append("  \"models\": [\n");
        for (int m = 0; m < results.size(); m++) {
            CvResult r = results.get(m);
            ClassMetrics.Scores p = r.pooled();
            sb.append("    {\n");
            sb.append("      \"features\": \"").append(r.featureLabel()).append("\",\n");
            sb.append("      \"nFeatures\": ").append(r.nFeatures()).append(",\n");
            sb.append("      \"n\": ").append(r.n()).append(",\n");
            sb.append(String.format(Locale.ROOT,
                    "      \"accuracy\": %.6f, \"macroF1\": %.6f,%n", p.accuracy(), p.macroF1()));
            sb.append("      \"precision\": ").append(darr(p.precision())).append(",\n");
            sb.append("      \"recall\": ").append(darr(p.recall())).append(",\n");
            sb.append("      \"f1\": ").append(darr(p.f1())).append(",\n");
            sb.append("      \"auc\": ").append(darr(p.auc())).append(",\n");
            sb.append("      \"testClassCounts\": ").append(iarr(p.truthCounts())).append(",\n");
            sb.append("      \"confusion\": [");
            for (int t = 0; t < N_CLASSES; t++) {
                if (t > 0) sb.append(", ");
                sb.append('[');
                for (int q = 0; q < N_CLASSES; q++) {
                    if (q > 0) sb.append(", ");
                    sb.append(p.confusion()[t * N_CLASSES + q]);
                }
                sb.append(']');
            }
            sb.append("],\n");
            sb.append("      \"heldOutBasePagesPerFold\": ").append(iarr(r.foldDocCounts()))
                    .append(",\n");
            sb.append("      \"perFold\": [\n");
            for (int f = 0; f < k; f++) {
                FoldMetrics fm = r.perFold()[f];
                sb.append(String.format(Locale.ROOT,
                        "        {\"fold\": %d, \"nTest\": %d, \"accuracy\": %.6f, \"macroF1\": %.6f,"
                                + " \"testClassCounts\": %s}",
                        f, fm.nTest(), fm.accuracy(), fm.macroF1(), iarr(fm.truthCounts())));
                if (f < k - 1) sb.append(',');
                sb.append('\n');
            }
            sb.append("      ]\n    }");
            if (m < results.size() - 1) sb.append(',');
            sb.append('\n');
        }
        sb.append("  ]\n}\n");
        return sb.toString();
    }

    private static String darr(double[] v) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < v.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(String.format(Locale.ROOT, "%.6f", v[i]));
        }
        return sb.append(']').toString();
    }

    private static String iarr(int[] v) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < v.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(v[i]);
        }
        return sb.append(']').toString();
    }
}
