package com.mrcpdf.quality;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;

import com.mrcpdf.model.TextBlock;

/**
 * Phase-1 tests for {@link TextQualityScorer}.
 *
 * Verifies the geometric-mean composite discriminates clean pages from blurred,
 * noisy, broken-stroke and blank pages; that both text-layer and whole-page
 * (fallback) modes order clean above degraded; and that text-layer mode only
 * engages when the layer carries a real (non-100.0) OCR confidence signal.
 */
class TextQualityScorerTest {

    private static final int W = 480;
    private static final int H = 640;
    private static final String[] WORDS = {"Alpha", "bravo", "Charlie", "delta", "Echo"};
    private static final int[] BASELINE_Y = {100, 180, 260, 340, 420};
    private static final double[] CONFS = {96.5, 97.8, 95.9, 98.2, 96.1};

    private final TextQualityScorer scorer = new TextQualityScorer(300);

    // ── Images ─────────────────────────────────────────────────────────────

    private static BufferedImage blankImage() {
        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(new Color(248, 248, 248));
        g.fillRect(0, 0, W, H);
        g.dispose();
        return img;
    }

    /** Clean synthetic page: dark text on near-white background, crisp edges. */
    private static BufferedImage cleanImage() {
        BufferedImage img = blankImage();
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
        g.setFont(new Font("SansSerif", Font.PLAIN, 28));
        g.setColor(new Color(25, 25, 25));
        for (int i = 0; i < WORDS.length; i++) {
            g.drawString(WORDS[i], TEXT_X, BASELINE_Y[i]);
        }
        g.dispose();
        return img;
    }

    private static BufferedImage blurredImage(BufferedImage src, float sigma) {
        int radius = Math.max(1, (int) Math.ceil(2 * sigma));
        float[] kernel = new float[2 * radius + 1];
        float sum = 0;
        for (int i = -radius; i <= radius; i++) {
            float v = (float) Math.exp(-(i * i) / (2 * sigma * sigma));
            kernel[i + radius] = v;
            sum += v;
        }
        for (int i = 0; i < kernel.length; i++) kernel[i] /= sum;

        int w = src.getWidth(), h = src.getHeight();
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        int[] srcRow = new int[w];
        int[] tmpRow = new int[w];
        int[] dst = new int[w * h];
        for (int y = 0; y < h; y++) {
            src.getRGB(0, y, w, 1, srcRow, 0, w);
            for (int x = 0; x < w; x++) {
                float acc = 0;
                for (int k = -radius; k <= radius; k++) {
                    int sx = Math.max(0, Math.min(w - 1, x + k));
                    acc += kernel[k + radius] * (srcRow[sx] & 0xFF);
                }
                tmpRow[x] = (int) acc;
            }
            for (int x = 0; x < w; x++) dst[y * w + x] = tmpRow[x];
        }
        // vertical pass (gray already separated)
        int[] result = new int[w * h];
        for (int x = 0; x < w; x++) {
            for (int y = 0; y < h; y++) {
                float acc = 0;
                for (int k = -radius; k <= radius; k++) {
                    int sy = Math.max(0, Math.min(h - 1, y + k));
                    acc += kernel[k + radius] * dst[sy * w + x];
                }
                result[y * w + x] = (int) acc;
            }
        }
        int[] packed = new int[w * h];
        for (int i = 0; i < packed.length; i++) {
            int v = result[i];
            packed[i] = 0xFF000000 | (v << 16) | (v << 8) | v;
        }
        out.setRGB(0, 0, w, h, packed, 0, w);
        return out;
    }

    private static BufferedImage noisyImage(BufferedImage src, int magnitude) {
        int w = src.getWidth(), h = src.getHeight();
        Random rnd = new Random(42);
        int[] rgb = new int[w * h];
        src.getRGB(0, 0, w, h, rgb, 0, w);
        for (int i = 0; i < rgb.length; i++) {
            int v = (rgb[i] & 0xFF) + (rnd.nextInt(2 * magnitude + 1) - magnitude);
            v = Math.max(0, Math.min(255, v));
            rgb[i] = 0xFF000000 | (v << 16) | (v << 8) | v;
        }
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        out.setRGB(0, 0, w, h, rgb, 0, w);
        return out;
    }

    /** Cuts vertical white bars through every word to fragment the glyphs. */
    private static BufferedImage brokenImage(BufferedImage src, List<TextBlock> blocks) {
        BufferedImage out = copyOf(src);
        Graphics2D g = out.createGraphics();
        g.setColor(Color.WHITE);
        for (TextBlock tb : blocks) {
            Rectangle b = tb.getBbox();
            for (int i = 1; i <= 3; i++) {
                int x = b.x + (b.width * i) / 4;
                g.fillRect(x, b.y, 3, b.height);
            }
        }
        g.dispose();
        return out;
    }

    private static BufferedImage copyOf(BufferedImage src) {
        BufferedImage out = new BufferedImage(src.getWidth(), src.getHeight(), src.getType());
        Graphics2D g = out.createGraphics();
        g.drawImage(src, 0, 0, null);
        g.dispose();
        return out;
    }

    // ── Text layer ─────────────────────────────────────────────────────────

    private static final int TEXT_X = 30;

    private static List<TextBlock> textLayer(double... confidences) {
        BufferedImage probe = blankImage();
        Graphics2D g = probe.createGraphics();
        g.setFont(new Font("SansSerif", Font.PLAIN, 28));
        FontMetrics fm = g.getFontMetrics();
        List<TextBlock> blocks = new ArrayList<>();
        for (int i = 0; i < WORDS.length; i++) {
            Rectangle b = new Rectangle(TEXT_X, BASELINE_Y[i] - fm.getAscent(),
                    fm.stringWidth(WORDS[i]), fm.getAscent() + fm.getDescent());
            b.grow(4, 4);
            double conf = confidences.length > 0 ? confidences[i] : CONFS[i];
            blocks.add(new TextBlock(WORDS[i], b, conf));
        }
        g.dispose();
        return blocks;
    }

    // ── Tests ──────────────────────────────────────────────────────────────

    @Test
    void cleanText_scoresAboveThresholdAndOutranksBlur() {
        BufferedImage clean = cleanImage();
        BufferedImage blurred = blurredImage(clean, 2.5f);
        List<TextBlock> blocks = textLayer();

        TextQualityScore qClean = scorer.compute(clean, blocks);
        TextQualityScore qBlur = scorer.compute(blurred, blocks);

        assertTrue(qClean.getComposite() > TextQualityScorer.DEFAULT_GATE_THRESHOLD,
                "clean text should clear the default gate threshold, got " + qClean.getComposite());
        assertTrue(qBlur.getComposite() < qClean.getComposite(),
                "blurred text should score below clean (clean=" + qClean.getComposite()
                        + ", blur=" + qBlur.getComposite() + ")");
    }

    @Test
    void noisyBackground_scoresBelowClean() {
        BufferedImage clean = cleanImage();
        BufferedImage noisy = noisyImage(clean, 25);
        List<TextBlock> blocks = textLayer();

        double qClean = scorer.compute(clean, blocks).getComposite();
        double qNoisy = scorer.compute(noisy, blocks).getComposite();

        assertTrue(qNoisy < qClean,
                "noisy background should score below clean (clean=" + qClean
                        + ", noisy=" + qNoisy + ")");
    }

    @Test
    void brokenStrokes_scoresBelowClean() {
        BufferedImage clean = cleanImage();
        List<TextBlock> blocks = textLayer();
        BufferedImage broken = brokenImage(clean, blocks);

        TextQualityScore qClean = scorer.compute(clean, blocks);
        TextQualityScore qBroken = scorer.compute(broken, blocks);

        assertTrue(qBroken.getStructure() < qClean.getStructure(),
                "broken glyphs should lower the structure group (clean=" + qClean.getStructure()
                        + ", broken=" + qBroken.getStructure() + ")");
        assertTrue(qBroken.getComposite() < qClean.getComposite() - 0.02,
                "fragmented text should score clearly below clean (clean=" + qClean.getComposite()
                        + ", broken=" + qBroken.getComposite() + ")");
    }

    @Test
    void blankPage_scoresWellBelowThreshold() {
        TextQualityScore q = scorer.compute(blankImage(), new ArrayList<>());
        assertTrue(q.getComposite() < 0.25,
                "blank page should score far below the threshold, got " + q.getComposite());
        assertTrue(q.isBelow(TextQualityScorer.DEFAULT_GATE_THRESHOLD));
    }

    @Test
    void fallbackWithoutTextLayer_ordersCleanOverBlur() {
        BufferedImage clean = cleanImage();
        BufferedImage blurred = blurredImage(clean, 2.5f);

        TextQualityScore qClean = scorer.compute(clean, null);
        TextQualityScore qBlur = scorer.compute(blurred, null);

        assertFalse(qClean.isUsesTextLayer(), "no text layer should use whole-page mode");
        assertFalse(qBlur.isUsesTextLayer());
        assertTrue(qClean.getComposite() > qBlur.getComposite(),
                "whole-page mode should still rank clean above blurred (clean=" + qClean.getComposite()
                        + ", blur=" + qBlur.getComposite() + ")");
        assertTrue(qClean.getComposite() > 0.3, "clean page should score reasonably in fallback");
    }

    @Test
    void textLayerDetection_requiresInformedConfidence() {
        BufferedImage clean = cleanImage();

        List<TextBlock> all100 = textLayer(100.0, 100.0, 100.0, 100.0, 100.0);
        assertFalse(scorer.compute(clean, all100).isUsesTextLayer(),
                "PDFBox-style constant 100.0 confidences are not a real OCR layer");

        List<TextBlock> varied = textLayer();
        assertTrue(scorer.compute(clean, varied).isUsesTextLayer(),
                "varying confidences indicate a genuine OCR layer");
    }

    @Test
    void gate_skipsBlankKeepsClean() {
        BufferedImage clean = cleanImage();
        TextQualityScore qClean = scorer.compute(clean, textLayer());
        TextQualityScore qBlank = scorer.compute(blankImage(), new ArrayList<>());

        assertFalse(qClean.isBelow(TextQualityScorer.DEFAULT_GATE_THRESHOLD),
                "clean page should not be routed to the fidelity path");
        assertTrue(qBlank.isBelow(TextQualityScorer.DEFAULT_GATE_THRESHOLD),
                "blank page should be routed to the fidelity path");
    }

    @Test
    void bothModes_shareTheSameOrdering() {
        BufferedImage clean = cleanImage();
        BufferedImage blurred = blurredImage(clean, 3f);
        List<TextBlock> blocks = textLayer();

        double glyphDiff = scorer.compute(clean, blocks).getComposite()
                - scorer.compute(blurred, blocks).getComposite();
        double fallbackDiff = scorer.compute(clean, null).getComposite()
                - scorer.compute(blurred, null).getComposite();

        assertTrue(glyphDiff > 0, "glyph mode: clean must outrank blur");
        assertTrue(fallbackDiff > 0, "fallback mode: clean must outrank blur");
    }
}