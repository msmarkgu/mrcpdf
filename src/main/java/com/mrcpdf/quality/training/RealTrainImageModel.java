package com.mrcpdf.quality.training;

import com.mrcpdf.quality.model.FeatureExtractor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Fits the OCR-free good-vs-rest screen on the 11 image features and writes it as
 * a research artifact, {@code build/real-train-image-model.json}.
 *
 * <p>This is the model to reach for when no OCR text is available: it reads only
 * image statistics, so it can score a scan before anything has tried to read it.
 * On the 500-row corpus it separates good pages from the rest at pooled
 * out-of-fold P 0.942 / R 0.792 (F1 0.860, AUC 0.947) -- see
 * {@link GoodScreen} and the CV report for those numbers, which come from
 * per-fold models rather than from the head written here.
 *
 * <p>No dedicated binary head is fitted, and that is deliberate. The good head of
 * the three one-vs-rest heads already <em>is</em> a good-vs-rest classifier;
 * fitting a second one on the same rows would measure the same thing and add a
 * threshold that could disagree with the first. So the artifact carries all three
 * heads, and the screen is the good head's probability against an explicit
 * threshold. The middle and bad heads are kept for comparability only: without
 * OCR text they barely separate their classes (F1 0.665 and 0.502).
 *
 * <p>Unlike {@code real-train-model.json}, this artifact carries the scaler
 * ({@code winsorizeLower/Upper}, {@code means}, {@code stds}) alongside the
 * weights. Without it the weights are not scores, they are numbers, and the file
 * can only be read by the program that wrote it.
 *
 * <p><b>Polarity.</b> Higher {@code pGood} means <em>better</em>. {@link
 * com.mrcpdf.quality.model.QualityModel#gate} treats a higher score as
 * <em>degraded</em>, so this file must not be handed to {@link
 * com.mrcpdf.quality.model.QualityModel#load} -- that would invert every
 * decision it makes. It is read through {@link
 * com.mrcpdf.quality.model.QualityModel#loadScreen}, which negates the weights
 * and flips the threshold so the pipeline keeps one polarity. The identity
 * {@code loadScreen(...).predict(x) == 1 - pGood(x)} is pinned by a golden test.
 *
 * <p><b>Metrics.</b> The {@code metrics} block is the shipped head scored on the
 * held-out rows it never saw: a single 100-row grouped split, so noisy, but a
 * measurement of <em>this</em> model rather than of the per-fold models behind
 * the cross-validated numbers.
 */
public final class RealTrainImageModel {

    private static final int N_CLASSES = ClassMetrics.N_CLASSES;
    private static final String[] IMAGE_FEATURES = FeatureExtractor.IMAGE_FEATURE_NAMES;
    /** Index of "good" in {@link RealTrainDump#CLASS_NAMES}, and of its head. */
    private static final int GOOD_HEAD = 0;

    /**
     * The screen's operating point: call a page good at {@code pGood >= 0.7}.
     *
     * <p>Chosen over the F1-optimal 0.5 for precision (0.942 against 0.915) at a
     * recall cost of about 4 good pages per 500. It was picked by looking at
     * out-of-fold predictions, so the metrics quoted for it are mildly optimistic;
     * the sweep is written into the artifact so that cost is visible rather than
     * assumed away.
     */
    static final double SCREEN_THRESHOLD = 0.70;

    private RealTrainImageModel() {
    }

    /** One fitted head plus the class it stands for. */
    record Head(String cls, LogisticRegression model) {
    }

    /**
     * The shipped head scored on the rows it was not fitted on, at the screen's
     * operating point. Measured, not cross-validated: one grouped holdout.
     */
    record Metrics(int rows, double auc, GoodScreen.Counts counts, double threshold) {
        double precision() {
            return counts.precision();
        }

        double recall() {
            return counts.recall();
        }

        double f1() {
            return counts.f1();
        }
    }

    /**
     * The fitted screen: the three heads, the scaler they were fitted against, the
     * split they were fitted on, and how the shipped head scored on the held-out
     * side of that split.
     */
    record ImageModel(List<String> featureNames, Head[] heads, Dataset.Stats stats,
                      double threshold, int[] trainIdx, int[] testIdx, long seed,
                      int epochs, double lr, double lambda, int batch,
                      double goodAbove, double badBelow, Metrics metrics) {
        /** The screen's own decision rule: the good head's probability, thresholded. */
        boolean screen(double[] rawFeatures) {
            double[] x = stats.transform(rawFeatures);
            return heads[GOOD_HEAD].model().predict(x) >= threshold;
        }
    }

    public static void main(String[] args) throws Exception {
        Path features = Path.of("build/real-train-features.csv");
        Path map = Path.of("build/real-train-map.csv");
        Path outJson = Path.of("build/real-train-image-model.json");
        long seed = 42;
        int epochs = 400;
        double lr = 0.5;
        double lambda = 0.01;
        int batch = 64;
        double testFrac = 0.20;
        double threshold = SCREEN_THRESHOLD;
        double goodAbove = 0.90;
        double badBelow = 0.60;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--features" -> features = Path.of(args[++i]);
                case "--map" -> map = Path.of(args[++i]);
                case "--out-json" -> outJson = Path.of(args[++i]);
                case "--seed" -> seed = Long.parseLong(args[++i]);
                case "--epochs" -> epochs = Integer.parseInt(args[++i]);
                case "--lr" -> lr = Double.parseDouble(args[++i]);
                case "--lambda" -> lambda = Double.parseDouble(args[++i]);
                case "--batch" -> batch = Integer.parseInt(args[++i]);
                case "--test-frac" -> testFrac = Double.parseDouble(args[++i]);
                case "--threshold" -> threshold = Double.parseDouble(args[++i]);
                case "--good-above" -> goodAbove = Double.parseDouble(args[++i]);
                case "--bad-below" -> badBelow = Double.parseDouble(args[++i]);
                default -> throw new IllegalArgumentException("Unknown flag: " + args[i]);
            }
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

        int[][] split = RealTrainEval.splitGrouped(docs, testFrac, seed);
        ImageModel model = fit(raw, docs, y, split[0], split[1], seed, epochs, lr, lambda,
                batch, threshold, goodAbove, badBelow);

        Files.createDirectories(outJson.toAbsolutePath().getParent());
        Files.writeString(outJson, toJson(model));
        System.out.println("fitted on " + model.trainIdx().length + " rows ("
                + model.testIdx().length + " held out as whole base pages), "
                + model.featureNames().size() + " image features");
        System.out.println("screen  good iff pGood >= " + model.threshold());
        System.out.println("model   " + outJson);
    }

    /**
     * Fits the three heads on {@code trainIdx} only, then scores the result on
     * {@code testIdx}.
     *
     * <p>The scaler is fitted on the training rows alone, for the same reason the
     * folds in {@link RealTrainCrossVal} are: fitting it on everything would leak
     * the held-out pages' feature distributions into the model being judged on
     * them.
     *
     * <p>The held-out rows are scored so the artifact can carry numbers that
     * measure <em>this</em> head rather than the per-fold models behind the
     * cross-validated report. It is one grouped split, so it is noisy -- and it is
     * a measurement, where the CV figures are an estimate over folds.
     */
    static ImageModel fit(double[][] raw, String[] docs, int[] y, int[] trainIdx, int[] testIdx,
                          long seed, int epochs, double lr, double lambda, int batch,
                          double threshold, double goodAbove, double badBelow) {
        int nFeatures = IMAGE_FEATURES.length;
        // The artifact records how many rows were held out, and the whole point of
        // this fit is that the scaler and the weights saw the same rows as the
        // 80/20 split they are compared against. An empty side would make that
        // provenance a claim rather than a fact.
        if (trainIdx.length == 0) {
            throw new IllegalArgumentException("no training rows: the split held out everything");
        }
        if (testIdx.length == 0) {
            throw new IllegalArgumentException("no held-out rows: a fit on every row would "
                    + "report training-set behaviour as though it were held out");
        }
        for (int i : trainIdx) {
            if (raw[i].length < nFeatures) {
                throw new IllegalArgumentException("row " + i + " has " + raw[i].length
                        + " features, need at least " + nFeatures + " image features");
            }
        }
        // The held-out rows are scored, so they are held to the same bar. A short
        // row there would be zero-padded by Arrays.copyOf and scored as if the
        // missing features were genuinely zero.
        for (int i : testIdx) {
            if (raw[i].length < nFeatures) {
                throw new IllegalArgumentException("held-out row " + i + " has " + raw[i].length
                        + " features, need at least " + nFeatures + " image features");
            }
        }
        List<String> used = List.of(IMAGE_FEATURES);
        Dataset trainDs = slice(raw, docs, trainIdx, used);
        Dataset.Stats stats = trainDs.fitStats(0.01, 0.99);
        double[][] xTr = Dataset.matrix(trainDs, stats);

        Head[] heads = new Head[N_CLASSES];
        for (int c = 0; c < N_CLASSES; c++) {
            int[] yc = new int[trainIdx.length];
            for (int i = 0; i < trainIdx.length; i++) yc[i] = y[trainIdx[i]] == c ? 1 : 0;
            heads[c] = new Head(RealTrainDump.CLASS_NAMES[c],
                    LogisticRegression.fit(xTr, yc, epochs, lr, lambda, batch, seed + c));
        }
        Metrics metrics = heldOutMetrics(heads, stats, used, raw, docs, y, testIdx, threshold);
        return new ImageModel(used, heads, stats, threshold, trainIdx.clone(), testIdx.clone(),
                seed, epochs, lr, lambda, batch, goodAbove, badBelow, metrics);
    }

    /**
     * Scores the fitted heads on {@code testIdx}, the rows they never saw.
     *
     * <p>{@code fold} is 0 for every row because there are no folds here -- one
     * holdout, not a cross-validation. {@link GoodScreen#countsAt} and {@link
     * GoodScreen#auc} read only {@code truth} and {@code pGood}, so the fold
     * column is inert; {@code pred} is still the argmax so the row stays a
     * faithful record of what the model said.
     */
    private static Metrics heldOutMetrics(Head[] heads, Dataset.Stats stats, List<String> names,
                                          double[][] raw, String[] docs, int[] y, int[] testIdx,
                                          double threshold) {
        Dataset testDs = slice(raw, docs, testIdx, names);
        double[][] xTe = Dataset.matrix(testDs, stats);
        RealTrainCrossVal.ErrorRow[] rows = new RealTrainCrossVal.ErrorRow[testIdx.length];
        for (int i = 0; i < testIdx.length; i++) {
            int row = testIdx[i];
            double[] p = new double[N_CLASSES];
            double best = -1;
            double second = -1;
            int bestC = 0;
            for (int c = 0; c < N_CLASSES; c++) {
                p[c] = heads[c].model().predict(xTe[i]);
                if (p[c] > best) {
                    second = best;
                    best = p[c];
                    bestC = c;
                } else if (p[c] > second) {
                    second = p[c];
                }
            }
            rows[i] = new RealTrainCrossVal.ErrorRow(row, docs[row], 0, "image",
                    y[row], bestC, p[GoodScreen.GOOD], p[1], p[2], best - second);
        }
        return new Metrics(rows.length, GoodScreen.auc(rows),
                GoodScreen.countsAt(rows, threshold), threshold);
    }

    private static Dataset slice(double[][] raw, String[] docs, int[] idx, List<String> names) {
        List<Dataset.Row> rows = new ArrayList<>(idx.length);
        for (int i : idx) {
            rows.add(new Dataset.Row(docs[i], 0, Arrays.copyOf(raw[i], names.size())));
        }
        return Dataset.of(names, rows);
    }

    static String toJson(ImageModel m) {
        Dataset.Stats s = m.stats();
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"label\": \"audited word F1 (Tesseract vs the text the generator printed)\",\n");
        sb.append("  \"classes\": [\"good\", \"notGood\"],\n");
        sb.append("  \"variant\": \"image-only good-vs-rest screen\",\n");
        sb.append("  \"purpose\": \"per-page MRC gate; read with QualityModel.loadScreen, "
                + "never with QualityModel.load\",\n");
        sb.append("  \"polarity\": \"higher pGood is better; loadScreen negates the weights and "
                + "flips the threshold so pipeline code keeps a single polarity "
                + "(predict = 1 - pGood, gate fires at pGood <= threshold)\",\n");
        sb.append("  \"featureNames\": [");
        for (int i = 0; i < m.featureNames().size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append('"').append(m.featureNames().get(i)).append('"');
        }
        sb.append("],\n");
        sb.append("  \"imageFeatureCount\": ").append(m.featureNames().size()).append(",\n");
        sb.append(String.format(Locale.ROOT,
                "  \"screen\": {\"rule\": \"good iff pGood >= threshold\", \"threshold\": %.3f,%n",
                m.threshold()));
        sb.append(String.format(Locale.ROOT,
                "    \"note\": \"threshold chosen on out-of-fold predictions, so the "
                        + "precision/recall quoted with it are mildly optimistic\"},\n"));
        sb.append(String.format(Locale.ROOT,
                "  \"thresholds\": {\"mode\": \"absolute\", \"goodAbove\": %.3f, \"badBelow\": %.3f},%n",
                m.goodAbove(), m.badBelow()));
        sb.append("  \"fit\": {\"rows\": ").append(m.trainIdx().length)
                .append(", \"heldOutRows\": ").append(m.testIdx().length)
                .append(", \"split\": \"grouped 80/20, whole base pages held out\"")
                .append(", \"seed\": ").append(m.seed())
                .append(String.format(Locale.ROOT, ", \"epochs\": %d, \"lr\": %.3f, "
                        + "\"lambda\": %.4f, \"batch\": %d,%n",
                        m.epochs(), m.lr(), m.lambda(), m.batch()));
        sb.append("    \"scalerFittedOn\": \"training rows only\"},\n");
        Metrics mt = m.metrics();
        sb.append("  \"metrics\": {\"source\": \"held-out grouped split, scored by the shipped "
                + "head\", \"rows\": ").append(mt.rows())
                .append(String.format(Locale.ROOT, ", \"threshold\": %.3f, \"auc\": %.6f, "
                                + "\"precision\": %.6f, \"recall\": %.6f, \"f1\": %.6f,%n",
                        mt.threshold(), mt.auc(), mt.precision(), mt.recall(), mt.f1()));
        sb.append("    \"tp\": ").append(mt.counts().tp())
                .append(", \"fp\": ").append(mt.counts().fp())
                .append(", \"fn\": ").append(mt.counts().fn())
                .append(", \"tn\": ").append(mt.counts().tn())
                .append("},\n");
        sb.append("  \"scaler\": {\"winsorizePercentiles\": [0.01, 0.99],\n");
        sb.append("    \"winsorizeLower\": ").append(darr(s.winsorizeLower())).append(",\n");
        sb.append("    \"winsorizeUpper\": ").append(darr(s.winsorizeUpper())).append(",\n");
        sb.append("    \"means\": ").append(darr(s.means())).append(",\n");
        sb.append("    \"stds\": ").append(darr(s.stds())).append("},\n");
        sb.append("  \"heads\": [\n");
        for (int h = 0; h < m.heads().length; h++) {
            Head head = m.heads()[h];
            sb.append("    {\"class\": \"").append(head.cls()).append("\", \"bias\": ")
                    .append(String.format(Locale.ROOT, "%.6f", head.model().bias()))
                    .append(", \"weights\": ").append(darr(head.model().weights())).append('}');
            if (h < m.heads().length - 1) sb.append(',');
            sb.append('\n');
        }
        sb.append("  ],\n");
        sb.append("  \"notes\": [\n");
        sb.append("    \"head.good is the screen; the middle and bad heads are kept only for "
                + "comparison and cannot separate their classes without OCR text\",\n");
        sb.append("    \"these weights were fitted on the training split above; the "
                + "cross-validated metrics in real-train-cv.json come from per-fold models and "
                + "do not measure this head\",\n");
        sb.append("    \"the metrics block above does measure this head, but on one 100-row "
                + "grouped holdout: treat it as noisy rather than as a CV estimate\"\n");
        sb.append("  ]\n");
        sb.append("}\n");
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
}