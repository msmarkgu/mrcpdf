package com.mrcpdf.pipeline;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.Iterator;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.stream.ImageInputStream;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * Tests for the JPEG encoding layer of the pipeline.
 *
 * A1 regression: {@code encodeForegroundColor} promises 4:4:4 chroma for the
 * color foreground plane (the soft-mask layer that carries colored text). The
 * metadata rewrite that was meant to set per-component sampling factors was
 * dead code (it iterated the marker sequence and always threw), so the
 * foreground was silently encoded at the JDK default 4:2:0 and colored text
 * edges were chroma-subsampled.
 */
class ImageEncoderTest {

    private static BufferedImage colorfulImage() {
        BufferedImage img = new BufferedImage(128, 96, BufferedImage.TYPE_3BYTE_BGR);
        Graphics2D g = img.createGraphics();
        // Strong saturated color boundaries so chroma subsampling would visibly
        // bleed if it were applied.
        g.setColor(new Color(200, 30, 30));
        g.fillRect(0, 0, 64, 96);
        g.setColor(new Color(30, 30, 200));
        g.fillRect(64, 0, 64, 96);
        g.dispose();
        return img;
    }

    private static int[] samplingFactors(byte[] jpeg) throws Exception {
        int[] factors = allSamplingFactors(jpeg);
        assertTrue(factors.length >= 6,
                "expected at least 3 JPEG components, got " + factors.length / 2);
        return factors;
    }

    private static int[] allSamplingFactors(byte[] jpeg) throws Exception {
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(jpeg))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
            assertTrue(readers.hasNext(), "jpeg should be readable");
            ImageReader reader = readers.next();
            try {
                reader.setInput(in);
                IIOMetadata meta = reader.getImageMetadata(0);
                Element tree = (Element) meta.getAsTree("javax_imageio_jpeg_image_1.0");
                Element markerSeq = (Element) tree.getElementsByTagName("markerSequence").item(0);
                Element sof = (Element) markerSeq.getElementsByTagName("sof").item(0);
                NodeList comps = sof.getElementsByTagName("componentSpec");
                int[] factors = new int[comps.getLength() * 2];
                for (int i = 0; i < comps.getLength(); i++) {
                    Element comp = (Element) comps.item(i);
                    factors[i * 2] = Integer.parseInt(comp.getAttribute("HsamplingFactor"));
                    factors[i * 2 + 1] = Integer.parseInt(comp.getAttribute("VsamplingFactor"));
                }
                return factors;
            } finally {
                reader.dispose();
            }
        }
    }

    private static BufferedImage grayscaleImage() {
        BufferedImage img = new BufferedImage(200, 160, BufferedImage.TYPE_3BYTE_BGR);
        Graphics2D g = img.createGraphics();
        g.setColor(new Color(180, 180, 180));
        g.fillRect(0, 0, 200, 160);
        g.setColor(new Color(90, 90, 90));
        g.fillRect(0, 0, 100, 160);
        g.dispose();
        return img;
    }

    private static int graySample(BufferedImage img, int x, int y) {
        // Read the stored gray sample directly: a PDF viewer shows DeviceGray
        // samples as-is, whereas getRGB would re-apply the linear-gray color
        // model and report a value the file does not contain.
        return img.getRaster().getSample(x, y, 0);
    }

    @Test
    void foregroundColor_encodesAt444Chroma() throws Exception {
        int[] factors = samplingFactors(ImageEncoder.encodeForegroundColor(colorfulImage(), 0.8f, 0.5));
        // Every component (luma and chroma) sampled 1x1 == 4:4:4. The buggy code
        // left the JDK default (luma 2x2, chroma 1x1) in place.
        for (int i = 0; i < factors.length; i++) {
            assertEquals(1, factors[i],
                    "foreground color plane must be 4:4:4; actual sampling factors: "
                            + java.util.Arrays.toString(factors));
        }
    }

    @Test
    void foregroundColor_atFullScale_isAlso444() throws Exception {
        int[] factors = samplingFactors(ImageEncoder.encodeForegroundColor(colorfulImage(), 0.8f, 1.0));
        for (int i = 0; i < factors.length; i++) {
            assertEquals(1, factors[i]);
        }
    }

    /**
     * A grayscale page -- the common case for a black-and-white scan rendered to
     * RGB -- must not be carried as a 3-component JPEG. The channels are
     * identical, so the two chroma components are pure waste; a 1-component
     * background JPEG is roughly a third smaller for the same luma.
     */
    @Test
    void background_grayscaleSource_encodesAsSingleComponentJpeg() throws Exception {
        BufferedImage gray = grayscaleImage();
        byte[] jpeg = ImageEncoder.encodeBackground(gray, 0.9f, true, 0.5f, 0f);

        int[] factors = allSamplingFactors(jpeg);
        assertEquals(2, factors.length,
                "a grayscale background must be a 1-component JPEG, factors="
                        + java.util.Arrays.toString(factors));
        assertEquals(1, factors[0], "the single gray component must not be subsampled");
        assertEquals(1, factors[1]);

        BufferedImage decoded = decode(jpeg);
        assertEquals(1, decoded.getColorModel().getNumComponents(),
                "the decoded JPEG must report one component");
        assertEquals(180, graySample(decoded, 75, 20), 12,
                "light background must survive the gray conversion");
        assertEquals(90, graySample(decoded, 25, 40), 12,
                "dark region must survive the gray conversion");
    }

    /**
     * The counterpart: a genuinely colored background must keep its color. The
     * channel scan that detects grayscale must be conservative -- any pixel that
     * differs across channels keeps the full 3-component path.
     */
    @Test
    void background_colorSource_staysThreeComponentJpeg() throws Exception {
        byte[] jpeg = ImageEncoder.encodeBackground(colorfulImage(), 0.9f, true, 0.5f, 0f);

        int[] factors = allSamplingFactors(jpeg);
        assertEquals(6, factors.length,
                "a color background keeps all 3 components, factors="
                        + java.util.Arrays.toString(factors));
        assertEquals(2, factors[0], "luma keeps its 4:2:0 subsampling");
        assertEquals(2, factors[1]);
    }

    /**
     * The background downscale must average each destination cell, not sample a
     * 2x2 neighbourhood from it.
     *
     * <p>On 50% black/white noise the distinction is stark: a 3x3 box average
     * reduces the standard deviation from ~128 to ~128/3 = 43, whereas bilinear
     * interpolation (the previous path) keeps the high-frequency energy and
     * aliases it down into the background.
     */
    @Test
    void downscale_averagesTheSourceCell() {
        int n = 600;
        java.util.Random rng = new java.util.Random(1234567L);
        BufferedImage noise = new BufferedImage(n, n, BufferedImage.TYPE_3BYTE_BGR);
        for (int y = 0; y < n; y++) {
            for (int x = 0; x < n; x++) {
                int v = rng.nextBoolean() ? 0 : 255;
                noise.setRGB(x, y, (v << 16) | (v << 8) | v);
            }
        }
        assertEquals(127.5, stdGray(noise), 6.0,
                "sanity: the input must be high-variance noise");

        BufferedImage scaled = ImageEncoder.downscaleAreaAverage(noise, 200, 200);
        assertEquals(200, scaled.getWidth());
        assertEquals(200, scaled.getHeight());
        assertTrue(stdGray(scaled) < 55.0,
                "a box filter must average the whole 3x3 cell, got std="
                        + stdGray(scaled));
    }

    private static double stdGray(BufferedImage img) {
        long sum = 0, sumSq = 0;
        int n = 0;
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                int v = img.getRGB(x, y) & 0xFF;
                sum += v;
                sumSq += (long) v * v;
                n++;
            }
        }
        double mean = (double) sum / n;
        return Math.sqrt((double) sumSq / n - mean * mean);
    }

    /**
     * A2 regression: encodeBackground scales the smoothing sigma the wrong way.
     * The blur runs on the downscaled image, so a sigma meant to be measured at
     * full resolution must be multiplied by bgScale, not divided by it; dividing
     * over-blurs by ~1/bgScale^2 and the "mild smoothing" the settings promise
     * turns into a heavy soften.
     */
    @Test
    void backgroundSigma_isRescaledByScaleNotDivided() throws Exception {
        BufferedImage bars = new BufferedImage(300, 200, BufferedImage.TYPE_3BYTE_BGR);
        Graphics2D g = bars.createGraphics();
        // Tinted, but with the SAME tint on the bars and the background: the
        // image is non-neutral (so it stays on the 3-component path this RGB
        // comparison is calibrated against) yet has no chroma detail, so 4:2:0
        // subsampling adds no error the sigma check could be blamed for.
        g.setColor(new Color(235, 235, 255));
        g.fillRect(0, 0, 300, 200);
        g.setColor(new Color(5, 5, 25));
        for (int x = 0; x < 300; x += 8) g.fillRect(x, 0, 4, 200); // 4px bars
        g.dispose();

        BufferedImage base = decode(ImageEncoder.encodeBackground(bars, 0.95f, true, 0.5f, 0f));
        BufferedImage blurred = decode(ImageEncoder.encodeBackground(bars, 0.95f, true, 0.5f, 0.8f));

        // Correct: sigma in the downscaled domain is 0.8 * 0.5 = 0.4.
        BufferedImage expectedCorrect = PDFAssembler.gaussianBlur(base, 0.4f);
        // Buggy: sigma / bgScale = 1.6 in the downscaled domain.
        BufferedImage expectedWrong = PDFAssembler.gaussianBlur(base, 1.6f);

        double rmsCorrect = rms(blurred, expectedCorrect);
        double rmsWrong = rms(blurred, expectedWrong);
        assertTrue(rmsCorrect < rmsWrong,
                "blur must track bgSmoothSigma*bgScale, not sigma/bgScale (rms@0.4=" + rmsCorrect
                        + ", rms@1.6=" + rmsWrong + ")");
        assertTrue(rmsCorrect < 3.0,
                "encode must match a gaussian blur at the corrected sigma (rms=" + rmsCorrect + ")");
    }

    private static BufferedImage decode(byte[] jpeg) throws Exception {
        return ImageIO.read(new ByteArrayInputStream(jpeg));
    }

    private static double rms(BufferedImage a, BufferedImage b) {
        int w = Math.min(a.getWidth(), b.getWidth());
        int h = Math.min(a.getHeight(), b.getHeight());
        double s = 0;
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++) {
                double d = ((a.getRGB(x, y) & 0xFF) - (b.getRGB(x, y) & 0xFF));
                s += d * d;
            }
        return Math.sqrt(s / (w * h));
    }
}