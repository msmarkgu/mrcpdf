package com.mrcpdf.quality.model;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The committed quality-gate logistic model: feature names, winsorize bounds,
 * z-score statistics, weights, bias, and a decision threshold chosen at
 * degraded-recall ~0.9 on a held-out validation split.
 *
 * Serializable to/from the {@code quality-model.json} artifact via {@link
 * QualityJson} (zero runtime dependencies).  Loaded by the pipeline scorer to
 * replace the Phase-1 geometric gate when available; the scorer falls back to
 * the geometric threshold when {@code loadDefault()} returns null.
 */
public final class QualityModel {

    /** Artifact schema version. */
    public static final int ARTIFACT_VERSION = 2;

    public static final String VARIANT_IMAGE_ONLY = "image-only";
    public static final String VARIANT_TEXT_LAYER = "text-layer";

    private final String variant;
    private final List<String> featureNames;
    private final double[] winsorizeLower;
    private final double[] winsorizeUpper;
    private final double[] means;
    private final double[] stds;
    private final double[] weights;
    private final double bias;
    private final double threshold;
    private final double auc;
    private final double recall;
    private final double precision;

    public QualityModel(String variant, List<String> featureNames,
                        double[] winsorizeLower, double[] winsorizeUpper,
                        double[] means, double[] stds,
                        double[] weights, double bias,
                        double threshold, double auc, double recall, double precision) {
        this.variant = variant;
        this.featureNames = List.copyOf(featureNames);
        this.winsorizeLower = winsorizeLower.clone();
        this.winsorizeUpper = winsorizeUpper.clone();
        this.means = means.clone();
        this.stds = stds.clone();
        this.weights = weights.clone();
        this.bias = bias;
        this.threshold = threshold;
        this.auc = auc;
        this.recall = recall;
        this.precision = precision;
    }

    public String variant() { return variant; }
    public List<String> featureNames() { return featureNames; }
    public double[] winsorizeLower() { return winsorizeLower.clone(); }
    public double[] winsorizeUpper() { return winsorizeUpper.clone(); }
    public double[] means() { return means.clone(); }
    public double[] stds() { return stds.clone(); }
    public double[] weights() { return weights.clone(); }
    public double bias() { return bias; }
    public double threshold() { return threshold; }
    public double auc() { return auc; }
    public double recall() { return recall; }
    public double precision() { return precision; }

    public double featureWeight(String name) {
        int i = featureNames.indexOf(name);
        return i >= 0 ? weights[i] : 0;
    }

    public boolean isImageOnly() {
        return VARIANT_IMAGE_ONLY.equals(variant);
    }

    // ──────────────────────────────────────────────────────────────────────
    // Inference
    // ──────────────────────────────────────────────────────────────────────

    /** Predicted degraded probability for a raw feature vector. */
    public double predict(double[] features) {
        if (features.length != featureNames.size()) {
            throw new IllegalArgumentException("Expected " + featureNames.size()
                    + " features, got " + features.length);
        }
        double[] t = stds.length == 0 ? features.clone() : transform(features);
        double z = bias;
        for (int i = 0; i < weights.length; i++) z += weights[i] * t[i];
        return 1.0 / (1.0 + Math.exp(-Math.max(-745.0, Math.min(745.0, z))));
    }

    private double[] transform(double[] raw) {
        double[] out = new double[raw.length];
        for (int i = 0; i < raw.length; i++) {
            double v = Math.max(winsorizeLower[i], Math.min(winsorizeUpper[i], raw[i]));
            out[i] = (v - means[i]) / (stds[i] > 1e-9 ? stds[i] : 1.0);
        }
        return out;
    }

    /** Gate: true ⇒ degraded ⇒ MRC should be skipped (fidelity path). */
    public boolean gate(double[] features) {
        return predict(features) >= threshold;
    }

    // ──────────────────────────────────────────────────────────────────────
    // IO
    // ──────────────────────────────────────────────────────────────────────

    public static QualityModel load(InputStream in) {
        String json;
        try {
            json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalArgumentException("Failed to read model artifact", e);
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> root = (Map<String, Object>) QualityJson.parse(json);
        Object version = root.get("version");
        if (!(version instanceof Number n) || n.intValue() != ARTIFACT_VERSION) {
            throw new IllegalArgumentException("Unsupported model artifact version: " + version);
        }
        String variant = (String) root.getOrDefault("variant", VARIANT_IMAGE_ONLY);
        Object fNames = root.get("featureNames");
        if (!(fNames instanceof List<?> list)) {
            throw new IllegalArgumentException("Missing 'featureNames' array");
        }
        List<String> featureNames = new ArrayList<>();
        for (Object o : list) {
            if (!(o instanceof String s)) {
                throw new IllegalArgumentException("featureNames must be strings");
            }
            featureNames.add(s);
        }
        int d = featureNames.size();
        double[] lo = doubles(root, "winsorizeLower", d);
        double[] hi = doubles(root, "winsorizeUpper", d);
        double[] means = doubles(root, "means", d);
        double[] stds = doubles(root, "stds", d);
        double[] weights = doubles(root, "weights", d);
        double bias = number(root, "bias");
        double threshold = number(root, "threshold");
        double auc = number(root, "auc");
        double recall = number(root, "recall");
        double precision = number(root, "precision");
        return new QualityModel(variant, featureNames, lo, hi, means, stds,
                weights, bias, threshold, auc, recall, precision);
    }

    /** Loads the bundled artifact from the classpath, or null when absent. */
    public static QualityModel loadDefault() {
        try (InputStream in = QualityModel.class.getResourceAsStream("/quality-model.json")) {
            if (in == null) return null;
            return load(in);
        } catch (IOException e) {
            return null;
        }
    }

    /** Serializes this model to the artifact JSON format. */
    public String toJson() {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("version", (double) ARTIFACT_VERSION);
        fields.put("variant", variant);
        fields.put("featureNames", strArray(featureNames));
        fields.put("winsorizeLower", dblArray(winsorizeLower));
        fields.put("winsorizeUpper", dblArray(winsorizeUpper));
        fields.put("means", dblArray(means));
        fields.put("stds", dblArray(stds));
        fields.put("weights", dblArray(weights));
        fields.put("bias", bias);
        fields.put("threshold", threshold);
        fields.put("auc", auc);
        fields.put("recall", recall);
        fields.put("precision", precision);
        return QualityJson.artifact(fields);
    }

    private static List<String> strArray(List<String> list) {
        return list;
    }

    private static List<Double> dblArray(double[] arr) {
        List<Double> out = new ArrayList<>(arr.length);
        for (double v : arr) out.add(v);
        return out;
    }

    private static double[] doubles(Map<String, Object> root, String field, int d) {
        List<Double> list = QualityJson.asDoubles(root.get(field), field);
        if (list.size() != d) {
            throw new IllegalArgumentException("Field '" + field + "' has " + list.size()
                    + " entries, expected " + d);
        }
        double[] out = new double[d];
        for (int i = 0; i < d; i++) out[i] = list.get(i);
        return out;
    }

    private static double number(Map<String, Object> root, String field) {
        Object o = root.get(field);
        if (!(o instanceof Number n)) {
            throw new IllegalArgumentException("Missing numeric field '" + field + "'");
        }
        return n.doubleValue();
    }
}