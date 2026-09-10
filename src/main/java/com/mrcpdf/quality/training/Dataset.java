package com.mrcpdf.quality.training;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.stream.Collectors;

/**
 * Labeled feature corpus loader/writer for quality-model training.
 *
 * CSV layout (header first): an optional {@code doc} column (grouping key for
 * leakage-safe splits), then {@code label} (0 = clean, 1 = degraded), then the
 * feature columns in the order recorded in {@link
 * com.mrcpdf.quality.model.FeatureExtractor}.  When {@code doc} is absent,
 * each row is its own document.
 */
public final class Dataset {

    /** One labeled feature row. */
    public record Row(String doc, int label, double[] features) {
    }

    /** Train/validation split. */
    public record Split(Dataset train, Dataset validation) {
    }

    /** Winsorization bounds + z-score statistics, fit on the training rows only. */
    public record Stats(double[] winsorizeLower, double[] winsorizeUpper,
                        double[] means, double[] stds) {
        public double[] transform(double[] raw) {
            double[] out = new double[raw.length];
            for (int i = 0; i < raw.length; i++) {
                double v = Math.max(winsorizeLower[i], Math.min(winsorizeUpper[i], raw[i]));
                out[i] = (v - means[i]) / (stds[i] > 1e-9 ? stds[i] : 1.0);
            }
            return out;
        }
    }

    private final List<String> featureNames;
    private final List<Row> rows;

    private Dataset(List<String> featureNames, List<Row> rows) {
        this.featureNames = List.copyOf(featureNames);
        this.rows = List.copyOf(rows);
    }

    public List<String> featureNames() {
        return featureNames;
    }

    public List<Row> rows() {
        return rows;
    }

    public int featureCount() {
        return featureNames.size();
    }

    public int size() {
        return rows.size();
    }

    // ──────────────────────────────────────────────────────────────────────
    // IO
    // ──────────────────────────────────────────────────────────────────────

    public static Dataset fromCsv(Path path, List<String> expectedFeatures) throws IOException {
        List<String> lines = Files.readAllLines(path);
        if (lines.isEmpty()) throw new IllegalArgumentException("Empty CSV: " + path);
        List<String> header = splitCsv(lines.get(0));
        int docIdx = header.contains("doc") ? header.indexOf("doc") : -1;
        int labelIdx = header.contains("label") ? header.indexOf("label") : -1;
        if (labelIdx < 0) throw new IllegalArgumentException("Missing 'label' column in " + path);

        List<String> colFeatures = new ArrayList<>();
        for (int i = 0; i < header.size(); i++) {
            String col = header.get(i);
            if (!col.equals("doc") && !col.equals("label") && !col.isEmpty()) colFeatures.add(col);
        }
        if (!colFeatures.equals(expectedFeatures)) {
            throw new IllegalArgumentException("Feature column mismatch in " + path + "\n  got : " + colFeatures
                    + "\n  want: " + expectedFeatures);
        }

        List<Row> rows = new ArrayList<>();
        for (int lineNo = 2; lineNo <= lines.size(); lineNo++) {
            List<String> cols = splitCsv(lines.get(lineNo - 1));
            if (cols.size() != header.size()) {
                throw new IllegalArgumentException("Row " + lineNo + " of " + path + " has "
                        + cols.size() + " columns, expected " + header.size());
            }
            String doc = docIdx >= 0 ? cols.get(docIdx) : "row" + lineNo;
            int label = Integer.parseInt(cols.get(labelIdx).trim());
            double[] features = new double[colFeatures.size()];
            int f = 0;
            for (int i = 0; i < header.size(); i++) {
                if (i == docIdx || i == labelIdx) continue;
                features[f++] = Double.parseDouble(cols.get(i).trim());
            }
            rows.add(new Row(doc, label, features));
        }
        return new Dataset(colFeatures, rows);
    }

    public static void writeCsv(Path path, List<Row> rows, List<String> featureNames) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("doc,label");
        for (String name : featureNames) sb.append(',').append(name);
        sb.append('\n');
        for (Row r : rows) {
            sb.append(r.doc()).append(',').append(r.label());
            for (double v : r.features()) sb.append(String.format(",%.6f", v));
            sb.append('\n');
        }
        Files.writeString(path, sb.toString());
    }

    /** Splits a CSV line respecting a simple quoted-field grammar. */
    private static List<String> splitCsv(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                inQuotes = !inQuotes;
            } else if (c == ',' && !inQuotes) {
                out.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        out.add(cur.toString());
        return out;
    }

    // ──────────────────────────────────────────────────────────────────────
    // Split / transform
    // ──────────────────────────────────────────────────────────────────────

    /** Document-aware split: whole docs go to one side only. */
    public Split splitByDoc(double validationFraction, long seed) {
        List<String> docs = new ArrayList<>(rows.stream().map(Row::doc).collect(Collectors.toSet()));
        Random rng = new Random(seed);
        Map<String, Boolean> toVal = new LinkedHashMap<>();
        for (String doc : docs) toVal.put(doc, rng.nextDouble() < validationFraction);
        List<Row> train = new ArrayList<>();
        List<Row> val = new ArrayList<>();
        for (Row r : rows) {
            if (toVal.get(r.doc())) val.add(r);
            else train.add(r);
        }
        if (train.isEmpty() || val.isEmpty()) {
            throw new IllegalArgumentException("Split produced an empty set; reduce validationFraction");
        }
        return new Split(new Dataset(featureNames, train), new Dataset(featureNames, val));
    }

    /** Winsorize bounds (1st/99th percentile) and z-score stats over these rows. */
    public Stats fitStats(double lowP, double highP) {
        int d = featureCount();
        int n = Math.max(1, rows.size());
        double[] lo = new double[d];
        double[] hi = new double[d];
        double[] sums = new double[d];
        double[] sumsq = new double[d];
        for (int j = 0; j < d; j++) {
            double[] col = new double[n];
            for (int i = 0; i < n; i++) col[i] = rows.get(i).features()[j];
            Arrays.sort(col);
            int lIdx = Math.min(n - 1, (int) Math.floor(lowP * n));
            int hIdx = Math.min(n - 1, (int) Math.ceil(highP * n));
            lo[j] = col[lIdx];
            hi[j] = col[hIdx];
        }
        for (Row r : rows) {
            for (int j = 0; j < d; j++) {
                double v = Math.max(lo[j], Math.min(hi[j], r.features()[j]));
                sums[j] += v;
                sumsq[j] += v * v;
            }
        }
        double[] means = new double[d];
        double[] stds = new double[d];
        for (int j = 0; j < d; j++) {
            means[j] = sums[j] / n;
            double v = Math.max(0, sumsq[j] / n - means[j] * means[j]);
            stds[j] = Math.sqrt(v);
        }
        return new Stats(lo, hi, means, stds);
    }

    /** Standardized feature matrix for the given rows + stats. */
    public static double[][] matrix(Dataset dataset, Stats stats) {
        int n = dataset.size();
        int d = dataset.featureCount();
        double[][] out = new double[n][];
        for (int i = 0; i < n; i++) out[i] = stats.transform(dataset.rows().get(i).features());
        return out;
    }
}