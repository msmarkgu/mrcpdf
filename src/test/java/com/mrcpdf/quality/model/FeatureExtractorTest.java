package com.mrcpdf.quality.model;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.awt.image.ConvolveOp;
import java.awt.image.Kernel;

import org.junit.jupiter.api.Test;

import java.util.List;

/**
 * Phase-2 tests for {@link FeatureExtractor}.
 *
 * Verifies the 11-image feature vector length, that clean vs blurred pages
 * order every discriminative feature the expected way, that a blank page has
 * near-zero separability/ink coverage, and that the image features never
 * produce NaN/±Infinity.
 */
class FeatureExtractorTest {

    private static final int W = 480;
    private static final int H = 640;
    private static final String[] WORDS = {"Alpha", "bravo", "Charlie", "delta", "Echo"};
    private static final int[] BASELINE_Y = {100, 180, 260, 340, 420};

    private static BufferedImage blankImage() {
        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(new Color(248, 248, 248));
        g.fillRect(0, 0, W, H);
        g.dispose();
        return img;
    }

    private static BufferedImage cleanImage() {
        BufferedImage img = blankImage();
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
        g.setFont(new Font("SansSerif", Font.PLAIN, 28));
        g.setColor(new Color(25, 25, 25));
        for (int i = 0; i < WORDS.length; i++) g.drawString(WORDS[i], 40, BASELINE_Y[i]);
        g.dispose();
        return img;
    }

    private static BufferedImage blurredImage(BufferedImage src, float sigma) {
        int radius = Math.max(1, (int) Math.ceil(2 * sigma));
        int size = 2 * radius + 1;
        float[] kernel = new float[size * size];
        double sum = 0;
        for (int j = -radius; j <= radius; j++) {
            for (int i = -radius; i <= radius; i++) {
                float v = (float) Math.exp(-(i * i + j * j) / (2 * sigma * sigma));
                kernel[(j + radius) * size + (i + radius)] = v;
                sum += v;
            }
        }
        for (int i = 0; i < kernel.length; i++) kernel[i] /= sum;
        BufferedImage out = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        new ConvolveOp(new Kernel(size, size, kernel), ConvolveOp.EDGE_NO_OP, null).filter(src, out);
        return out;
    }

    private static double[] imageFeatures(BufferedImage img) {
        return FeatureExtractor.extractImage(img, 300);
    }

    @Test
    void imageFeatureVectorHasElevenValues() {
        assertEquals(11, imageFeatures(cleanImage()).length);
        assertEquals(List.of(FeatureExtractor.IMAGE_FEATURE_NAMES).size(), 11);
    }

    @Test
    void cleanScoresBlurStrictlyInEveryDiscriminativeFeature() {
        double[] clean = imageFeatures(cleanImage());
        double[] blur = imageFeatures(blurredImage(cleanImage(), 1.8f));

        assertTrue(clean[0] < blur[0], "blurSigmaPx should rise with blur");
        assertTrue(clean[2] > blur[2], "hiFreqEnergy should drop with blur");
        assertTrue(clean[3] > blur[3], "laplacianVar should drop with blur");
        assertTrue(clean[4] > blur[4], "otsuSeparability should drop with blur");
        assertTrue(clean[5] > blur[5], "michelsonContrast should drop with blur");
    }

    @Test
    void blankPageHasZeroSeparabilityAndInkCoverage() {
        double[] blank = imageFeatures(blankImage());
        assertEquals(0.0, blank[4], 1e-6, "otsuSeparability");
        assertEquals(0.0, blank[10], 1e-6, "inkCoverage");
        assertTrue(blank[2] < 1.0, "blank looks low-energy");
    }

    @Test
    void featuresAreFiniteAndInSaneRanges() {
        double[] f = imageFeatures(cleanImage());
        for (double v : f) {
            assertTrue(Double.isFinite(v), "feature must be finite: " + v);
            assertTrue(v >= 0, "feature must be non-negative: " + v);
        }
        assertTrue(f[9] >= 0, "snr is log1p so non-negative");
        assertTrue(f[10] >= 0 && f[10] <= 1, "inkCoverage is a fraction");
        assertTrue(f[7] >= 0, "bgUniformity non-negative");
    }
}