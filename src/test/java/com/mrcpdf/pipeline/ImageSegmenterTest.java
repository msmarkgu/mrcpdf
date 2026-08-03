package com.mrcpdf.pipeline;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.mrcpdf.model.SegmentedImage;

    /**
     * Tests for ImageSegmenter (pure-Java binarization pipeline).
     *
     * Verifies that segment() returns non-null outputs, preserves dimensions,
     * produces a foreground mask, correctly identifies text vs blank pages,
     * and inpaints foreground pixels in the cleaned background.
     */
class ImageSegmenterTest {

    static PageExtractor extractor;
    static ImageSegmenter segmenter;

    @BeforeAll
    static void setup() {
        extractor = new PageExtractor(150f);
        segmenter = new ImageSegmenter();
    }

    @Test
    void segment_blankPage_returnsNonNull() throws IOException {
        var pages = extractor.extractPages(new File("tests/blank.pdf"));
        SegmentedImage result = segmenter.segment(pages.get(0));
        assertNotNull(result);
        assertNotNull(result.getForegroundMask());
        assertNotNull(result.getCleanedBackground());
    }

    @Test
    void segment_preservesDimensions() throws IOException {
        var pages = extractor.extractPages(new File("tests/simple-text.pdf"));
        BufferedImage page = pages.get(0);
        SegmentedImage result = segmenter.segment(page);
        assertEquals(page.getWidth(), result.getForegroundMask().getWidth());
        assertEquals(page.getHeight(), result.getForegroundMask().getHeight());
        assertEquals(page.getWidth(), result.getCleanedBackground().getWidth());
        assertEquals(page.getHeight(), result.getCleanedBackground().getHeight());
    }

    @Test
    void segment_foregroundMaskIsBinary() throws IOException {
        var pages = extractor.extractPages(new File("tests/simple-text.pdf"));
        SegmentedImage result = segmenter.segment(pages.get(0));
        BufferedImage mask = result.getForegroundMask();
        assertEquals(BufferedImage.TYPE_BYTE_BINARY, mask.getType());
    }

    @Test
    void segment_blankPage_hasLittleForeground() throws IOException {
        var pages = extractor.extractPages(new File("tests/blank.pdf"));
        SegmentedImage result = segmenter.segment(pages.get(0));
        BufferedImage mask = result.getForegroundMask();
        int width = mask.getWidth();
        int height = mask.getHeight();
        int blackPixels = 0;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                if ((mask.getRGB(x, y) & 0xFFFFFF) == 0) {
                    blackPixels++;
                }
            }
        }
        int total = width * height;
        assertTrue((double) blackPixels / total < 0.05,
                "Blank page should have <5% foreground pixels, got " +
                (100.0 * blackPixels / total) + "%");
    }

    @Test
    void segment_simpleText_hasForegroundPixels() throws IOException {
        var pages = extractor.extractPages(new File("tests/simple-text.pdf"));
        SegmentedImage result = segmenter.segment(pages.get(0));
        BufferedImage mask = result.getForegroundMask();
        int width = mask.getWidth();
        int height = mask.getHeight();
        int blackPixels = 0;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                if ((mask.getRGB(x, y) & 0xFFFFFF) == 0) {
                    blackPixels++;
                }
            }
        }
        int total = width * height;
        assertTrue((double) blackPixels / total > 0.001,
                "Text page should have >0.1% foreground pixels, got " +
                (100.0 * blackPixels / total) + "%");
    }

    @Test
    void segment_cleanedBackground_reducesTextNoise() throws IOException {
        var pages = extractor.extractPages(new File("tests/simple-text.pdf"));
        BufferedImage page = pages.get(0);
        SegmentedImage result = segmenter.segment(page);
        BufferedImage cleaned = result.getCleanedBackground();

        // Compare cleaned vs original: text areas should be filled in
        BufferedImage mask = result.getForegroundMask();
        int width = page.getWidth();
        int height = page.getHeight();
        int textPixels = 0;
        int differentPixels = 0;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                if ((mask.getRGB(x, y) & 0xFFFFFF) == 0) {
                    textPixels++;
                    int origRgb = page.getRGB(x, y);
                    int cleanRgb = cleaned.getRGB(x, y);
                    if (origRgb != cleanRgb) {
                        differentPixels++;
                    }
                }
            }
        }
        // Most foreground pixels should differ (text filled in)
        assertTrue(textPixels > 0);
        assertTrue((double) differentPixels / textPixels > 0.5,
                "Most foreground pixels should differ between original and cleaned");
    }

    @Test
    void otsuMode_remainsFunctional() throws IOException {
        var pages = extractor.extractPages(new File("tests/simple-text.pdf"));
        ImageSegmenter otsu = new ImageSegmenter(64, 0.95, 0, "otsu", 15, 0.20, 128, 0.005, 0.10);
        SegmentedImage result = otsu.segment(pages.get(0));

        assertEquals(BufferedImage.TYPE_BYTE_BINARY, result.getForegroundMask().getType());
        int blackPixels = countBlackPixels(result.getForegroundMask());
        assertTrue((double) blackPixels / (pages.get(0).getWidth() * pages.get(0).getHeight()) > 0.001,
                "Otsu mode should still detect text as foreground");
    }

    @Test
    void cca_dropsLargeRectangle_keepsText() {
        // 200x200: light gray background, a small text block and a large dark
        // rectangle that should be treated as a picture and left in the background.
        int w = 200, h = 200;
        BufferedImage page = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = page.createGraphics();
        g.setColor(java.awt.Color.LIGHT_GRAY);
        g.fillRect(0, 0, w, h);
        g.setColor(java.awt.Color.BLACK);
        g.fillRect(10, 10, 20, 20);        // text block (small)
        g.fillRect(50, 50, 150, 80);       // picture (large)
        g.dispose();

        ImageSegmenter segmenter = new ImageSegmenter(64, 0.95, 0, "sauvola", 15, 0.20, 128, 0.10, 0.30);
        SegmentedImage result = segmenter.segment(page);
        BufferedImage mask = result.getForegroundMask();

        // Interior of the small text block is foreground (black in mask)
        assertEquals(0x00000000, mask.getRGB(20, 20) & 0xFFFFFF,
                "small text block should stay in the foreground mask");
        // Interior of the large rectangle is background (white in mask)
        assertEquals(0x00FFFFFF, mask.getRGB(120, 90) & 0xFFFFFF,
                "oversized dark region should be dropped from the foreground mask");
    }

    @Test
    void infiniteInpaint_noWhiteBlobBehindThickStroke() {
        // Gray background with a 10px-wide black vertical bar. The bar's center is
        // 5px from the nearest background — beyond a radius of 3, but with infinite
        // inpainting it must be filled with the surrounding gray, not pure white.
        int w = 100, h = 100;
        BufferedImage page = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = page.createGraphics();
        g.setColor(new java.awt.Color(200, 200, 200));
        g.fillRect(0, 0, w, h);
        g.setColor(java.awt.Color.BLACK);
        g.fillRect(45, 0, 10, h);
        g.dispose();

        // CCA disabled (0,0) so the full bar stays in the foreground mask and the
        // inpainting behavior is what is exercised, not the component filter.
        ImageSegmenter infinite = new ImageSegmenter(64, 0.95, 0, "sauvola", 15, 0.20, 128, 0, 0);
        BufferedImage cleanedInfinite = infinite.segment(page).getCleanedBackground();
        assertNotEquals(0xFFFFFFFF, cleanedInfinite.getRGB(50, 50),
                "infinite inpainting should fill thick strokes with the surrounding color");

        ImageSegmenter bounded = new ImageSegmenter(64, 0.95, 3, "sauvola", 15, 0.20, 128, 0, 0);
        BufferedImage cleanedBounded = bounded.segment(page).getCleanedBackground();
        assertEquals(0xFFFFFFFF, cleanedBounded.getRGB(50, 50),
                "bounded inpainting keeps the white-beyond-radius behavior");
    }

    private static int countBlackPixels(BufferedImage mask) {
        int width = mask.getWidth();
        int height = mask.getHeight();
        int black = 0;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                if ((mask.getRGB(x, y) & 0xFFFFFF) == 0) black++;
            }
        }
        return black;
    }
}
