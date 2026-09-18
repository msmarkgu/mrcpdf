package com.mrcpdf.quality.training;

import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;

import com.mrcpdf.quality.model.FeatureExtractor;
import com.mrcpdf.util.ImageOps;

/**
 * Validates that the synthetic corpus's degradation score behaves like a
 * readability prior before it is labeled by humans:
 *
 * <ul>
 *   <li>reports the label mix and checks the 10-20 events-per-feature rule for
 *       the minority class (degraded pages must be plentiful enough to fit an
 *       11-feature logistic model without biased coefficients);</li>
 *   <li>reports mean synthetic score per tier (tiers should be ordered and
 *       close to their archetype centers);</li>
 *   <li>reports Spearman correlation of the synthetic score with each of the
 *       11 image features and the expected sign, a sanity check that the
 *       generated degradations move the features the gate model reads.</li>
 * </ul>
 *
 * <p>Usage: ScoreCorrCheck --src build/synthetic-src.pdf
 *                          --map build/synthetic-variant-map.csv
 *                          [--dpi 300] [--min-events 165]
 */
public final class ScoreCorrCheck {

    /** Features and their expected sign vs degradation risk (score = 1 - severity). */
    private static final Map<String, Integer> EXPECTED_SIGN = expectedSigns();

    public static void main(String[] args) throws Exception {
        Path src = null;
        Path map = null;
        int dpi = 300;
        int minEvents = 165;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--src" -> src = Path.of(args[++i]);
                case "--map" -> map = Path.of(args[++i]);
                case "--dpi" -> dpi = Integer.parseInt(args[++i]);
                case "--min-events" -> minEvents = Integer.parseInt(args[++i]);
                default -> {
                    System.err.println("Unknown arg: " + args[i]);
                    System.err.println("Usage: ScoreCorrCheck --src <pdf> --map <csv> [--dpi 300] [--min-events 165]");
                    return;
                }
            }
        }
        if (src == null || map == null) {
            System.err.println("--src and --map are required");
            return;
        }

        // page -> (label, tier, score)
        Map<Integer, String[]> meta = readMap(map);
        int n = meta.size();
        int[] labelByPage = new int[n + 1];
        double[] scoreByPage = new double[n + 1];
        for (int p = 1; p <= n; p++) {
            String[] v = meta.get(p);
            labelByPage[p] = Integer.parseInt(v[0]);
            scoreByPage[p] = Double.parseDouble(v[1]);
        }

        // Extract per-page features.
        double[][] features = new double[n][FeatureExtractor.IMAGE_FEATURE_NAMES.length];
        double[] scoreOrder = new double[n];
        int[] labelOrder = new int[n];
        try (PDDocument doc = Loader.loadPDF(src.toFile())) {
            if (doc.getNumberOfPages() != n) {
                throw new IllegalStateException("src pages " + doc.getNumberOfPages()
                        + " != map rows " + n);
            }
            PDFRenderer renderer = new PDFRenderer(doc);
            for (int i = 0; i < n; i++) {
                int p = i + 1;
                BufferedImage page = renderer.renderImageWithDPI(i, dpi);
                int w = page.getWidth();
                int h = page.getHeight();
                int[] gray = ImageOps.toGrayscale(page);
                features[i] = FeatureExtractor.extractImage(gray, w, h, dpi);
                scoreOrder[i] = scoreByPage[p];
                labelOrder[i] = labelByPage[p];
            }
        }

        // Ladder of checks.
        int positions = count(labelOrder, 1);
        int clean = count(labelOrder, 0);
        int featuresCount = FeatureExtractor.IMAGE_FEATURE_NAMES.length;
        int events = Math.min(positions, clean);
        System.out.printf("%nCorpus stats: %d pages (clean %d, degraded label-1 %d)%n",
                n, clean, positions);
        System.out.printf("Events per feature (min class / %d features): %.1f (target 10-20)%n",
                featuresCount, (double) events / featuresCount);
        if (events / featuresCount < 10) {
            System.out.println("WARNING: minority class too small for stable logistic coefficients — increase --docs/--pages.");
        } else if (events / featuresCount > 20) {
            System.out.println("Note: minority class above 20 events/feature — fine, just heavier.");
        }

        // Mean score per tier + label mix (in archetype order).
        Map<String, long[]> tierHist = new LinkedHashMap<>();
        Map<String, double[]> tierScore = new LinkedHashMap<>();
        Map<String, Integer> tierLabel1 = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) {
            String tier = meta.get(i + 1)[2];
            tierHist.computeIfAbsent(tier, k -> new long[1])[0]++;
            tierScore.computeIfAbsent(tier, k -> new double[1])[0] += scoreOrder[i];
            if (labelOrder[i] == 1) tierLabel1.merge(tier, 1, Integer::sum);
        }
        System.out.println("\nPer-tier stats (mean score, label-1 count):");
        for (ScannerTier t : ScannerTier.values()) {
            String slug = t.slug();
            long total = tierHist.containsKey(slug) ? tierHist.get(slug)[0] : 0;
            double mean = total > 0 ? tierScore.get(slug)[0] / total : Double.NaN;
            int ones = tierLabel1.getOrDefault(slug, 0);
            String center = " (archetype " + String.format(Locale.ROOT, "%.2f", t.center()) + ")";
            System.out.printf(Locale.ROOT, "  %-6s n=%-4d mean_score=%-6.3f label1=%-4d%s%n",
                    slug, total, mean, ones, center);
        }

        // Feature correlations: score -> feature, expect the sign the gate
        // model uses (locked quality-model.json / QualityModelTest):
        //   edgeWidthCV>0, hiFreqEnergy<0, otsuSeparability<0, snr<0  (p(degraded))
        // so the score correlation is the OPPOSITE sign (score is quality).
        System.out.println("\nSpearman correlation (synthetic score vs feature):");
        String[] names = FeatureExtractor.IMAGE_FEATURE_NAMES;
        boolean ok = true;
        for (int f = 0; f < names.length; f++) {
            double[] col = new double[n];
            for (int i = 0; i < n; i++) col[i] = features[i][f];
            double rho = spearman(col, scoreOrder);
            String expected = String.format("%+d", EXPECTED_SIGN.getOrDefault(names[f], 0));
            String mark = (EXPECTED_SIGN.getOrDefault(names[f], 0) > 0 && rho > 0)
                    || (EXPECTED_SIGN.getOrDefault(names[f], 0) < 0 && rho < 0)
                    ? "ok" : "SIGN MISMATCH";
            System.out.printf("  %-18s rho=%+.3f (expected %s) %s%n", names[f], rho, expected, mark);
            if (mark.contains("SIGN")) ok = false;
        }
        System.out.println(ok ? "All feature-sign checks passed." : "Some feature signs are off — review tiers/weights.");

        // Per-tier feature means so the Ladder of checks has ground truth for
        // whether each degradation moves its features in the model's direction.
        System.out.println("\nPer-tier feature means (higher score = cleaner):");
        System.out.printf("  %-6s %11s%n", "tier", String.join(" ", names));
        for (ScannerTier t : ScannerTier.values()) {
            String slug = t.slug();
            int cnt = 0;
            double[] sum = new double[names.length];
            for (int i = 0; i < n; i++) {
                if (meta.get(i + 1)[2].equals(slug)) {
                    cnt++;
                    for (int f = 0; f < names.length; f++) sum[f] += features[i][f];
                }
            }
            if (cnt == 0) continue;
            StringBuilder sb = new StringBuilder();
            for (int f = 0; f < names.length; f++) sb.append(String.format(" %10.4f", sum[f] / cnt));
            System.out.printf("  %-6s%s%n", slug, sb);
        }

        // Score monotonicity vs label as a sanity floor.
        double label0 = meanOf(scoreOrder, labelOrder, 0);
        double label1 = meanOf(scoreOrder, labelOrder, 1);
        System.out.printf("%nMean synthetic score: label0=%.3f  label1=%.3f%n", label0, label1);
        if (label1 >= label0) {
            System.out.println("WARNING: label-1 pages do not score below label-0 pages.");
        } else {
            System.out.println("Label ordering OK (label-1 pages score lower).");
        }
        if (positions < minEvents) {
            System.out.printf("WARNING: label-1 count %d below --min-events %d.%n", positions, minEvents);
        }
    }

    private static Map<Integer, String[]> readMap(Path map) throws Exception {
        Map<Integer, String[]> out = new LinkedHashMap<>();
        for (String line : Files.readAllLines(map, StandardCharsets.US_ASCII)) {
            if (line.isBlank() || line.startsWith("page,")) continue;
            String[] c = line.split(",", 6);
            if (c.length < 6) throw new IllegalArgumentException("Bad map row: " + line);
            out.put(Integer.parseInt(c[0]),
                    new String[]{c[3], c[5], c[4]});
        }
        return out;
    }

    private static int count(int[] a, int value) {
        int n = 0;
        for (int v : a) if (v == value) n++;
        return n;
    }

    private static double meanOf(double[] score, int[] label, int which) {
        double sum = 0;
        int n = 0;
        for (int i = 0; i < score.length; i++) {
            if (label[i] == which) {
                sum += score[i];
                n++;
            }
        }
        return n > 0 ? sum / n : -1;
    }

    /** Spearman rank correlation (average ranks for ties). */
    static double spearman(double[] a, double[] b) {
        double[] ra = rank(a);
        double[] rb = rank(b);
        return pearson(ra, rb);
    }

    private static double pearson(double[] a, double[] b) {
        int n = a.length;
        double ma = 0, mb = 0;
        for (int i = 0; i < n; i++) {
            ma += a[i];
            mb += b[i];
        }
        ma /= n;
        mb /= n;
        double num = 0, da = 0, db = 0;
        for (int i = 0; i < n; i++) {
            double x = a[i] - ma;
            double y = b[i] - mb;
            num += x * y;
            da += x * x;
            db += y * y;
        }
        double den = Math.sqrt(da * db);
        return den == 0 ? 0 : num / den;
    }

    private static double[] rank(double[] v) {
        int n = v.length;
        Integer[] idx = new Integer[n];
        for (int i = 0; i < n; i++) idx[i] = i;
        java.util.Arrays.sort(idx, java.util.Comparator.comparingDouble(i -> v[i]));
        double[] r = new double[n];
        int i = 0;
        while (i < n) {
            int j = i;
            while (j + 1 < n && v[idx[j + 1]] == v[idx[i]]) j++;
            double avg = (i + j) / 2.0 + 1;
            for (int k = i; k <= j; k++) r[idx[k]] = avg;
            i = j + 1;
        }
        return r;
    }

    /**
     * Expected sign of rho(score, feature): score is quality, so it is the
     * opposite of the committed model's p(degraded) weight (see
     * QualityModelTest.coefficientSignsMatchPhysicalMechanism — edgeWidthCV,
     * otsuSeparability and snr are asserted by sign there).
     */
    private static Map<String, Integer> expectedSigns() {
        Map<String, Integer> m = new LinkedHashMap<>();
        m.put("blurSigmaPx", -1);        // more blur -> wider edges -> worse
        m.put("edgeWidthCV", -1);        // model weight edgeWidthCV>0
        m.put("hiFreqEnergy", 1);        // model weight hiFreqEnergy<0
        m.put("laplacianVar", 1);        // sharper page -> higher variance
        m.put("otsuSeparability", 1);    // model weight otsuSeparability<0
        m.put("michelsonContrast", 1);   // stronger stroke/background contrast
        m.put("histRange", 1);           // full 0..255 span means clean
        m.put("bgUniformity", -1);       // stains/bleed-through -> less uniform
        m.put("noiseSigma", -1);         // noise degrades
        m.put("snr", 1);                 // model weight snr<0
        m.put("inkCoverage", -1);        // blur/bold/streaks spread ink; model weight +
        return m;
    }
}