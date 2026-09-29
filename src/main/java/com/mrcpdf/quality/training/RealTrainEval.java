package com.mrcpdf.quality.training;

import com.mrcpdf.quality.model.FeatureExtractor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;

/**
 * Trains and evaluates good/middle/bad logistic-regression models on the corpus
 * produced by {@link RealTrainDump}.
 *
 * <p>Reports four combinations: {14 features, 11 image-only} x {random 250/1000
 * split, grouped split holding out 25 whole base pages}.  The grouped split is
 * the honest number: the 10 variants of a base page share its font, layout and
 * text, so a random split puts near-duplicates on both sides and inflates
 * accuracy.
 *
 * <p>The label is the audited word F1 written by {@link RealTrainDump}. It is
 * deliberately spread rather than bimodal -- it is a weighted geometric
 * mean over four dimensions, so it degrades smoothly as a page is scanned worse
 * -- which is why it is usable with fixed cuts.  By default the two cut points
 * are still taken as terciles <em>of the training split's scores</em>, because
 * that keeps the classes balanced whatever the corpus turns out to look like and
 * leaks nothing from the test rows.  Pass {@code --good-above}/{@code --bad-below}
 * to force absolute thresholds instead; the values used are always reported.
 *
 * <p>Three one-vs-rest {@link LogisticRegression}s are fitted per model, because
 * the existing fitter is binary.
 */
public final class RealTrainEval {

    private static final int N_CLASSES = 3;
    /** The map column holding the label; see {@link #readScores}. */
    private static final String LABEL_COLUMN = "labelWordF1";
    private static final int IMAGE_FEATURES = FeatureExtractor.IMAGE_FEATURE_NAMES.length;

    private RealTrainEval() {
    }

    /** One fitted one-vs-rest head. */
    private record Head(int cls, LogisticRegression model) {
    }

    /** Everything measured for one feature-set/split combination. */
    private record Result(String featureSet, String split, int nTrain, int nTest,
                          double goodCut, double badCut,
                          int[] confusion, double[] precision, double[] recall, double[] f1,
                          double[] auc, double accuracy, double macroF1,
                          int[] testClassCounts, Head[] heads) {
    }

    public static void main(String[] args) throws Exception {
        Path features = Path.of("build/real-train-features.csv");
        Path map = Path.of("build/real-train-map.csv");
        Path outJson = Path.of("build/real-train-model.json");
        Path report = Path.of("build/real-train-report.txt");
        long seed = 42;
        int epochs = 400;
        double lr = 0.5;
        double lambda = 0.01;
        int batch = 64;
        double testFrac = 0.20;
        Double goodAbove = null;
        Double badBelow = null;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--features" -> features = Path.of(args[++i]);
                case "--map" -> map = Path.of(args[++i]);
                case "--out-json" -> outJson = Path.of(args[++i]);
                case "--report" -> report = Path.of(args[++i]);
                case "--seed" -> seed = Long.parseLong(args[++i]);
                case "--epochs" -> epochs = Integer.parseInt(args[++i]);
                case "--lr" -> lr = Double.parseDouble(args[++i]);
                case "--lambda" -> lambda = Double.parseDouble(args[++i]);
                case "--batch" -> batch = Integer.parseInt(args[++i]);
                case "--test-frac" -> testFrac = Double.parseDouble(args[++i]);
                case "--good-above" -> goodAbove = Double.parseDouble(args[++i]);
                case "--bad-below" -> badBelow = Double.parseDouble(args[++i]);
                default -> throw new IllegalArgumentException("Unknown flag: " + args[i]);
            }
        }
        boolean absolute = goodAbove != null && badBelow != null;
        if (absolute && goodAbove <= badBelow) {
            throw new IllegalArgumentException("--good-above must exceed --bad-below");
        }

        List<String> names = RealTrainDump.featureNames();
        Dataset ds = Dataset.fromCsv(features, names);
        double[] scores = readScores(map);
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

        int[][] random = splitRandom(n, testFrac, seed);
        int[][] grouped = splitGrouped(docs, testFrac, seed);

        System.out.println("RealTrainEval: " + n + " rows, " + unique(docs).size()
                + " base pages, " + names.size() + " features ("
                + IMAGE_FEATURES + " image + " + (names.size() - IMAGE_FEATURES) + " text)");
        System.out.println("  random split : train=" + random[0].length + " test=" + random[1].length);
        System.out.println("  grouped split: train=" + grouped[0].length + " test=" + grouped[1].length
                + " across " + unique(docs, grouped[1]).size() + " held-out base pages");

        List<Result> results = new ArrayList<>();
        int allFeatures = names.size();
        results.add(evaluate("random", allFeatures + " features (image+text)", raw, docs, scores,
                random, names, allFeatures, epochs, lr, lambda, batch, seed, absolute,
                goodAbove, badBelow));
        results.add(evaluate("random", IMAGE_FEATURES + " features (image only)", raw, docs, scores,
                random, names, IMAGE_FEATURES, epochs, lr, lambda, batch, seed, absolute,
                goodAbove, badBelow));
        results.add(evaluate("grouped", allFeatures + " features (image+text)", raw, docs, scores,
                grouped, names, allFeatures, epochs, lr, lambda, batch, seed, absolute,
                goodAbove, badBelow));
        results.add(evaluate("grouped", IMAGE_FEATURES + " features (image only)", raw, docs, scores,
                grouped, names, IMAGE_FEATURES, epochs, lr, lambda, batch, seed, absolute,
                goodAbove, badBelow));

        String text = renderReport(results, absolute, goodAbove, badBelow, testFrac);
        Files.createDirectories(report.toAbsolutePath().getParent());
        Files.writeString(report, text);
        System.out.print(text);
        System.out.println("report  " + report);

        Files.createDirectories(outJson.toAbsolutePath().getParent());
        Files.writeString(outJson, toJson(results, names, IMAGE_FEATURES, absolute, goodAbove, badBelow));
        System.out.println("model   " + outJson);
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Evaluation
    // ──────────────────────────────────────────────────────────────────────────

    private static Result evaluate(String splitName, String featureLabel, double[][] raw, String[] docs,
                                   double[] scores, int[][] split, List<String> names, int nFeatures,
                                   int epochs, double lr, double lambda, int batch, long seed,
                                   boolean absolute, Double goodAbove, Double badBelow) {
        int[] trainIdx = split[0];
        int[] testIdx = split[1];

        double goodCut;
        double badCut;
        if (absolute) {
            goodCut = goodAbove;
            badCut = badBelow;
        } else {
            double[] tr = new double[trainIdx.length];
            for (int i = 0; i < trainIdx.length; i++) tr[i] = scores[trainIdx[i]];
            Arrays.sort(tr);
            // Terciles: bottom third = bad, top third = good, middle = middle.
            badCut = quantile(tr, 1.0 / 3.0);
            goodCut = quantile(tr, 2.0 / 3.0);
        }
        int[] y = new int[scores.length];
        for (int i = 0; i < scores.length; i++) y[i] = bucket(scores[i], goodCut, badCut);

        List<String> used = new ArrayList<>(names.subList(0, nFeatures));
        Dataset trainDs = slice(raw, docs, trainIdx, nFeatures, used);
        Dataset testDs = slice(raw, docs, testIdx, nFeatures, used);
        Dataset.Stats stats = trainDs.fitStats(0.01, 0.99);
        double[][] xTr = Dataset.matrix(trainDs, stats);
        double[][] xTe = Dataset.matrix(testDs, stats);

        Head[] heads = new Head[N_CLASSES];
        for (int c = 0; c < N_CLASSES; c++) {
            int[] yc = new int[trainIdx.length];
            for (int i = 0; i < trainIdx.length; i++) yc[i] = y[trainIdx[i]] == c ? 1 : 0;
            heads[c] = new Head(c, LogisticRegression.fit(xTr, yc, epochs, lr, lambda, batch, seed + c));
        }

        int[] confusion = new int[N_CLASSES * N_CLASSES];
        int[] testCounts = new int[N_CLASSES];
        int[] truth = new int[testIdx.length];
        int[] pred = new int[testIdx.length];
        double[][] probs = new double[testIdx.length][N_CLASSES];
        for (int i = 0; i < testIdx.length; i++) {
            double best = -1;
            int bestC = 0;
            for (int c = 0; c < N_CLASSES; c++) {
                double p = heads[c].model().predict(xTe[i]);
                probs[i][c] = p;
                if (p > best) {
                    best = p;
                    bestC = c;
                }
            }
            int t = y[testIdx[i]];
            truth[i] = t;
            pred[i] = bestC;
            testCounts[t]++;
            confusion[t * N_CLASSES + bestC]++;
        }

        int correct = 0;
        for (int i = 0; i < testIdx.length; i++) {
            if (truth[i] == pred[i]) correct++;
        }
        double accuracy = (double) correct / testIdx.length;

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
            int[] yc = new int[testIdx.length];
            double[] sc = new double[testIdx.length];
            for (int i = 0; i < testIdx.length; i++) {
                yc[i] = truth[i] == c ? 1 : 0;
                sc[i] = probs[i][c];
            }
            auc[c] = LogisticRegression.auc(yc, sc);
        }

        return new Result(featureLabel, splitName, trainIdx.length, testIdx.length,
                goodCut, badCut, confusion, precision, recall, f1, auc, accuracy, macro,
                testCounts, heads);
    }

    private static Dataset slice(double[][] raw, String[] docs, int[] idx, int nFeatures,
                                 List<String> names) {
        List<Dataset.Row> rows = new ArrayList<>(idx.length);
        for (int i : idx) {
            rows.add(new Dataset.Row(docs[i], 0, Arrays.copyOf(raw[i], nFeatures)));
        }
        return Dataset.of(names, rows);
    }

    private static int bucket(double score, double goodCut, double badCut) {
        if (score >= goodCut) return 0;
        if (score >= badCut) return 1;
        return 2;
    }

    private static double quantile(double[] sorted, double q) {
        if (sorted.length == 0) return 0;
        int i = (int) Math.round(q * (sorted.length - 1));
        return sorted[Math.max(0, Math.min(sorted.length - 1, i))];
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Splits
    // ──────────────────────────────────────────────────────────────────────────

    private static int[][] splitRandom(int n, double testFrac, long seed) {
        List<Integer> shuffled = new ArrayList<>(n);
        for (int i = 0; i < n; i++) shuffled.add(i);
        Collections.shuffle(shuffled, new Random(seed));
        int nTest = (int) Math.round(testFrac * n);
        List<Integer> te = new ArrayList<>();
        List<Integer> tr = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            if (i < nTest) {
                te.add(shuffled.get(i));
            } else {
                tr.add(shuffled.get(i));
            }
        }
        return new int[][]{toInt(tr), toInt(te)};
    }

    /** Holds out whole base pages, so all 10 variants of a page land on one side. */
    private static int[][] splitGrouped(String[] docs, double testFrac, long seed) {
        List<String> uniq = new ArrayList<>(new LinkedHashSet<>(Arrays.asList(docs)));
        uniq.sort(Comparator.naturalOrder());
        Collections.shuffle(uniq, new Random(seed));
        int nTestDocs = (int) Math.round(testFrac * uniq.size());
        Set<String> held = new LinkedHashSet<>(uniq.subList(0, nTestDocs));
        List<Integer> tr = new ArrayList<>();
        List<Integer> te = new ArrayList<>();
        for (int i = 0; i < docs.length; i++) {
            if (held.contains(docs[i])) {
                te.add(i);
            } else {
                tr.add(i);
            }
        }
        return new int[][]{toInt(tr), toInt(te)};
    }

    private static int[] toInt(List<Integer> v) {
        int[] out = new int[v.size()];
        for (int i = 0; i < out.length; i++) out[i] = v.get(i);
        return out;
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Input / output
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Reads the label column from the shard map.
     *
     * <p>The column is resolved by name rather than index: the map has been
     * renamed more than once ({@code score}, then {@code R_mrc}, then
     * {@code labelWordF1} when the label became the audit rather than the
     * composite), and a hardcoded index would quietly train on the wrong field
     * instead of failing.
     */
    private static double[] readScores(Path map) throws IOException {
        List<String> lines = Files.readAllLines(map);
        if (lines.isEmpty()) {
            throw new IllegalArgumentException("empty map: " + map);
        }
        String[] header = lines.get(0).trim().split(",");
        int col = -1;
        for (int i = 0; i < header.length; i++) {
            if (header[i].trim().equalsIgnoreCase(LABEL_COLUMN)) {
                col = i;
                break;
            }
        }
        if (col < 0) {
            throw new IllegalArgumentException("no " + LABEL_COLUMN + " column in " + map
                    + "; header was " + String.join(",", header));
        }
        List<Double> out = new ArrayList<>();
        for (int i = 1; i < lines.size(); i++) {
            String line = lines.get(i).trim();
            if (line.isEmpty()) continue;
            String[] c = line.split(",");
            if (c.length <= col) {
                throw new IllegalArgumentException("row " + i + " has " + c.length
                        + " columns, expected more than " + col);
            }
            out.add(Double.parseDouble(c[col].trim()));
        }
        double[] scores = new double[out.size()];
        for (int i = 0; i < scores.length; i++) scores[i] = out.get(i);
        return scores;
    }

    private static Set<String> unique(String[] docs) {
        return new LinkedHashSet<>(Arrays.asList(docs));
    }

    private static Set<String> unique(String[] docs, int[] idx) {
        Set<String> s = new LinkedHashSet<>();
        for (int i : idx) s.add(docs[i]);
        return s;
    }

    private static String renderReport(List<Result> results, boolean absolute, Double goodAbove,
                                      Double badBelow, double testFrac) {
        StringBuilder sb = new StringBuilder();
        sb.append("Good/middle/bad readability model -- test-set results\n");
        sb.append("=".repeat(78)).append('\n');
        sb.append("label          audited word F1 (Tesseract vs the text the generator printed)\n");
        sb.append("                audited separately by word-multiset F1, not by this model\n");
        sb.append("features       14 = 11 image + 3 conditional text; 11 = image-only baseline\n");
        sb.append("thresholds     ")
                .append(absolute
                        ? String.format(Locale.ROOT, "absolute good>=%.3f, bad<%.3f%n", goodAbove, badBelow)
                        : String.format(Locale.ROOT, "terciles of each split's training scores "
                        + "(absolute cuts reported per row)%n"))
                .append("split          random = shuffled rows; grouped = 25 whole base pages held out\n");
        sb.append("               (all 10 variants of a base page share one side in the grouped split)\n");
        sb.append("test fraction  ").append(String.format(Locale.ROOT, "%.2f%n", testFrac)).append('\n');

        sb.append(String.format("%-8s %-26s %6s %8s %8s %8s%n",
                "split", "features", "acc", "macroF1", "goodF1", "note"));
        sb.append("-".repeat(78)).append('\n');
        for (Result r : results) {
            sb.append(String.format(Locale.ROOT, "%-8s %-26s %6.3f %8.3f %8.3f   cuts %.3f/%.3f%n",
                    r.split(), r.featureSet(), r.accuracy(), r.macroF1(), r.f1()[0],
                    r.badCut(), r.goodCut()));
        }

        for (Result r : results) {
            sb.append('\n');
            sb.append("=== ").append(r.split()).append(" / ").append(r.featureSet())
                    .append("  (train=").append(r.nTrain()).append(", test=").append(r.nTest())
                    .append(") ===\n");
            sb.append("cuts           good >= ").append(String.format(Locale.ROOT, "%.3f", r.goodCut()))
                    .append(",  bad < ").append(String.format(Locale.ROOT, "%.3f", r.badCut())).append('\n');
            sb.append("test class counts  ");
            for (int c = 0; c < N_CLASSES; c++) {
                sb.append(RealTrainDump.CLASS_NAMES[c]).append('=').append(r.testClassCounts()[c]).append("  ");
            }
            sb.append('\n');
            sb.append("                P      R      F1     AUC\n");
            for (int c = 0; c < N_CLASSES; c++) {
                sb.append(String.format(Locale.ROOT, "%-8s %.3f  %.3f  %.3f  %.3f%n",
                        RealTrainDump.CLASS_NAMES[c], r.precision()[c], r.recall()[c],
                        r.f1()[c], r.auc()[c]));
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
                    sb.append(String.format("%8d", r.confusion()[t * N_CLASSES + p]));
                }
                sb.append('\n');
            }
        }
        return sb.toString();
    }

    private static String toJson(List<Result> results, List<String> names, int imageFeatures,
                                 boolean absolute, Double goodAbove, Double badBelow) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"label\": \"measured R_mrc (reference-free page-readability score)\",\n");
        sb.append("  \"classes\": [\"good\", \"middle\", \"bad\"],\n");
        sb.append("  \"featureNames\": [");
        for (int i = 0; i < names.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append('"').append(names.get(i)).append('"');
        }
        sb.append("],\n");
        sb.append("  \"imageFeatureCount\": ").append(imageFeatures).append(",\n");
        sb.append("  \"thresholds\": {\"mode\": \"").append(absolute ? "absolute" : "train-terciles")
                .append("\", \"goodAbove\": ").append(absolute ? goodAbove : null)
                .append(", \"badBelow\": ").append(absolute ? badBelow : null).append("},\n");
        sb.append("  \"models\": [\n");
        for (int m = 0; m < results.size(); m++) {
            Result r = results.get(m);
            sb.append("    {\n");
            sb.append("      \"split\": \"").append(r.split()).append("\",\n");
            sb.append("      \"features\": \"").append(r.featureSet()).append("\",\n");
            sb.append("      \"nTrain\": ").append(r.nTrain())
                    .append(", \"nTest\": ").append(r.nTest()).append(",\n");
            sb.append(String.format(Locale.ROOT,
                    "      \"accuracy\": %.6f, \"macroF1\": %.6f,%n", r.accuracy(), r.macroF1()));
            sb.append(String.format(Locale.ROOT, "      \"goodCut\": %.6f, \"badCut\": %.6f,%n",
                    r.goodCut(), r.badCut()));
            sb.append("      \"heads\": [");
            for (int h = 0; h < r.heads().length; h++) {
                if (h > 0) sb.append(", ");
                double[] w = r.heads()[h].model().weights();
                sb.append("{\"class\": \"").append(RealTrainDump.CLASS_NAMES[h]).append("\", \"bias\": ")
                        .append(String.format(Locale.ROOT, "%.6f", r.heads()[h].model().bias()))
                        .append(", \"weights\": [");
                for (int i = 0; i < w.length; i++) {
                    if (i > 0) sb.append(", ");
                    sb.append(String.format(Locale.ROOT, "%.6f", w[i]));
                }
                sb.append("]}");
            }
            sb.append("]\n    }");
            if (m < results.size() - 1) sb.append(',');
            sb.append('\n');
        }
        sb.append("  ]\n}\n");
        return sb.toString();
    }
}
