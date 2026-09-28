package com.mrcpdf.quality.training;

import java.awt.Color;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

/**
 * Source-dump companion to {@link SynthPageDump}: writes every corpus variant
 * page at full 300-DPI resolution into a plain PDF, one page per variant, in
 * exactly the same order as the labeled preview so an MRC pass over it maps
 * 1:1 to the corpus rows.
 *
 * <p>Each page embeds the variant raster as grayscale JPEG q~0.90 into a
 * 612x792 pt page — the same raster → PDF → render chain the corpus and
 * production inputs go through — so the MRC output is produced from the same
 * pixels the model's features are computed on.  A {@code score = X.XXX} banner
 * is then drawn as vector text in the page's blank top margin so the synthetic
 * quality is visible when the dump is eyeballed or labeled.
 *
 * <p>Also writes {@code --map <csv>}: page,doc,variant,label,tier,score with
 * the variant name, the derived binary label (0 = MRC safe, 1 = skip MRC), the
 * quality tier archetype and the synthetic quality score in [0,1], so labeled
 * pages can be joined back to corpus rows.
 *
 * <p>Usage: SynthSrcDump [--docs N] [--pages N] [--seed S]
 *                        [--out build/synthetic-src.pdf]
 *                        [--map build/synthetic-variant-map.csv]
 * <p>Page count = docs x (1 clean + (pages-1) degraded variants).
 */
public final class SynthSrcDump {

private static final float PAGE_W = 612;
    private static final float PAGE_H = 792;

    public static void main(String[] args) throws Exception {
        int docs = 25;
        int pagesPerDoc = 20;
        long seed = 42L;
        Path out = Path.of("build/synthetic-src.pdf");
        Path map = Path.of("build/synthetic-variant-map.csv");
        boolean steer = false;
        double steerLo = ScoreSampler.DEFAULT_LO;
        double steerHi = ScoreSampler.DEFAULT_HI;
        String steerDims = "visual,mask,bg";
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--docs" -> docs = Integer.parseInt(args[++i]);
                case "--pages" -> pagesPerDoc = Integer.parseInt(args[++i]);
                case "--seed" -> seed = Long.parseLong(args[++i]);
                case "--out" -> out = Path.of(args[++i]);
                case "--map" -> map = Path.of(args[++i]);
                case "--steer" -> steer = true;
                case "--no-steer" -> steer = false;
                case "--steer-lo" -> steerLo = Double.parseDouble(args[++i]);
                case "--steer-hi" -> steerHi = Double.parseDouble(args[++i]);
                case "--steer-dims" -> steerDims = args[++i];
                default -> {
                    System.err.println("Unknown arg: " + args[i]);
                    System.err.println("Usage: SynthSrcDump [--docs N] [--pages N] [--seed S] "
                            + "[--out <pdf>] [--map <csv>] [--steer] [--steer-lo F] "
                            + "[--steer-hi F] [--steer-dims visual,mask,bg]");
                    return;
                }
            }
        }
        if (pagesPerDoc < 2) {
            System.err.println("--pages must be >= 2 (clean + at least one degraded variant)");
            return;
        }
        if (steerLo >= steerHi) {
            System.err.println("--steer-lo must be below --steer-hi");
            return;
        }
        ScoreSampler.Steering steering = steer
                ? new ScoreSampler.Steering(true, steerLo, steerHi,
                        ScoreSampler.DEFAULT_TOLERANCE, ScoreSampler.DEFAULT_MAX_ATTEMPTS,
                        RealTrainDump.parseDims(steerDims))
                : null;

        StringBuilder csv = new StringBuilder(
                "page,doc,variant,label,tier,score,steerDim,steerTarget,steerValue,steerAttempts,onTarget\n");
        int pageCount = docs * pagesPerDoc;
        try (PDDocument preview = new PDDocument()) {
            int page = 0;
            for (int d = 0; d < docs; d++) {
                long rngSeed = seed ^ (d * 0x9E3779B97F4A7C15L);
                SyntheticCorpus.Page p = SyntheticCorpus.renderPage(rngSeed);

                List<SyntheticCorpus.Variant> sheets = new ArrayList<>();
                sheets.add(new SyntheticCorpus.Variant("clean", "clean",
                        SyntheticCorpus.cleanScan(p, rngSeed), 1.0, 0));
                sheets.addAll(SyntheticCorpus.buildScannedPages(p, rngSeed, pagesPerDoc - 1, steering));

                for (SyntheticCorpus.Variant v : sheets) {
                    page++;
                    embedPage(preview, v.image(), v.score());
                    csv.append(page).append(',').append("doc").append(d).append(',')
                            .append(v.name()).append(',').append(v.label()).append(',')
                            .append(v.tier()).append(',')
                            .append(String.format(Locale.ROOT, "%.3f", v.score()));
                    // Empty, not zero, for a clean or unsteered page: a steer value
                    // of 0.0 is a real measurement of a page destroyed past
                    // recognition, and ScoreCorrCheck bins it as such.
                    if (v.steer() == null) {
                        csv.append(",,,,\n");
                    } else {
                        csv.append(',').append(v.steer().dim().slug())
                                .append(',').append(String.format(Locale.ROOT, "%.3f", v.steer().target()))
                                .append(',').append(String.format(Locale.ROOT, "%.3f", v.steer().value()))
                                .append(',').append(v.steer().attempts())
                                .append(',').append(v.steer().onTarget()).append('\n');
                    }
                }
            }
            preview.save(out.toFile());
        }
        Files.writeString(map, csv.toString(), StandardCharsets.US_ASCII);

        long[] counts = {0, 0};
        csv.toString().lines().skip(1).forEach(l -> {
            String[] c = l.split(",", 6);
            if (c.length >= 6) counts[Integer.parseInt(c[3])]++;
        });
        System.out.println("Wrote " + out.toAbsolutePath() + " (" + pageCount + " pages)");
        System.out.println("Wrote " + map.toAbsolutePath());
        System.out.printf("Label 0: %d, label 1 (skip MRC): %d (%d pages total)%n",
                counts[0], counts[1], pageCount);

        if (steering != null && steering.enabled()) {
            List<ScoreSampler.SteeringRow> steered = new ArrayList<>();
            csv.toString().lines().skip(1).forEach(l -> {
                String[] c = l.split(",", -1);
                if (c.length < 11 || c[6].isEmpty()) return;
                // The synthetic label is measured 1-WER, not R_mrc, so there is no
                // R_mrc class mix to report here; map the 1-WER binary label onto
                // the three-class bins so the histogram still has something to
                // check, and let the histogram's steer distribution carry the QA.
                int cls = "1".equals(c[3]) ? 0 : 2;
                steered.add(new Row(Double.parseDouble(c[8]), Double.parseDouble(c[7]),
                        Boolean.parseBoolean(c[10]), cls));
            });
            ScoreCalibration.Histogram hist = ScoreCalibration.histogram(steered, 10,
                    steering.lo(), steering.hi());
            hist.print();
        }
    }

    /** Minimal {@link ScoreSampler.SteeringRow} for the synthetic map. */
    private record Row(double steer, double target, boolean onTarget, int cls)
            implements ScoreSampler.SteeringRow {
    }

    private static void embedPage(PDDocument doc, java.awt.image.BufferedImage image, double score) throws IOException {
        PDPage pdPage = new PDPage(new PDRectangle(PAGE_W, PAGE_H));
        doc.addPage(pdPage);
        try (PDPageContentStream cs = new PDPageContentStream(doc, pdPage)) {
            JpegCodec.embed(doc, cs, image, SyntheticCorpus.LABEL_JPEG_QUALITY, 0, 0, PAGE_W, PAGE_H);
            drawScoreOverlay(cs, score);
        }
    }

    /** Draws {@code score = X.XXX} in the blank top margin (content starts at y≈50). */
    private static void drawScoreOverlay(PDPageContentStream cs, double score) throws IOException {
        String text = "score = " + String.format(Locale.ROOT, "%.3f", score);
        PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
        float fontSize = 11;
        float textWidth = font.getStringWidth(text) / 1000f * fontSize;
        float x = (PAGE_W - textWidth) / 2f;
        float baseline = PAGE_H - 12;
        cs.setNonStrokingColor(Color.WHITE);
        cs.addRect(x - 4, baseline - 3, textWidth + 8, 15);
        cs.fill();
        cs.setNonStrokingColor(Color.BLACK);
        cs.beginText();
        cs.setFont(font, fontSize);
        cs.newLineAtOffset(x, baseline);
        cs.showText(text);
        cs.endText();
    }

    private SynthSrcDump() {
    }
}