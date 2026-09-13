package com.mrcpdf.quality.training;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.JPEGFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.rendering.PDFRenderer;

/**
 * Renders a side-by-side labeling doc for the synthetic corpus: one page per
 * variant with the ORIGINAL page on top and its MRC output on the bottom, both
 * at ~300-DPI panel resolution inside a 612x1590 pt page so 11pt body text
 * stays legible.  Page i maps 1:1 to the variant-map row (doc, variant,
 * input-severity label).
 *
 * Usage: BuildSyntheticPairs --src build/synthetic-src.pdf
 *                            --mrc build/synthetic-mrc.pdf
 *                            --map build/synthetic-variant-map.csv
 *                            [--dpi 300] [--out build/synthetic-pairs.pdf]
 */
public final class BuildSyntheticPairs {

    private static final float PAGE_W = 612;
    private static final float SRC_H = 792;
    private static final int DENSITY = 2;
    private static final int PANEL_H = DENSITY * 792;
    private static final int BAND_H = 44;

    public static void main(String[] args) throws Exception {
        Path src = null;
        Path mrc = null;
        Path map = null;
        Path out = Path.of("build/synthetic-pairs.pdf");
        int dpi = 300;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--src" -> src = Path.of(args[++i]);
                case "--mrc" -> mrc = Path.of(args[++i]);
                case "--map" -> map = Path.of(args[++i]);
                case "--out" -> out = Path.of(args[++i]);
                case "--dpi" -> dpi = Integer.parseInt(args[++i]);
                default -> {
                    System.err.println("Unknown arg: " + args[i]);
                    System.err.println("Usage: BuildSyntheticPairs --src <pdf> --mrc <pdf> --map <csv> [--out <pdf>] [--dpi N]");
                    return;
                }
            }
        }
        if (src == null || mrc == null || map == null) {
            System.err.println("Missing required --src/--mrc/--map");
            return;
        }

        List<String[]> rows = readMap(map);
        try (PDDocument srcDoc = Loader.loadPDF(src.toFile());
             PDDocument mrcDoc = Loader.loadPDF(mrc.toFile());
             PDDocument pairs = new PDDocument()) {
            if (srcDoc.getNumberOfPages() != mrcDoc.getNumberOfPages()) {
                throw new IllegalStateException("src/mrc page count mismatch: "
                        + srcDoc.getNumberOfPages() + " vs " + mrcDoc.getNumberOfPages());
            }
            if (rows.size() != srcDoc.getNumberOfPages()) {
                throw new IllegalStateException("map rows (" + rows.size()
                        + ") != pages (" + srcDoc.getNumberOfPages() + ")");
            }
            PDFRenderer srcRenderer = new PDFRenderer(srcDoc);
            PDFRenderer mrcRenderer = new PDFRenderer(mrcDoc);
            for (int i = 0; i < rows.size(); i++) {
                BufferedImage orig = srcRenderer.renderImageWithDPI(i, dpi);
                BufferedImage comp = mrcRenderer.renderImageWithDPI(i, dpi);
                String[] row = rows.get(i);
                embedPair(pairs, orig, comp, row, i + 1);
                if ((i + 1) % 20 == 0) {
                    System.out.println("  paired " + (i + 1) + "/" + rows.size());
                }
            }
            pairs.save(out.toFile());
        }
        System.out.println("Wrote " + out.toAbsolutePath() + " (" + rows.size() + " pages)");
    }

    private static List<String[]> readMap(Path map) throws IOException {
        List<String[]> out = new ArrayList<>();
        for (String line : Files.readAllLines(map, StandardCharsets.US_ASCII)) {
            if (line.isBlank() || line.startsWith("page,")) continue;
            String[] c = line.split(",", 5);
            if (c.length < 4) throw new IllegalArgumentException("Bad map row: " + line);
            out.add(c);
        }
        return out;
    }

    private static void embedPair(PDDocument pairs, BufferedImage orig, BufferedImage comp,
                                  String[] row, int pageNo) throws IOException {
        int cw = DENSITY * (int) PAGE_W;
        int ch = 2 * PANEL_H + 2 * BAND_H;
        BufferedImage sheet = new BufferedImage(cw, ch, BufferedImage.TYPE_INT_RGB);
        Graphics2D g2 = sheet.createGraphics();
        try {
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g2.setColor(Color.WHITE);
            g2.fillRect(0, 0, cw, ch);

            drawPanel(g2, orig, "ORIGINAL", row, pageNo, 0, PANEL_H);
            drawPanel(g2, comp, "MRC", row, pageNo, PANEL_H + BAND_H, 2 * PANEL_H + BAND_H);
        } finally {
            g2.dispose();
        }

        PDPage pdPage = new PDPage(new PDRectangle(PAGE_W, SRC_H * 2 + 2 * BAND_H / (float) DENSITY));
        pairs.addPage(pdPage);
        try (PDPageContentStream cs = new PDPageContentStream(pairs, pdPage)) {
            PDImageXObject img = JPEGFactory.createFromImage(pairs, sheet, 0.85f);
            cs.drawImage(img, 0, 0, PAGE_W, SRC_H * 2 + 2 * BAND_H / (float) DENSITY);
        }
    }

    private static void drawPanel(Graphics2D g2, BufferedImage img,
                                  String side, String[] row, int pageNo,
                                  int top, int bottom) {
        int w = DENSITY * (int) PAGE_W;
        int h = bottom - top;
        double scale = Math.min((double) w / img.getWidth(), (double) h / img.getHeight());
        int dw = (int) Math.round(img.getWidth() * scale);
        int dh = (int) Math.round(img.getHeight() * scale);
        int dx = (w - dw) / 2;
        int dy = top + (h - dh) / 2;
        g2.drawImage(img, dx, dy, dw, dh, null);

        // Label band overlays the page's top margin (text starts at ~50pt).
        int bandY = top + 4;
        int bandH = 46;
        g2.setColor(new Color(255, 255, 255, 210));
        g2.fillRect(dx, bandY, dw, bandH);
        g2.setColor(Color.BLACK);
        g2.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 22));
        String label = "p" + pageNo + "  " + row[1] + "  " + row[2]
                + "  (input " + row[3] + ")  " + side + "     -> unreadable after MRC? 0/1";
        g2.drawString(label, dx + 8, bandY + 31);
    }

    private BuildSyntheticPairs() {
    }
}