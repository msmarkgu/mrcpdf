package com.mrcpdf.quality.model;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.FontMetrics;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.awt.image.ConvolveOp;
import java.awt.image.Kernel;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;

import com.mrcpdf.model.TextBlock;
import com.mrcpdf.util.ImageOps;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

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

    /**
     * Regression: {@code strokeWidthEstimate} used to seed its distance transform
     * on the foreground and then read the result back at foreground pixels, so
     * every sampled distance was 0, the stroke width was always 0, and
     * {@code extractText} always returned null.  The 3 conditional text features
     * were therefore dead in every corpus built before the fix, and the
     * text-layer rows silently fell back to the 11 image features.
     */
    @Test
    void extractTextReturnsThreeValuesForRealGlyphBoxes() {
        BufferedImage img = cleanImage();
        int[] gray = ImageOps.toGrayscale(img);
        float[] mag = FeatureExtractor.gradientMagnitudes(gray, W, H);

        List<TextBlock> blocks = new ArrayList<>();
        FontMetrics fm = img.createGraphics().getFontMetrics(new Font("SansSerif", Font.PLAIN, 28));
        for (int i = 0; i < WORDS.length; i++) {
            int x = 40;
            int y = BASELINE_Y[i] - fm.getAscent();
            blocks.add(new TextBlock(WORDS[i],
                    new Rectangle(x, y, fm.stringWidth(WORDS[i]), fm.getHeight()),
                    90.0));
        }

        double[] text = FeatureExtractor.extractText(gray, mag, W, H, blocks);
        assertNotNull(text, "extractText must not return null for real glyph boxes");
        assertEquals(3, text.length);
        for (int i = 0; i < text.length; i++) {
            assertTrue(Double.isFinite(text[i]), "text feature must be finite: " + text[i]);
            assertTrue(text[i] >= 0, "text feature must be non-negative: " + text[i]);
        }
        // strokeWidthCV is a coefficient of variation, so it is scale-invariant
        // and legitimately 0 when every glyph is identical; what must not happen
        // is extractText bailing out and handing back fewer than 3 features.
        assertEquals(FeatureExtractor.TEXT_FEATURE_NAMES.length, text.length);
    }

    /**
     * edgeAlign measures edge energy leaking outside the glyph boxes, so shifting
     * the boxes off the ink must push it up.  This also proves the boxes are
     * really being used.
     */
    /**
     * The bg-uniformity feature must move in the physical direction it is named
     * for: sensor noise raises the median-filter residual and so must raise
     * {@code bgUniformity} (and {@code noiseSigma}) while lowering {@code snr}.
     * Re-bonding these to the wrong polarity would silently invert the gate.
     */
    @Test
    void bgUniformity_risesWithBackgroundNoise() {
        double[] clean = imageFeatures(cleanImage());
        double[] noisy = imageFeatures(noisyImage(cleanImage(), 6));

        assertTrue(noisy[7] > clean[7],
                "noise must raise bgUniformity (clean=" + clean[7] + ", noisy=" + noisy[7] + ")");
        assertTrue(noisy[8] > clean[8],
                "noise must raise noiseSigma (clean=" + clean[8] + ", noisy=" + noisy[8] + ")");
        assertTrue(noisy[9] < clean[9],
                "noise must lower snr (clean=" + clean[9] + ", noisy=" + noisy[9] + ")");
    }

    /**
     * Polarity guard for the shipped screen artifact ({@code loadScreenDefault}):
     * {code loadScreen} negates the good-head weights, so the loaded weights must
     * carry the skip-side meaning -- a dirtier background, lower high-frequency
     * energy and a denser ink coverage all strengthen the "skip MRC" decision.
     * Re-trained screens that flipped these signs would reverse the gate.
     * (noiseSigma and snr are deliberately not asserted: the multivariate fit
     * uses them as collinear suppressors, exactly as the legacy-model test notes.)
     */
    @Test
    void shippedScreen_bgUniformityPolarityPullsTowardSkip() {
        QualityModel screen = QualityModel.loadScreenDefault();
        Assumptions.assumeTrue(screen != null, "quality-image-screen.json absent");

        assertTrue(screen.featureWeight("bgUniformity") > 0,
                "bgUniformity (dirtier = higher) must push the skip side up, got "
                        + screen.featureWeight("bgUniformity"));
        assertTrue(screen.featureWeight("hiFreqEnergy") < 0,
                "less high-frequency energy must push the skip side up, got "
                        + screen.featureWeight("hiFreqEnergy"));
        assertTrue(screen.featureWeight("inkCoverage") > 0,
                "denser ink coverage must push the skip side up, got "
                        + screen.featureWeight("inkCoverage"));
    }

    @Test
    void edgeAlignRisesWhenGlyphBoxesAreMisaligned() {
        BufferedImage img = cleanImage();
        int[] gray = ImageOps.toGrayscale(img);
        float[] mag = FeatureExtractor.gradientMagnitudes(gray, W, H);
        Font font = new Font("SansSerif", Font.PLAIN, 28);
        FontMetrics fm = img.createGraphics().getFontMetrics(font);

        List<TextBlock> aligned = new ArrayList<>();
        List<TextBlock> shifted = new ArrayList<>();
        for (int i = 0; i < WORDS.length; i++) {
            int x = 40;
            int y = BASELINE_Y[i] - fm.getAscent();
            int wBox = fm.stringWidth(WORDS[i]);
            int hBox = fm.getHeight();
            aligned.add(new TextBlock(WORDS[i], new Rectangle(x, y, wBox, hBox), 90.0));
            shifted.add(new TextBlock(WORDS[i], new Rectangle(x + 14, y + 7, wBox, hBox), 90.0));
        }

        double[] a = FeatureExtractor.extractText(gray, mag, W, H, aligned);
        double[] s = FeatureExtractor.extractText(gray, mag, W, H, shifted);
        assertNotNull(a);
        assertNotNull(s);
        int edgeAlignIdx = 2;
        assertTrue(s[edgeAlignIdx] > a[edgeAlignIdx],
                "edgeAlign should rise with misalignment: aligned=" + a[edgeAlignIdx]
                        + " shifted=" + s[edgeAlignIdx]);
    }
}
