package com.mrcpdf.quality.training;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;

/**
 * Debug companion to {@link SynthSrcDump}: writes a small PDF whose pages are
 * FORCED to cycle through every ScannerTier so ScoreCorrCheck's sign checks
 * can be iterated quickly on the real raster-PDF-render chain.
 *
 * <p>Usage: TierCheckDump [--per-tier N] [--seed S] [--out <pdf>] [--map <csv>]
 */
public final class TierCheckDump {

    private static final float PAGE_W = 612;
    private static final float PAGE_H = 792;

    public static void main(String[] args) throws Exception {
        int perTier = 10;
        long seed = 42L;
        Path out = Path.of("build/tier-check.pdf");
        Path map = Path.of("build/tier-check.csv");
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--per-tier" -> perTier = Integer.parseInt(args[++i]);
                case "--seed" -> seed = Long.parseLong(args[++i]);
                case "--out" -> out = Path.of(args[++i]);
                case "--map" -> map = Path.of(args[++i]);
                default -> {
                    System.err.println("Unknown arg: " + args[i]);
                    System.err.println("Usage: TierCheckDump [--per-tier N] [--seed S] [--out <pdf>] [--map <csv>]");
                    return;
                }
            }
        }
        ScannerTier[] tiers = ScannerTier.values();
        StringBuilder csv = new StringBuilder("page,doc,variant,label,tier,score\n");
        int page = 0;
        try (PDDocument preview = new PDDocument()) {
            for (int ti = 0; ti < tiers.length; ti++) {
                ScannerTier t = tiers[ti];
                SyntheticCorpus.Page base = SyntheticCorpus.renderPage(seed ^ (ti * 0x9E3779B97F4A7C15L));
                for (int i = 0; i < perTier; i++) {
                    long ps = seed ^ (i * 0xBF58476D1CE4E5B9L + ti * 0x51A7ED0000000007L);
                    Random rng = new Random(ps ^ 0x51a7);
                    ScannerProfile p = t.draw(rng);
                    double score = SeverityScore.score(p);
                    int label = SeverityScore.label(score);
                    java.awt.image.BufferedImage img = ScannerSimulator.apply(base.image(), p, rng);
                    page++;
                    // copy of SynthSrcDump.embedPage
                    PDPage pdPage = new PDPage(new PDRectangle(PAGE_W, PAGE_H));
                    preview.addPage(pdPage);
                    try (PDPageContentStream cs = new PDPageContentStream(preview, pdPage)) {
                        JpegCodec.embed(preview, cs, img, SyntheticCorpus.jpegQualityForScore(score), 0, 0, PAGE_W, PAGE_H);
                    }
                    csv.append(page).append(",tiercheck,var").append(page)
                            .append(',').append(label).append(',')
                            .append(t.slug()).append(',')
                            .append(String.format(java.util.Locale.ROOT, "%.3f", score)).append('\n');
                }
            }
            preview.save(out.toFile());
        }
        Files.writeString(map, csv.toString(), StandardCharsets.US_ASCII);
        System.out.println("Wrote " + out.toAbsolutePath() + " (" + page + " pages)");
        System.out.println("Wrote " + map.toAbsolutePath());
    }

    private TierCheckDump() {
    }
}