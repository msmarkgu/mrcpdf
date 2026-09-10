package com.mrcpdf.tools;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import com.mrcpdf.quality.model.FeatureExtractor;
import com.mrcpdf.quality.model.QualityModel;
import com.mrcpdf.quality.training.Dataset;
import com.mrcpdf.quality.training.LogisticRegression;
import com.mrcpdf.quality.training.SyntheticCorpus;

/**
 * Trains the Phase-2 quality-gate logistic model and exports the artifact JSON.
 *
 * Can bootstrap from a generated synthetic corpus ({@code --generate}) or a
 * user-annotated CSV ({@code --corpus}).  Either way it performs a
 * document-aware train/validation split, winsorizes (1st/99th) and z-scores on
 * the training rows only, fits L2 logistic regression by SGD, and picks the
 * decision threshold at ~0.9 degraded recall on the validation rows.
 */
@Command(
    name = "train-quality-model",
    description = "Train and export the Phase-2 quality-gate logistic model",
    mixinStandardHelpOptions = true
)
public class TrainModel implements Callable<Integer> {

    @Option(names = "--generate", description = "Generate a synthetic corpus at this path first, then train on it")
    private String generate;

    @Option(names = "--corpus", description = "Existing labeled feature CSV to train on (ignore --generate)")
    private String corpus;

    @Option(names = "--out", description = "Artifact output path (default: src/main/resources/quality-model.json)")
    private String out = "src/main/resources/quality-model.json";

    @Option(names = "--variant", description = "Feature variant: image-only or text-layer (default: image-only)")
    private String variant;

    @Option(names = "--docs", description = "Synthetic corpus documents (when --generate is used; default: 30)")
    private int docs = 30;

    @Option(names = "--seed", description = "Determinism seed (default: 42)")
    private long seed = 42;

    @Option(names = "--epochs", description = "SGD epochs (default: 300)")
    private int epochs = 300;

    @Option(names = "--lr", description = "SGD learning rate (default: 0.05)")
    private double lr = 0.05;

    @Option(names = "--lambda", description = "L2 regularization (default: 0.001)")
    private double lambdaReg = 0.001;

    @Option(names = "--batch", description = "SGD minibatch size (default: 64)")
    private int batch = 64;

    @Option(names = "--val-fraction", description = "Document-aware validation fraction (default: 0.2)")
    private double valFraction = 0.2;

    @Option(names = "--recall", description = "Target degraded recall for the threshold (default: 0.9)")
    private double recallTarget = 0.9;

    @Override
    public Integer call() {
        try {
            String resolvedVariant = variant == null ? QualityModel.VARIANT_IMAGE_ONLY : variant;
            if (!QualityModel.VARIANT_IMAGE_ONLY.equals(resolvedVariant)
                    && !QualityModel.VARIANT_TEXT_LAYER.equals(resolvedVariant)) {
                System.err.println("Unknown variant: " + resolvedVariant);
                return 2;
            }
            List<String> featureNames = new ArrayList<>(List.of(FeatureExtractor.IMAGE_FEATURE_NAMES));
            if (QualityModel.VARIANT_TEXT_LAYER.equals(resolvedVariant)) {
                featureNames.addAll(List.of(FeatureExtractor.TEXT_FEATURE_NAMES));
            }

            Path corpusPath;
            if (generate != null) {
                corpusPath = Path.of(generate);
                System.out.println("Generating synthetic corpus: " + corpusPath
                        + " (" + docs + " documents)");
                SyntheticCorpus.generate(corpusPath, resolvedVariant, docs, seed);
            } else if (corpus != null) {
                corpusPath = Path.of(corpus);
            } else {
                System.err.println("Either --generate or --corpus is required");
                return 2;
            }

            Dataset data = Dataset.fromCsv(corpusPath, featureNames);
            System.out.printf("Loaded %d rows (%d features)%n", data.size(), data.featureCount());

            Dataset.Split split = data.splitByDoc(valFraction, seed);
            System.out.printf("Split: %d train / %d validation rows (doc-aware)%n",
                    split.train().size(), split.validation().size());

            Dataset.Stats stats = split.train().fitStats(0.01, 0.99);
            double[][] xTrain = Dataset.matrix(split.train(), stats);
            int[] yTrain = labels(split.train());
            double[][] xVal = Dataset.matrix(split.validation(), stats);
            int[] yVal = labels(split.validation());

            LogisticRegression model = LogisticRegression.fit(
                    xTrain, yTrain, epochs, lr, lambdaReg, batch, seed);

            double[] valScores = predictAll(model, xVal);
            double auc = LogisticRegression.auc(yVal, valScores);
            double threshold = LogisticRegression.thresholdForRecall(yVal, valScores, recallTarget);
            double[] pr = precisionRecall(yVal, valScores, threshold);

            System.out.println("Validation metrics:");
            System.out.printf("  AUC:            %.3f%n", auc);
            System.out.printf("  threshold:      %.3f (target degraded-recall %.2f)%n", threshold, recallTarget);
            System.out.printf("  recall@thr:     %.2f%n", pr[0]);
            System.out.printf("  precision@thr:  %.2f%n", pr[1]);
            System.out.println("  Feature weights (standardized):");
            String tbl = IntStream.range(0, featureNames.size())
                    .mapToObj(i -> String.format("    %-18s %+.3f   (mean %8.3f, std %8.3f)",
                            featureNames.get(i), model.weights()[i], stats.means()[i], stats.stds()[i]))
                    .collect(Collectors.joining("\n"));
            System.out.println(tbl);

            QualityModel qualityModel = new QualityModel(resolvedVariant, featureNames,
                    stats.winsorizeLower(), stats.winsorizeUpper(),
                    stats.means(), stats.stds(),
                    model.weights(), model.bias(), threshold, auc, pr[0], pr[1]);
            Files.writeString(Path.of(out), qualityModel.toJson());
            System.out.println("Artifact written: " + out);
            return 0;
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
            return 1;
        }
    }

    private static int[] labels(Dataset d) {
        int[] out = new int[d.size()];
        for (int i = 0; i < out.length; i++) out[i] = d.rows().get(i).label();
        return out;
    }

    private static double[] predictAll(LogisticRegression lr, double[][] x) {
        double[] out = new double[x.length];
        for (int i = 0; i < x.length; i++) out[i] = lr.predict(x[i]);
        return out;
    }

    /** Returns {recall, precision} of positives ({@code y==1}) at threshold. */
    private static double[] precisionRecall(int[] y, double[] scores, double threshold) {
        int tp = 0, fp = 0, pos = 0;
        for (int i = 0; i < y.length; i++) {
            if (y[i] == 1) {
                pos++;
                if (scores[i] >= threshold) tp++;
            } else if (scores[i] >= threshold) {
                fp++;
            }
        }
        double recall = pos > 0 ? (double) tp / pos : 0;
        double precision = tp + fp > 0 ? (double) tp / (tp + fp) : 0;
        return new double[]{recall, precision};
    }

    public static void main(String[] args) {
        int exitCode = new CommandLine(new TrainModel()).execute(args);
        System.exit(exitCode);
    }
}