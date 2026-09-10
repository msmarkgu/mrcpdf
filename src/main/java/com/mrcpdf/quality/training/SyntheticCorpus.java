package com.mrcpdf.quality.training;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.awt.image.ConvolveOp;
import java.awt.image.Kernel;
import java.io.IOException;
import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.JPEGFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.rendering.PDFRenderer;

import com.mrcpdf.model.TextBlock;
import com.mrcpdf.quality.model.FeatureExtractor;
import com.mrcpdf.quality.model.QualityModel;
import com.mrcpdf.util.ImageOps;

/**
 * Deterministic synthetic corpus for training and validating the quality-gate
 * model, engineered to live in the same feature space as the pipeline's real
 * inputs.
 *
 * Production pages pass through: a Java-AWT raster of text (per-page 612×792
 * grayscale scan, font 14, JPEG q~0.95) embedded in a PDF, then re-rendered at
 * 300 DPI by PDFRenderer.  The corpus reproduces that exact chain, so clean
 * corpus pages share the anti-aliasing / upscale / JPEG statistics of real
 * scans, and the trained gate transfers without re-training.
 *
 * Each document renders its own page (deterministic per seed) and derives a
 * clean row plus mild and severe versions of each degradation (Gaussian blur,
 * additive sensor noise, JPEG recompression, low-resolution downscale, broken
 * strokes).  Every variant of one source page shares its {@code doc} id so the
 * train/val split stays document-aware.  Mild degradations label 0 (fine) and
 * severe ones label 1 (degraded → skip MRC).
 */
public final class SyntheticCorpus {

    /** Pipeline render DPI — must match production for feature transfer. */
    public static final int RENDER_DPI = 300;

    /** JPEG quality of the embedded "scan" like TestPdfGenerator.reads. */
    static final float SOURCE_JPEG_QUALITY = 0.95f;

    /** Source raster page size in points (72 DPI units). */
    private static final int SRC_W = 612;
    private static final int SRC_H = 792;
    private static final int MARGIN = 50;
    private static final int LINE_HEIGHT = 22;

    private static final int FONT_SIZE = 14;

    private SyntheticCorpus() {
    }

    /**
     * Writes the labeled corpus CSV for the given variant.  Rows:
     * doc,label,followed by the {@link FeatureExtractor} features for that
     * variant.
     */
    public static void generate(Path out, String variant, int docCount, long seed) throws IOException {
        List<String> featureNames = new ArrayList<>(List.of(FeatureExtractor.IMAGE_FEATURE_NAMES));
        if (QualityModel.VARIANT_TEXT_LAYER.equals(variant)) {
            featureNames.addAll(List.of(FeatureExtractor.TEXT_FEATURE_NAMES));
        }

        List<Dataset.Row> rows = new ArrayList<>();
        for (int d = 0; d < docCount; d++) {
            long rngSeed = seed ^ (d * 0x9E3779B97F4A7C15L);
            Page page = renderPage(rngSeed);
            double dpi = RENDER_DPI;
            int w = page.image.getWidth();
            int h = page.image.getHeight();
            int[] gray = ImageOps.toGrayscale(page.image);
            rows.add(featureRow(gray, w, h, page.blocks, variant, "doc" + d, 0, dpi));

            int[] sigmaBlur = {1, 3};
            for (double s : sigmaBlur) {
                int[] g = grayOf(gaussianBlur(page.image, s));
                rows.add(featureRow(g, w, h, page.blocks, variant, "doc" + d, s >= 2.5 ? 1 : 0, dpi));
            }
            int[] noiseLevels = {6, 24};
            for (int sigma : noiseLevels) {
                int[] g = grayOf(addNoise(page.image, sigma, new java.util.Random(rngSeed ^ 0x51a)));
                rows.add(featureRow(g, w, h, page.blocks, variant, "doc" + d, sigma >= 24 ? 1 : 0, dpi));
            }
            float[] qualities = {0.75f, 0.30f};
            for (float q : qualities) {
                int[] g = grayOf(recompressJpeg(page.image, q));
                rows.add(featureRow(g, w, h, page.blocks, variant, "doc" + d, q < 0.5f ? 1 : 0, dpi));
            }
            double[] scales = {0.75, 0.45};
            for (double s : scales) {
                int[] g = grayOf(downscale(page.image, s));
                rows.add(featureRow(g, w, h, page.blocks, variant, "doc" + d, s < 0.55 ? 1 : 0, dpi));
            }
            int[] breakFractions = {4, 14};
            for (int frac : breakFractions) {
                int[] g = grayOf(breakStrokes(page.image, page.blocks, frac, new java.util.Random(rngSeed ^ 0xb10b)));
                rows.add(featureRow(g, w, h, page.blocks, variant, "doc" + d, frac >= 14 ? 1 : 0, dpi));
            }
        }
        Dataset.writeCsv(out, rows, featureNames);
    }

    private static int[] grayOf(BufferedImage image) {
        return ImageOps.toGrayscale(image);
    }

    private static Dataset.Row featureRow(int[] gray, int w, int h, List<TextBlock> blocks,
                                          String variant, String doc, int label, double dpi) {
        double[] imageFeatures = FeatureExtractor.extractImage(gray, w, h, dpi);
        if (!QualityModel.VARIANT_TEXT_LAYER.equals(variant)) {
            return new Dataset.Row(doc, label, imageFeatures);
        }
        float[] mag = FeatureExtractor.gradientMagnitudes(gray, w, h);
        double[] textFeatures = FeatureExtractor.extractText(gray, mag, w, h, blocks);
        if (textFeatures == null) {
            return new Dataset.Row(doc, label, imageFeatures);
        }
        double[] features = new double[imageFeatures.length + textFeatures.length];
        System.arraycopy(imageFeatures, 0, features, 0, imageFeatures.length);
        System.arraycopy(textFeatures, 0, features, imageFeatures.length, textFeatures.length);
        return new Dataset.Row(doc, label, features);
    }

    // ──────────────────────────────────────────────────────────────────────
    // Page synthesis: replicate the production raster -> JPEG -> PDF -> render
    // chain so feature distributions match pipeline inputs.
    // ──────────────────────────────────────────────────────────────────────

    private record Page(BufferedImage image, List<TextBlock> blocks) {
    }

    private record WordBox(String word, Rectangle box) {
    }

    private static Page renderPage(long seed) throws IOException {
        // 1. Source "scan": AWT text on a 612x792 grayscale raster, JPEG'd.
        BufferedImage src = new BufferedImage(SRC_W, SRC_H, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D g = src.createGraphics();
        java.util.Random rng = new java.util.Random(seed ^ 0xbeef);
        java.util.Random confRng = new java.util.Random(seed ^ 0xc0ffee);
        List<WordBox> wordBoxes = new ArrayList<>();
        try {
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, SRC_W, SRC_H);
            g.setColor(Color.BLACK);
            Font font = new Font(Font.SANS_SERIF, Font.PLAIN, FONT_SIZE);
            g.setFont(font);
            FontMetrics fm = g.getFontMetrics();
            int y = MARGIN;
            while (y < SRC_H - MARGIN) {
                int x = MARGIN;
                while (x < SRC_W - MARGIN) {
                    String word = WORD_BANK[rng.nextInt(WORD_BANK.length)];
                    int wordWidth = fm.stringWidth(word);
                    if (x + wordWidth > SRC_W - MARGIN) break;
                    g.drawString(word, x, y);
                    wordBoxes.add(new WordBox(word,
                            new Rectangle(x, y - fm.getAscent(), wordWidth, fm.getAscent() + fm.getDescent())));
                    x += wordWidth + fm.stringWidth(" ");
                }
                y += LINE_HEIGHT;
            }
        } finally {
            g.dispose();
        }

        // 2. Embed the raster as a JPEG page (like the real scanned fixtures).
        File pdf = File.createTempFile("mrcpdf-corpus-", ".pdf");
        pdf.deleteOnExit();
        try (PDDocument doc = new PDDocument()) {
            PDPage pdPage = new PDPage(new PDRectangle(SRC_W, SRC_H));
            doc.addPage(pdPage);
            try (PDPageContentStream cs = new PDPageContentStream(doc, pdPage)) {
                PDImageXObject img = JPEGFactory.createFromImage(doc, src, SOURCE_JPEG_QUALITY);
                cs.drawImage(img, 0, 0, SRC_W, SRC_H);
            }
            doc.save(pdf);
        }

        // 3. Render the page at pipeline DPI (upscale + JPEG decode = the same
        //    soft statistics the production renderer sees).
        try (PDDocument pdfDoc = Loader.loadPDF(pdf)) {
            PDFRenderer renderer = new PDFRenderer(pdfDoc);
            BufferedImage page = renderer.renderImageWithDPI(0, RENDER_DPI);

            // Scale source boxes to the rendered image for the text features.
            double sx = page.getWidth() / (double) SRC_W;
            double sy = page.getHeight() / (double) SRC_H;
            List<TextBlock> blocks = new ArrayList<>();
            for (WordBox wb : wordBoxes) {
                Rectangle b = wb.box();
                int bw = Math.max(1, (int) Math.round(b.width * sx));
                int bh = Math.max(1, (int) Math.round(b.height * sy));
                Rectangle scaled = new Rectangle((int) Math.round(b.x * sx),
                        (int) Math.round(b.y * sy), bw, bh);
                double conf = 55 + confRng.nextInt(45); // 55..99, informative layer
                blocks.add(new TextBlock(wb.word(), scaled, conf));
            }
            return new Page(page, blocks);
        } finally {
            pdf.delete();
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // Degradations (applied to the rendered page image)
    // ──────────────────────────────────────────────────────────────────────

    private static BufferedImage gaussianBlur(BufferedImage src, double sigma) {
        int radius = Math.max(1, (int) Math.ceil(sigma * 3));
        int size = 2 * radius + 1;
        float[] kernel = new float[size * size];
        double sum = 0;
        for (int j = -radius; j <= radius; j++) {
            for (int i = -radius; i <= radius; i++) {
                double v = Math.exp(-(i * i + j * j) / (2 * sigma * sigma));
                kernel[(j + radius) * size + (i + radius)] = (float) v;
                sum += v;
            }
        }
        for (int i = 0; i < kernel.length; i++) kernel[i] /= sum;
        BufferedImage out = new BufferedImage(src.getWidth(), src.getHeight(), src.getType());
        new ConvolveOp(new Kernel(size, size, kernel), ConvolveOp.EDGE_NO_OP, null).filter(src, out);
        return out;
    }

    private static BufferedImage addNoise(BufferedImage src, double sigma, java.util.Random rng) {
        int w = src.getWidth();
        int h = src.getHeight();
        BufferedImage out = new BufferedImage(w, h, src.getType());
        int[] gray = ImageOps.toGrayscale(src);
        for (int i = 0; i < gray.length; i++) {
            gray[i] = clamp255(gray[i] + (int) Math.round(rng.nextGaussian() * sigma));
        }
        out.setRGB(0, 0, w, h, intsToRgb(gray), 0, w);
        return out;
    }

    private static int[] intsToRgb(int[] gray) {
        int[] rgb = new int[gray.length];
        for (int i = 0; i < gray.length; i++) {
            int v = gray[i];
            rgb[i] = (v << 16) | (v << 8) | v;
        }
        return rgb;
    }

    private static BufferedImage recompressJpeg(BufferedImage src, float quality) {
        try {
            ImageWriter writer = ImageIO.getImageWritersByFormatName("jpg").next();
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(quality);
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            try (ImageOutputStream ios = new MemoryCacheImageOutputStream(bos)) {
                writer.setOutput(ios);
                writer.write(null, new IIOImage(src, null, null), param);
            }
            writer.dispose();
            return ImageIO.read(new java.io.ByteArrayInputStream(bos.toByteArray()));
        } catch (IOException e) {
            throw new IllegalStateException("JPEG degradation failed", e);
        }
    }

    private static BufferedImage downscale(BufferedImage src, double scale) {
        int w = src.getWidth();
        int h = src.getHeight();
        int sw = Math.max(1, (int) (w * scale));
        int sh = Math.max(1, (int) (h * scale));
        BufferedImage small = new BufferedImage(sw, sh, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = small.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(src, 0, 0, sw, sh, null);
        } finally {
            g.dispose();
        }
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g2 = out.createGraphics();
        try {
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            g2.drawImage(small, 0, 0, w, h, null);
        } finally {
            g2.dispose();
        }
        return out;
    }

    private static BufferedImage breakStrokes(BufferedImage src, List<TextBlock> blocks,
                                              int percent, java.util.Random rng) {
        BufferedImage out = new BufferedImage(src.getWidth(), src.getHeight(), src.getType());
        Graphics2D g = out.createGraphics();
        try {
            g.drawImage(src, 0, 0, null);
            g.setColor(Color.WHITE);
            int target = Math.max(1, (blocks.size() * percent) / 100);
            for (int k = 0; k < target; k++) {
                Rectangle b = blocks.get(rng.nextInt(blocks.size())).getBbox();
                int breakW = Math.max(2, b.width / 3);
                int bx = b.x + rng.nextInt(Math.max(1, b.width - breakW));
                int by = b.y + b.height / 2 + rng.nextInt(Math.max(1, b.height / 2));
                g.fillRect(bx, by, breakW, 1);
            }
        } finally {
            g.dispose();
        }
        return out;
    }

    private static int clamp255(int v) {
        return Math.max(0, Math.min(255, v));
    }

    private static final String[] WORD_BANK = {
            "quality", "model", "gate", "degradation", "threshold", "fidelity",
            "compression", "artifact", "blur", "noise", "jpeg", "downscale",
            "broken", "stroke", "page", "render", "background", "foreground",
            "contrast", "uniformity", "separation", "energy", "laplacian",
            "coverage", "sharpness", "informed", "sampling", "undersampling",
            "registration", "halo", "edge", "width", "fragment", "glyph"
    };
}