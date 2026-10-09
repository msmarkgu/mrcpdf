package com.mrcpdf.quality.training;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import com.mrcpdf.quality.model.FeatureExtractor;

import org.junit.jupiter.api.Test;

/**
 * The fitted image-only artifact: what it claims to be, and that it can be used.
 *
 * <p>Two failure modes are worth a test each. The first is a silent
 * incompatibility -- an artifact whose feature list does not match
 * {@link FeatureExtractor#IMAGE_FEATURE_NAMES}, or whose weights are a different
 * length from its own scaler, still serialises happily and fails only when
 * something tries to score a page with it, at which point the feature that is
 * wrong is whatever happens to sit at the mismatched index. The second is the
 * scaler: the sibling {@code real-train-model.json} omits it, so its weights
 * cannot be applied to a raw feature vector at all. These tests pin the feature
 * identity, the weight/scaler agreement, and that the recorded operating point is
 * the one the screen actually uses.
 */
class RealTrainImageModelTest {

    private static final int D = FeatureExtractor.IMAGE_FEATURE_NAMES.length;

    /**
     * 12 base pages x 4 variants, 11 image features. The good pages sit high on
     * the first feature and the bad ones low, so a fitted head has something to
     * find; the rest is noise. Class assignment is by variant so every base page
     * contains all three classes.
     */
    private static final int PAGES = 12;
    private static final int VARIANTS = 4;
    private static final int N = PAGES * VARIANTS;

    private static String[] docs() {
        String[] d = new String[N];
        for (int p = 0; p < PAGES; p++) {
            for (int j = 0; j < VARIANTS; j++) d[p * VARIANTS + j] = "p" + (p + 1);
        }
        return d;
    }

    private static int[] labels() {
        int[] y = new int[N];
        for (int p = 0; p < PAGES; p++) {
            for (int j = 0; j < VARIANTS; j++) {
                int cls = (p + j) % ClassMetrics.N_CLASSES;
                y[p * VARIANTS + j] = cls;
            }
        }
        return y;
    }

    /**
     * Each class gets its own <em>direction</em>, not its own position on a line.
     *
     * <p>"Good" is +3 on feature 0, "bad" is -3, and "middle" is 0 on feature 0
     * but +3 on feature 1. Putting middle at the midpoint of feature 0 instead
     * would make it a band rather than a direction, and no linear head can carve
     * out a band -- that head would sit at AUC 0.5 no matter how it was trained.
     * (The real corpus has exactly this property, which is why the middle F1 is
     * only 0.665 there; here the classes are made separable so that a head fitted
     * on the wrong target is visibly wrong.)
     */
    private static double[][] features() {
        double[][] raw = new double[N][D];
        for (int p = 0; p < PAGES; p++) {
            for (int j = 0; j < VARIANTS; j++) {
                int i = p * VARIANTS + j;
                int cls = (p + j) % ClassMetrics.N_CLASSES;
                double[] signal = new double[D];
                signal[0] = switch (cls) {
                    case 0 -> 3.0;
                    case 1 -> 0.0;
                    default -> -3.0;
                };
                signal[1] = cls == 1 ? 3.0 : 0.0;
                for (int c = 0; c < D; c++) {
                    raw[i][c] = signal[c] + Math.sin(0.9 * i + 1.7 * c) * 0.3 + 0.05 * c;
                }
            }
        }
        return raw;
    }

    private static RealTrainImageModel.ImageModel fitted() {
        int[][] split = RealTrainEval.splitGrouped(docs(), 0.20, 42);
        return RealTrainImageModel.fit(features(), docs(), labels(), split[0], split[1],
                42, 300, 0.5, 0.01, 16, RealTrainImageModel.SCREEN_THRESHOLD, 0.90, 0.60);
    }

    /**
     * The artifact's feature list is the contract with
     * {@link FeatureExtractor}. A renamed or reordered feature would still load
     * and still produce numbers -- the wrong ones, silently.
     */
    @Test
    void artifactFeaturesAreTheImageFeaturesInOrder() {
        RealTrainImageModel.ImageModel m = fitted();
        assertEquals(D, m.featureNames().size());
        assertEquals(List.of(FeatureExtractor.IMAGE_FEATURE_NAMES), m.featureNames());
        for (int c = 0; c < D; c++) {
            assertEquals(D, m.heads()[0].model().weights().length,
                    "the good head's weight " + c + " does not match the feature count");
        }
    }

    /** One scaler per feature, or the z-score step silently no-ops on feature 0. */
    @Test
    void scalerCoversEveryFeature() {
        Dataset.Stats s = fitted().stats();
        for (String field : new String[]{"lower", "upper", "means", "stds"}) {
            double[] v = switch (field) {
                case "lower" -> s.winsorizeLower();
                case "upper" -> s.winsorizeUpper();
                case "means" -> s.means();
                default -> s.stds();
            };
            assertEquals(D, v.length, field + " must have one entry per image feature");
        }
    }

    /**
     * Each head must be a classifier of <em>its own</em> class, measured by how well
     * its score orders that class above the rest.
     *
     * <p>Comparing the weight vectors would not do: the heads are fitted with
     * different seeds, so two heads fitted on identical targets still differ
     * numerically and a "they are not the same array" assertion passes against a
     * model that learned one class three times. Ranking quality cannot be faked
     * that way -- a head fitted on the wrong target is confidently inverted.
     */
    @Test
    void eachHeadClassifiesItsOwnClass() {
        RealTrainImageModel.ImageModel m = fitted();
        int[][] split = RealTrainEval.splitGrouped(docs(), 0.20, 42);
        for (int c = 0; c < ClassMetrics.N_CLASSES; c++) {
            int[] isC = new int[N];
            double[] scores = new double[N];
            for (int i = 0; i < N; i++) {
                isC[i] = labels()[i] == c ? 1 : 0;
                scores[i] = m.heads()[c].model().predict(m.stats().transform(features()[i]));
            }
            double auc = LogisticRegression.auc(isC, scores);
            assertTrue(auc > 0.9,
                    "head " + c + " (" + RealTrainDump.CLASS_NAMES[c] + ") has AUC " + auc
                            + " against its own class, so it is not fitted to that class");
        }
        assertTrue(split[0].length > 0);
    }

    /**
     * The scaler and the weights have to be usable together: feeding the recorded
     * stats a raw vector and scoring it must give the same answer the model's own
     * screen() gives, on every row. That equivalence is the whole reason the
     * scaler is in the artifact, and it is invisible in a file listing.
     */
    @Test
    void screenUsesTheRecordedScalerAndThreshold() {
        RealTrainImageModel.ImageModel m = fitted();
        int best = -1;
        int worst = -1;
        double bestP = -1;
        double worstP = Double.POSITIVE_INFINITY;
        for (int i = 0; i < N; i++) {
            double p = m.heads()[0].model().predict(m.stats().transform(features()[i]));
            assertEquals(p >= m.threshold(), m.screen(features()[i]),
                    "row " + i + ": screen() disagrees with pGood >= threshold");
            if (p > bestP) {
                bestP = p;
                best = i;
            }
            if (p < worstP) {
                worstP = p;
                worst = i;
            }
        }
        assertTrue(m.screen(features()[best]),
                "the highest-scoring row (pGood=" + bestP + ") must clear the screen");
        assertFalse(m.screen(features()[worst]),
                "the lowest-scoring row (pGood=" + worstP + ") must not clear the screen");
        assertEquals(0, labels()[best], "the highest pGood row should be a good page");
        assertNotEquals(0, labels()[worst], "the lowest pGood row should not be a good page");
    }

    @Test
    void thresholdIsTheShippedOperatingPoint() {
        assertEquals(0.70, RealTrainImageModel.SCREEN_THRESHOLD, 1e-12);
        assertEquals(RealTrainImageModel.SCREEN_THRESHOLD, fitted().threshold(), 1e-12);
    }

    /**
     * The threshold is an argument, not a constant baked into the fitter, so an
     * artifact can record the operating point actually used. If it were baked in,
     * the JSON would say 0.70 whatever threshold was asked for.
     */
    @Test
    void fittedThresholdIsCarriedThrough() {
        int[][] split = RealTrainEval.splitGrouped(docs(), 0.20, 42);
        RealTrainImageModel.ImageModel m = RealTrainImageModel.fit(features(), docs(), labels(),
                split[0], split[1], 42, 300, 0.5, 0.01, 16, 0.42, 0.90, 0.60);
        assertEquals(0.42, m.threshold(), 1e-12);
        assertTrue(RealTrainImageModel.toJson(m).contains("\"threshold\": 0.420"),
                "the artifact must record the threshold it was fitted at");
    }

    /**
     * The held-out rows must not reach the fit. If they did, the artifact would
     * look better than it is on exactly the pages it will be judged on, and
     * nothing in the JSON would say so.
     */
    @Test
    void scalerIsFittedOnTrainingRowsOnly() {
        double[][] raw = features();
        int[][] split = RealTrainEval.splitGrouped(docs(), 0.20, 42);
        assertTrue(split[1].length > 0, "the split must actually hold rows out");

        // Corrupt only the held-out rows. A training-only fit must be unmoved by it.
        double[][] corrupted = new double[N][];
        for (int i = 0; i < N; i++) {
            corrupted[i] = raw[i].clone();
            if (inFold(split[1], i)) {
                for (int c = 0; c < D; c++) corrupted[i][c] += 1000.0 + c;
            }
        }
        RealTrainImageModel.ImageModel a = RealTrainImageModel.fit(raw, docs(), labels(),
                split[0], split[1], 42, 300, 0.5, 0.01, 16, 0.70, 0.90, 0.60);
        RealTrainImageModel.ImageModel b = RealTrainImageModel.fit(corrupted, docs(), labels(),
                split[0], split[1], 42, 300, 0.5, 0.01, 16, 0.70, 0.90, 0.60);
        for (int c = 0; c < D; c++) {
            assertEquals(a.stats().means()[c], b.stats().means()[c], 1e-9,
                    "held-out rows leaked into the scaler at feature " + c);
        }
        for (int h = 0; h < ClassMetrics.N_CLASSES; h++) {
            double[] wa = a.heads()[h].model().weights();
            double[] wb = b.heads()[h].model().weights();
            for (int c = 0; c < D; c++) {
                assertEquals(wa[c], wb[c], 1e-9, "held-out rows leaked into head " + h);
            }
        }
    }

    private static boolean inFold(int[] fold, int row) {
        for (int i : fold) if (i == row) return true;
        return false;
    }

    @Test
    void splitIsRecordedSoTheProvenanceIsVisible() {
        RealTrainImageModel.ImageModel m = fitted();
        assertTrue(m.trainIdx().length > 0);
        assertEquals(N, m.trainIdx().length + m.testIdx().length,
                "the two sides must account for every row");
        List<Integer> seen = new ArrayList<>();
        for (int i : m.trainIdx()) seen.add(i);
        for (int i : m.testIdx()) seen.add(i);
        assertEquals(N, seen.size());
        assertEquals(N, seen.stream().distinct().count(), "a row appears on both sides");
    }

    /**
     * The artifact must be explicit about polarity. QualityModel treats a higher
     * score as degraded; this screen treats a higher score as better. Loading one
     * as the other inverts every decision it makes, so the JSON says so in words
     * and names the loader that resolves it, rather than leaving it to be inferred
     * from the sign of a weight.
     */
    @Test
    void artifactWarnsThatItIsNotALoadableGate() {
        String json = RealTrainImageModel.toJson(fitted());
        assertTrue(json.contains("higher pGood is better"), "polarity must be stated");
        assertTrue(json.contains("loadScreen"),
                "the loader that resolves the polarity must be named");
        assertTrue(json.contains("never with QualityModel.load"),
                "the loader that would invert every decision must be named too");
        assertTrue(json.contains("\"featureNames\": [\"blurSigmaPx\""),
                "the feature list must be the image features, starting with the first");
    }

    /**
     * The metrics block must describe the shipped head on rows it did not train
     * on, and its numbers must follow from its own confusion counts. A block whose
     * precision does not equal tp/(tp+fp) is a claim, not a measurement, and would
     * be quoted as if it were one.
     */
    @Test
    void metricsDescribeTheHeldOutRowsOfThisHead() {
        RealTrainImageModel.ImageModel m = fitted();
        RealTrainImageModel.Metrics mt = m.metrics();
        assertEquals(m.testIdx().length, mt.rows(),
                "metrics must cover exactly the held-out side of the split");
        assertEquals(0.70, mt.threshold(), 1e-12,
                "metrics must be measured at the operating point they are quoted for");
        assertTrue(mt.auc() >= 0.0 && mt.auc() <= 1.0, "AUC must be a probability");
        int tp = mt.counts().tp();
        int fp = mt.counts().fp();
        int fn = mt.counts().fn();
        int tn = mt.counts().tn();
        assertEquals(mt.rows(), tp + fp + fn + tn, "confusion counts must add up");
        assertTrue(tp + fn > 0, "the held-out side must contain a good page");
        assertTrue(fn + tn > 0, "the held-out side must contain a not-good page");
        assertEquals(tp + fp == 0 ? 0.0 : tp / (double) (tp + fp), mt.precision(), 1e-12,
                "precision must follow from the counts");
        assertEquals(tp + fn == 0 ? 0.0 : tp / (double) (tp + fn), mt.recall(), 1e-12,
                "recall must follow from the counts");
    }

    /**
     * The held-out rows must actually be scored and the block must reach the file.
     * A metrics section pinned to zero rows, or one that never gets serialised, is
     * a placeholder -- and a placeholder reads the same as a measurement in a file
     * listing.
     */
    @Test
    void metricsReachTheArtifact() {
        RealTrainImageModel.ImageModel m = fitted();
        assertTrue(m.metrics().rows() > 0, "nothing was scored");
        String json = RealTrainImageModel.toJson(m);
        assertTrue(json.contains("\"metrics\": {\"source\": \"held-out grouped split"),
                "the artifact must carry a metrics block");
        assertTrue(json.contains("\"rows\": " + m.testIdx().length),
                "the artifact must record how many rows were scored");
        assertTrue(json.contains("\"auc\": "), "the artifact must record the held-out AUC");
    }

    @Test
    void artifactRecordsItsOwnReproducibilityInputs() {
        String json = RealTrainImageModel.toJson(fitted());
        for (String field : new String[]{"\"seed\": 42", "\"epochs\": 300", "\"lr\": 0.500",
                "\"batch\": 16", "\"scalerFittedOn\": \"training rows only\""}) {
            assertTrue(json.contains(field), "artifact is missing " + field);
        }
    }

    /**
     * The scaler must be present <em>with one number per feature</em>. Checking
     * only that the key appears would pass against {@code "stds": []}, which is
     * exactly the shape of an artifact that cannot be applied to anything.
     */
    @Test
    void artifactCarriesTheScalerTheSiblingOmits() {
        String json = RealTrainImageModel.toJson(fitted());
        for (String field : new String[]{"winsorizeLower", "winsorizeUpper", "means", "stds"}) {
            assertEquals(D, numericArray(json, field).length,
                    field + " must hold one number per image feature");
        }
        // The screen is the good head's probability; without stds that probability
        // is not computable from a raw feature vector.
        double[] stds = numericArray(json, "stds");
        for (int c = 0; c < D; c++) {
            assertTrue(stds[c] > 0, "std " + c + " must be positive");
        }
    }

    /**
     * The value of a top-level numeric array in the artifact, one entry per
     * feature. Throws if the field is missing or is not an array of numbers, so a
     * caller cannot mistake "field gone" for "field empty".
     */
    private static double[] numericArray(String json, String field) {
        int at = json.indexOf('"' + field + '"');
        assertTrue(at >= 0, "artifact has no " + field);
        int open = json.indexOf('[', at);
        int close = json.indexOf(']', open);
        assertTrue(open >= 0 && close > open, field + " is not a JSON array");
        String body = json.substring(open + 1, close).trim();
        if (body.isEmpty()) return new double[0];
        String[] parts = body.split(",");
        double[] out = new double[parts.length];
        for (int i = 0; i < parts.length; i++) {
            out[i] = Double.parseDouble(parts[i].trim());
        }
        return out;
    }

    @Test
    void jsonIsWellFormedAtTheTopLevel() {
        String json = RealTrainImageModel.toJson(fitted());
        assertTrue(json.startsWith("{\n"), "must be a JSON object");
        assertTrue(json.trim().endsWith("}"), "must be closed");
        // Balanced braces catch a dropped comma or an unclosed array in the
        // hand-built serializer, which is the only thing guarding this file's shape.
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (char ch : json.toCharArray()) {
            if (escaped) {
                escaped = false;
            } else if (ch == '\\' && inString) {
                escaped = true;
            } else if (ch == '"') {
                inString = !inString;
            } else if (!inString) {
                if (ch == '{' || ch == '[') depth++;
                if (ch == '}' || ch == ']') depth--;
                assertTrue(depth >= 0, "unbalanced closing bracket");
            }
        }
        assertEquals(0, depth, "unbalanced braces or brackets");
    }

    @Test
    void aSplitWithNoHeldOutRowsIsRejectedRatherThanSilentlyAccepting() {
        int[][] split = new int[][]{{0, 1, 2, 3}, new int[0]};
        double[][] raw = new double[4][D];
        String[] docs = {"a", "a", "b", "b"};
        int[] y = {0, 1, 2, 0};
        assertThrows(IllegalArgumentException.class, () -> RealTrainImageModel.fit(raw, docs, y,
                        split[0], split[1], 42, 10, 0.5, 0.01, 4, 0.7, 0.9, 0.6),
                "a fit with nothing held out would report training-set metrics as if they were held out");
    }
}