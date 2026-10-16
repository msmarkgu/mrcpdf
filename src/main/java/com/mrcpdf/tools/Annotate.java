package com.mrcpdf.tools;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import com.mrcpdf.quality.TextQualityScore;
import com.mrcpdf.quality.TextQualityScorer;
import com.mrcpdf.quality.model.FeatureExtractor;
import com.mrcpdf.quality.training.Dataset;
import com.mrcpdf.util.ImageOps;

/**
 * Annotation tooling for the Phase-2 quality model.
 *
 * {@code export} renders each page of a PDF, computes the Phase-1 composite and
 * the Phase-2 image-only features, and writes a page-level feature CSV. {@code
 * ingest} joins an exported feature CSV with a {@code doc,page,label} labeling
 * sheet (0 = clean, 1 = degraded) into the training CSV consumed by {@link
 * TrainModel}. The composite column stays in the export so annotators can focus
 * on boundary pages ({@code --min}/{@code --max}).
 */
@Command(
    name = "annotate",
    description = "Export/ingest labeled page features for quality-model training",
    mixinStandardHelpOptions = true,
    subcommands = {Annotate.ExportCmd.class, Annotate.IngestCmd.class}
)
public class Annotate implements Runnable {

    @Override
    public void run() {
        CommandLine.usage(this, System.err);
    }

    public static void main(String[] args) {
        int exitCode = new CommandLine(new Annotate()).execute(args);
        System.exit(exitCode);
    }

    // ──────────────────────────────────────────────────────────────────────
    // export
    // ──────────────────────────────────────────────────────────────────────

    @Command(name = "export", description = "Render pages and write per-page image-feature CSV",
             mixinStandardHelpOptions = true)
    public static final class ExportCmd implements Callable<Integer> {

        @Parameters(index = "0", description = "Input PDF file")
        private File inputFile;

        @Option(names = "--out", description = "Output feature CSV (default: <name>.features.csv)")
        private Path out;

        @Option(names = "--dpi", description = "Render DPI (default: 200)")
        private int dpi = 200;

        @Option(names = "--min", description = "Only export pages with composite >= min")
        private Double min;

        @Option(names = "--max", description = "Only export pages with composite <= max")
        private Double max;

        @Override
        public Integer call() throws IOException {
            List<String> featureNames = List.of(FeatureExtractor.IMAGE_FEATURE_NAMES);
            List<Dataset.Row> rows = new ArrayList<>();
            try (PDDocument doc = Loader.loadPDF(inputFile)) {
                PDFRenderer renderer = new PDFRenderer(doc);
                TextQualityScorer scorer = new TextQualityScorer(dpi);
                int pages = doc.getNumberOfPages();
                for (int pageIdx = 0; pageIdx < pages; pageIdx++) {
                    BufferedImage page = renderer.renderImageWithDPI(pageIdx, dpi);
                    TextQualityScore score = scorer.compute(page, null);
                    if (min != null && score.getComposite() < min) continue;
                    if (max != null && score.getComposite() > max) continue;

                    int w = page.getWidth();
                    int h = page.getHeight();
                    int[] gray = ImageOps.toGrayscale(page);
                    double[] features = FeatureExtractor.extractImage(gray, w, h, dpi);
                    // composite rides along as an extra first column for the
                    // annotator's boundary focus; ingest drops it.
                    double[] rowFeatures = new double[features.length + 1];
                    rowFeatures[0] = score.getComposite();
                    System.arraycopy(features, 0, rowFeatures, 1, features.length);
                    rows.add(new Dataset.Row(docId(inputFile), 0, rowFeatures));
                    System.out.printf("  page %d: composite %.2f%n", pageIdx + 1, score.getComposite());
                }
            }
            Path target = out != null ? out
                    : Path.of(inputFile.getName().replaceAll("\\.[^.]+$", "") + ".features.csv");
            writeExportCsv(target, rows);
            System.out.println("Wrote " + rows.size() + " rows to " + target);
            return 0;
        }

        private static String docId(File input) {
            return input.getName().replaceAll("\\.[^.]+$", "");
        }

        /** Export layout: doc,page,composite,f1..f11. */
        private void writeExportCsv(Path path, List<Dataset.Row> rows) throws IOException {
            StringBuilder sb = new StringBuilder("doc,page,composite");
            for (String name : FeatureExtractor.IMAGE_FEATURE_NAMES) sb.append(',').append(name);
            sb.append('\n');
            for (int i = 0; i < rows.size(); i++) {
                Dataset.Row r = rows.get(i);
                sb.append(r.doc()).append(',').append(i + 1);
                for (double v : r.features()) sb.append(String.format(",%.6f", v));
                sb.append('\n');
            }
            Files.writeString(path, sb.toString());
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // ingest
    // ──────────────────────────────────────────────────────────────────────

    @Command(name = "ingest", description = "Join exported features with a doc,page,label sheet into a training CSV",
             mixinStandardHelpOptions = true)
    public static final class IngestCmd implements Callable<Integer> {

        @Option(names = "--features", description = "Feature CSV written by 'export'", required = true)
        private Path features;

        @Option(names = "--labels", description = "Label CSV with header doc,page,label", required = true)
        private Path labels;

        @Option(names = "--out", description = "Output training CSV", required = true)
        private Path out;

        @Override
        public Integer call() throws IOException {
            List<String> featureNames = List.of(FeatureExtractor.IMAGE_FEATURE_NAMES);

            // features: doc,page,composite,f1..f11 -> key (doc,page)
            Map<String, double[]> byPage = new LinkedHashMap<>();
            for (String line : Files.readAllLines(features)) {
                if (line.isBlank()) continue;
                String[] cols = splitCsv(line);
                if (cols[0].equals("doc")) continue;
                if (cols.length != 2 + 1 + featureNames.size()) {
                    throw new IllegalArgumentException("Unexpected feature row width: " + line);
                }
                double[] feats = new double[featureNames.size()];
                for (int i = 0; i < featureNames.size(); i++) feats[i] = Double.parseDouble(cols[i + 3]);
                byPage.put(cols[0] + ":" + cols[1], feats);
            }

            // labels: doc,page,label
            List<Dataset.Row> rows = new ArrayList<>();
            int joined = 0;
            for (String line : Files.readAllLines(labels)) {
                if (line.isBlank()) continue;
                String[] cols = splitCsv(line);
                if (cols[0].equals("doc")) continue;
                if (cols.length != 3) throw new IllegalArgumentException("Label row must be doc,page,label: " + line);
                double[] feats = byPage.get(cols[0] + ":" + cols[1]);
                if (feats == null) {
                    System.err.println("  warning: no features for " + cols[0] + " page " + cols[1]);
                    continue;
                }
                rows.add(new Dataset.Row(cols[0], Integer.parseInt(cols[2].trim()), feats));
                joined++;
            }
            Dataset.writeCsv(out, rows, featureNames);
            System.out.println("Joined " + joined + " labeled rows to " + out);
            return 0;
        }

        private static String[] splitCsv(String line) {
            List<String> parts = new ArrayList<>();
            StringBuilder cur = new StringBuilder();
            for (int i = 0; i < line.length(); i++) {
                char c = line.charAt(i);
                if (c == ',') {
                    parts.add(cur.toString());
                    cur.setLength(0);
                } else {
                    cur.append(c);
                }
            }
            parts.add(cur.toString());
            return parts.toArray(new String[0]);
        }
    }
}