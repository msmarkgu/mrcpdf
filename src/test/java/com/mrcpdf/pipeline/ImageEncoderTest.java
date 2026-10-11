package com.mrcpdf.pipeline;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.Iterator;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriteParam;
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
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(jpeg))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
            assertTrue(readers.hasNext(), "jpeg should be readable");
            ImageReader reader = readers.next();
            try {
                reader.setInput(in);
                ImageWriteParam param = new javax.imageio.plugins.jpeg.JPEGImageWriteParam(null);
                ImageTypeSpecifier type = ImageTypeSpecifier.createFromRenderedImage(colorfulImage());
                IIOMetadata meta = reader.getImageMetadata(0);
                Element tree = (Element) meta.getAsTree("javax_imageio_jpeg_image_1.0");
                Element markerSeq = (Element) tree.getElementsByTagName("markerSequence").item(0);
                Element sof = (Element) markerSeq.getElementsByTagName("sof").item(0);
                NodeList comps = sof.getElementsByTagName("componentSpec");
                assertTrue(comps.getLength() >= 3, "expected at least 3 JPEG components, got " + comps.getLength());
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
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, 300, 200);
        g.setColor(Color.BLACK);
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