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
 * Serializable to/from the bundled artifacts via {@link QualityJson} (zero
 * runtime dependencies). The pipeline gates on {@link #loadScreenDefault};
 * when that returns null the scorer falls back to the Phase-1 geometric
 * threshold.
 *
 * <p>Two artifacts load into this class. {@link #load} reads
 * {@code quality-model.json} verbatim (legacy; still pinned by tests but no
 * longer used by the pipeline). {@link #loadScreen} reads
 * {@code quality-image-screen.json}, the good-vs-rest screen the pipeline gates
 * on, and resolves its opposite polarity -- see the method for why that cannot
 * be left to the caller.
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

    /**
     * Predicted skip-side probability for a raw feature vector: the probability
     * that the gate's "yes, skip MRC" side holds. Which side that is depends on
     * the artifact -- see {@link #load} and {@link #loadScreen}.
     */
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

    /**
     * Gate: true ⇒ degraded ⇒ MRC should be skipped (fidelity path).
     *
     * <p>Which probability {@link #predict} is returning is decided when the
     * artifact is loaded, not here: {@link #load} leaves it as the file's own
     * polarity (higher = more degraded), while {@link #loadScreen} negates the
     * weights of a {@code pGood} screen so that "higher = worse" holds for both.
     */
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

    /**
     * Loads the legacy {@code quality-model.json} artifact from the classpath,
     * or null when absent. Superseded by {@link #loadScreenDefault}: the
     * pipeline no longer calls this, only its own tests do.
     */
    public static QualityModel loadDefault() {
        try (InputStream in = QualityModel.class.getResourceAsStream("/quality-model.json")) {
            if (in == null) return null;
            return load(in);
        } catch (IOException e) {
            return null;
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // The good-vs-rest screen
    // ──────────────────────────────────────────────────────────────────────

    /** Classpath location of the bundled screen artifact. */
    static final String SCREEN_RESOURCE = "/quality-image-screen.json";

    /**
     * Loads the bundled good-vs-rest screen, or null when absent or malformed.
     *
     * <p>{@code quality-image-screen.json} is written by
     * {@code ./gradlew trainQualityScreen}. It is <em>not</em> a {@link #load}
     * artifact: the scaler sits under {@code scaler}, the weights under
     * {@code heads[class=="good"]}, there is no {@code version}, and -- the one
     * that matters -- it stores {@code pGood}, where higher means <em>better</em>.
     * {@link #load} on this file either throws or, worse, would read a good page
     * as a degraded one.
     *
     * <p>So the polarity is resolved here, once: the weights and bias are negated
     * and the threshold flipped to {@code 1 - pGoodThreshold}, making
     * {@code predict(x) == 1 - pGood(x)} and leaving {@link #gate} with its usual
     * meaning of "true ⇒ skip MRC". Every other caller sees one polarity. The
     * identity is pinned by {@code QualityModelTest}; the alternative -- a
     * direction flag threaded through the scorer -- would leave two opposite
     * meanings behind the same method name.
     */
    public static QualityModel loadScreen(InputStream in) {
        String json;
        try {
            json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalArgumentException("Failed to read screen artifact", e);
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> root = (Map<String, Object>) QualityJson.parse(json);

        List<String> featureNames = strings(root.get("featureNames"), "featureNames");
        int d = featureNames.size();

        Map<String, Object> scaler = map(root.get("scaler"), "scaler");
        double[] lo = doubles(scaler, "winsorizeLower", d);
        double[] hi = doubles(scaler, "winsorizeUpper", d);
        double[] means = doubles(scaler, "means", d);
        double[] stds = doubles(scaler, "stds", d);

        Map<String, Object> head = goodHead(root.get("heads"));
        double[] rawWeights = doubles(head, "weights", d);
        double rawBias = number(head, "bias");

        Map<String, Object> screen = map(root.get("screen"), "screen");
        double pGoodThreshold = number(screen, "threshold");
        if (!(pGoodThreshold > 0.0 && pGoodThreshold < 1.0)) {
            throw new IllegalArgumentException("screen.threshold must be in (0, 1), got "
                    + pGoodThreshold);
        }

        // Optional: the metrics block is reporting, not structure. Absent, the
        // model still scores identically and the banner just prints zeros.
        Map<String, Object> metrics = optionalMap(root.get("metrics"));
        double auc = optNumber(metrics, "auc");
        double recall = optNumber(metrics, "recall");
        double precision = optNumber(metrics, "precision");

        double[] weights = new double[d];
        for (int i = 0; i < d; i++) weights[i] = -rawWeights[i];
        return new QualityModel(VARIANT_IMAGE_ONLY, featureNames, lo, hi, means, stds,
                weights, -rawBias, 1.0 - pGoodThreshold, auc, recall, precision);
    }

    /** The screen artifact from the classpath, or null when absent or malformed. */
    public static QualityModel loadScreenDefault() {
        try (InputStream in = QualityModel.class.getResourceAsStream(SCREEN_RESOURCE)) {
            if (in == null) return null;
            return loadScreen(in);
        } catch (IOException | IllegalArgumentException e) {
            return null;
        }
    }

    /** The single {@code heads} entry standing for the good class. */
    private static Map<String, Object> goodHead(Object value) {
        if (!(value instanceof List<?> list)) {
            throw new IllegalArgumentException("Missing 'heads' array");
        }
        Map<String, Object> found = null;
        for (Object o : list) {
            Map<String, Object> head = map(o, "heads[]");
            if ("good".equals(head.get("class"))) {
                if (found != null) {
                    throw new IllegalArgumentException("Screen artifact has more than one "
                            + "head for class 'good'");
                }
                found = head;
            }
        }
        if (found == null) {
            throw new IllegalArgumentException("Screen artifact has no head for class 'good'");
        }
        return found;
    }

    private static Map<String, Object> map(Object value, String field) {
        if (!(value instanceof Map<?, ?> m)) {
            throw new IllegalArgumentException("Missing '" + field + "' object");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> cast = (Map<String, Object>) m;
        return cast;
    }

    private static Map<String, Object> optionalMap(Object value) {
        if (value == null) return Map.of();
        return map(value, "metrics");
    }

    private static List<String> strings(Object value, String field) {
        if (!(value instanceof List<?> list)) {
            throw new IllegalArgumentException("Missing '" + field + "' array");
        }
        List<String> out = new ArrayList<>(list.size());
        for (Object o : list) {
            if (!(o instanceof String s)) {
                throw new IllegalArgumentException(field + " must be strings");
            }
            out.add(s);
        }
        if (out.isEmpty()) {
            throw new IllegalArgumentException("'" + field + "' must not be empty");
        }
        return out;
    }

    private static double optNumber(Map<String, Object> root, String field) {
        Object o = root.get(field);
        return o instanceof Number n ? n.doubleValue() : 0.0;
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