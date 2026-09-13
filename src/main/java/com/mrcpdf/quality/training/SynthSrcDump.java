package com.mrcpdf.quality.training;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.JPEGFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;

/**
 * Source-dump companion to {@link SynthPageDump}: writes every corpus variant
 * page at full 300-DPI resolution into a plain PDF, one page per variant, in
 * exactly the same order as the labeled preview so an MRC pass over it maps
 * 1:1 to the corpus rows.
 *
 * Each page embeds the variant raster as JPEG q~0.95 into a 612x792 pt page —
 * the same raster → PDF → render chain the corpus and production inputs go
 * through — so the MRC output is produced from the same pixels the model's
 * features are computed on.
 *
 * Also writes {@code --map <csv>}: page,variant,label with the variant name and
 * its input-severity label, so labeled pages can be joined back to corpus rows.
 *
 * Usage: SynthSrcDump [--docs N] [--seed S]
 *                     [--out build/synthetic-src.pdf]
 *                     [--map build/synthetic-variant-map.csv]
 */
public final class SynthSrcDump {

    private static final float EMBED_JPEG_QUALITY = 0.95f;
    private static final float PAGE_W = 612;
    private static final float PAGE_H = 792;

    public static void main(String[] args) throws Exception {
        int docs = 20;
        long seed = 42L;
        Path out = Path.of("build/synthetic-src.pdf");
        Path map = Path.of("build/synthetic-variant-map.csv");
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--docs" -> docs = Integer.parseInt(args[++i]);
                case "--seed" -> seed = Long.parseLong(args[++i]);
                case "--out" -> out = Path.of(args[++i]);
                case "--map" -> map = Path.of(args[++i]);
                default -> {
                    System.err.println("Unknown arg: " + args[i]);
                    System.err.println("Usage: SynthSrcDump [--docs N] [--seed S] [--out <pdf>] [--map <csv>]");
                    return;
                }
            }
        }

        StringBuilder csv = new StringBuilder("page,doc,variant,label\n");
        int pageCount = docs * 12;
        try (PDDocument preview = new PDDocument()) {
            int page = 0;
            for (int d = 0; d < docs; d++) {
                long rngSeed = seed ^ (d * 0x9E3779B97F4A7C15L);
                SyntheticCorpus.Page p = SyntheticCorpus.renderPage(rngSeed);

                List<SyntheticCorpus.Variant> sheets = new ArrayList<>();
                sheets.add(new SyntheticCorpus.Variant("clean", p.image(), 0));
                sheets.addAll(SyntheticCorpus.buildVariants(p, rngSeed));

                for (SyntheticCorpus.Variant v : sheets) {
                    page++;
                    embedPage(preview, v.image());
                    csv.append(page).append(',').append("doc").append(d).append(',')
                            .append(v.name()).append(',').append(v.label()).append('\n');
                }
            }
            preview.save(out.toFile());
        }
        Files.writeString(map, csv.toString(), StandardCharsets.US_ASCII);

        System.out.println("Wrote " + out.toAbsolutePath() + " (" + pageCount + " pages)");
        System.out.println("Wrote " + map.toAbsolutePath());
    }

    private static void embedPage(PDDocument doc, java.awt.image.BufferedImage image) throws IOException {
        PDPage pdPage = new PDPage(new PDRectangle(PAGE_W, PAGE_H));
        doc.addPage(pdPage);
        try (PDPageContentStream cs = new PDPageContentStream(doc, pdPage)) {
            PDImageXObject img = JPEGFactory.createFromImage(doc, image, EMBED_JPEG_QUALITY);
            cs.drawImage(img, 0, 0, PAGE_W, PAGE_H);
        }
    }

    private SynthSrcDump() {
    }
}