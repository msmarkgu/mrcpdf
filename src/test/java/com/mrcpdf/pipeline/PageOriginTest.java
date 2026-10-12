package com.mrcpdf.pipeline;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.image.JPEGFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDInlineImage;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The origin detector that decides which pages skip MRC outright.
 *
 * <p>Every assertion is on the measured coverage as well as the verdict, so a
 * change that moves the number without moving the boolean still shows up here.
 */
class PageOriginTest {

    private static final float W = 612, H = 792;

    // ── fixtures ───────────────────────────────────────────────────────

    @Test
    void bornDigitalPagesAreNative() throws Exception {
        try (PDDocument doc = open("tests/born-digital.pdf")) {
            double plain = PageOrigin.coverageOf(doc.getPage(0));
            double withFigure = PageOrigin.coverageOf(doc.getPage(1));

            assertEquals(0.0, plain, 1e-9,
                    "a page with no raster at all has no coverage to report");
            assertTrue(plain < PageOrigin.PAGE_FILL_COVERAGE);
            assertTrue(PageOrigin.isDigitalNative(doc.getPage(0)));

            // The half-page figure is a real image, drawn small enough to leave
            // the page vector. 320x320 on 612x792 is 21%.
            assertTrue(withFigure > 0.15 && withFigure < 0.30,
                    "figure should measure about 0.21, measured " + withFigure);
            assertTrue(PageOrigin.isDigitalNative(doc.getPage(1)));
        }
    }

    @Test
    void scannedPagesAreNotNative() throws Exception {
        for (String fixture : new String[]{
                "tests/scanned-text.pdf",   // raster + invisible text layer
                "tests/noisy-scan.pdf",     // raster only
                "tests/simple-text.pdf"}) { // saveImagePdf: raster + text layer
            try (PDDocument doc = open(fixture)) {
                double coverage = PageOrigin.coverageOf(doc.getPage(0));
                assertTrue(coverage >= PageOrigin.PAGE_FILL_COVERAGE,
                        fixture + " should measure as page-filling, measured "
                                + coverage);
                assertFalse(PageOrigin.isDigitalNative(doc.getPage(0)));
            }
        }
    }

    @Test
    void mixedOriginPdf_decidesPerPage() throws Exception {
        try (PDDocument doc = open("tests/mixed-origin.pdf")) {
            assertTrue(PageOrigin.isDigitalNative(doc.getPage(0)),
                    "page 1 is vector and must pass through");
            assertFalse(PageOrigin.isDigitalNative(doc.getPage(1)),
                    "page 2 is a full-page raster and must stay on the MRC path");
        }
    }

    // ── synthetic boundaries ───────────────────────────────────────────

    @Test
    void coverageThresholdSitsBetweenHalfAndNearlyFull() throws Exception {
        try (PDDocument doc = new PDDocument()) {
            PDPage half = new PDPage(new PDRectangle(W, H));
            doc.addPage(half);
            drawImage(doc, half, W / 4, H / 4, W / 2, H / 2); // 25%

            PDPage almost = new PDPage(new PDRectangle(W, H));
            doc.addPage(almost);
            // 594 x 774 inset by 9pt on each side: 94.8% of the page.
            drawImage(doc, almost, 9, 9, 594, 774);

            double halfCoverage = PageOrigin.coverageOf(half);
            double almostCoverage = PageOrigin.coverageOf(almost);

            assertEquals(0.25, halfCoverage, 0.01,
                    "a quarter-page figure measures a quarter, measured "
                            + halfCoverage);
            assertTrue(PageOrigin.isDigitalNative(half),
                    "a figure must not make the page a scan");

            assertTrue(almostCoverage > 0.9,
                    "a page-filling raster measures above 0.9, measured "
                            + almostCoverage);
            assertFalse(PageOrigin.isDigitalNative(almost),
                    "white margins around a scan must not make it native");
        }
    }

    @Test
    void fullPageRasterFollowingASmallFigure_isNotNative() throws Exception {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(new PDRectangle(W, H));
            doc.addPage(page);
            // A logo drawn first, then the scan itself. The second image's CTM
            // is only right if the first one's q/Q pair was honoured; if it was
            // not, the full-page raster lands off the crop box, measures zero,
            // and the page would be waved through as native.
            drawImage(doc, page, 40, H - 140, 100, 100);
            drawImage(doc, page, 0, 0, W, H);

            double coverage = PageOrigin.coverageOf(page);
            assertTrue(coverage >= PageOrigin.PAGE_FILL_COVERAGE,
                    "the full-page raster must still be measured, got "
                            + coverage);
            assertFalse(PageOrigin.isDigitalNative(page));
        }
    }

    @Test
    void twoSmallFiguresLeaveThePageNative() throws Exception {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(new PDRectangle(W, H));
            doc.addPage(page);
            // Both are drawn inside q/cm/Do/Q. If the stack is not maintained,
            // the second figure is measured against the first one's CTM instead
            // of the page's, and the page flips to a scan.
            drawImage(doc, page, 40, 40, 300, 300);
            drawImage(doc, page, 272, 452, 300, 300);

            double coverage = PageOrigin.coverageOf(page);
            assertTrue(coverage < 0.3,
                    "two small figures measure as two small figures, measured "
                            + coverage);
            assertTrue(PageOrigin.isDigitalNative(page));
        }
    }

    @Test
    void figureStraddlingThePageEdgeIsClippedToThePage() throws Exception {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(new PDRectangle(W, H));
            doc.addPage(page);
            // 800pt wide starting 400pt off the left edge: the raw bounds are
            // 1.31 of the page, but only 400 x 792 of it is actually on the
            // page -- 65%, below the threshold.
            drawImage(doc, page, -400, 0, 800, H);

            double coverage = PageOrigin.coverageOf(page);
            assertTrue(coverage > 0.60 && coverage < 0.70,
                    "coverage must be clipped to the crop box, measured "
                            + coverage);
            assertTrue(PageOrigin.isDigitalNative(page),
                    "a figure that only partly reaches the page is not a scan");
        }
    }

    @Test
    void rasterInsideAFormXObject_isNotNative() throws Exception {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(new PDRectangle(W, H));
            doc.addPage(page);

            PDImageXObject img = solidImage(doc, new Color(120, 60, 30));
            PDFormXObject form = new PDFormXObject(doc);
            form.setBBox(new PDRectangle(0, 0, W, H));
            form.setResources(new PDResources());
            COSName name = form.getResources().add(img);
            PDStream body = form.getContentStream();
            try (OutputStream out = body.createOutputStream()) {
                out.write(("q " + W + " 0 0 " + H + " 0 0 cm /" + name.getName()
                        + " Do Q").getBytes(StandardCharsets.US_ASCII));
            }

            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.drawForm(form);
            }

            double coverage = PageOrigin.coverageOf(page);
            assertTrue(coverage > 0.9,
                    "the form's image must be descended into, measured " + coverage);
            assertFalse(PageOrigin.isDigitalNative(page),
                    "a full-page raster inside a form is still a scan");
        }
    }

    @Test
    void pageFillingInlineImage_isNotNative() throws Exception {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(new PDRectangle(W, H));
            doc.addPage(page);

            COSDictionary d = new COSDictionary();
            d.setInt(COSName.WIDTH, 4);
            d.setInt(COSName.HEIGHT, 4);
            d.setInt(COSName.BITS_PER_COMPONENT, 8);
            d.setItem(COSName.COLORSPACE, COSName.DEVICERGB);
            byte[] rgb = new byte[4 * 4 * 3];
            for (int i = 0; i < rgb.length; i += 3) {
                rgb[i] = (byte) 200;
                rgb[i + 1] = (byte) 100;
                rgb[i + 2] = (byte) 50;
            }
            PDInlineImage inline = new PDInlineImage(d, rgb, null);

            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.drawImage(inline, 0, 0, W, H);
            }

            double coverage = PageOrigin.coverageOf(page);
            assertTrue(coverage > 0.99,
                    "an inline image needs no resource lookup, measured "
                            + coverage);
            assertFalse(PageOrigin.isDigitalNative(page));
        }
    }

    @Test
    void pageWithNoResourcesAtAll_isNative() throws Exception {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(new PDRectangle(W, H));
            doc.addPage(page);
            assertNull(page.getResources(), "page is built with no /Resources");

            assertEquals(0.0, PageOrigin.coverageOf(page), 1e-9);
            assertTrue(PageOrigin.isDigitalNative(page),
                    "an empty page must not be mistaken for a scan");
        }
    }

    // ── native-resolution probe ────────────────────────────────────────

    @Test
    void nativeRasterDpi_reflectsPixelsPerDrawnPoint() throws Exception {
        try (PDDocument doc = new PDDocument()) {
            PDPage at72 = new PDPage(new PDRectangle(W, H));
            doc.addPage(at72);
            drawImage(doc, at72, imageOfSize(doc, 72, 72), 0, 0, 72, 72);

            PDPage at144 = new PDPage(new PDRectangle(W, H));
            doc.addPage(at144);
            drawImage(doc, at144, imageOfSize(doc, 144, 144), 0, 0, 72, 72);

            assertEquals(72.0, PageOrigin.probe(at72).getNativeDpi(), 1.0,
                    "a 72px raster drawn over 72pt is 72 ppi");
            assertEquals(144.0, PageOrigin.probe(at144).getNativeDpi(), 1.0,
                    "a 144px raster drawn over 72pt is 144 ppi");
            assertTrue(PageOrigin.probe(at144).hasNativeDpi());
        }
    }

    @Test
    void nativeRasterDpi_usesTheSmallerAxis() throws Exception {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(new PDRectangle(W, H));
            doc.addPage(page);
            // 100x400 px into 100x100 pt: 72 ppi horizontally, 288 ppi vertically.
            // The smaller axis is the one a re-render can actually satisfy.
            drawImage(doc, page, imageOfSize(doc, 100, 400), 0, 0, 100, 100);

            assertEquals(72.0, PageOrigin.probe(page).getNativeDpi(), 1.0,
                    "the limiting axis sets the measured resolution");
        }
    }

    @Test
    void nativeRasterDpi_isUnknownWithoutARaster() throws Exception {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(new PDRectangle(W, H));
            doc.addPage(page);

            PageOrigin.Probe probe = PageOrigin.probe(page);
            assertEquals(0.0, probe.getNativeDpi(), 1e-9,
                    "a vector page has no measured resolution");
            assertFalse(probe.hasNativeDpi());
        }
    }

    @Test
    void nativeRasterDpi_isUnknownForInlineImages() throws Exception {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(new PDRectangle(W, H));
            doc.addPage(page);

            COSDictionary d = new COSDictionary();
            d.setInt(COSName.WIDTH, 4);
            d.setInt(COSName.HEIGHT, 4);
            d.setInt(COSName.BITS_PER_COMPONENT, 8);
            d.setItem(COSName.COLORSPACE, COSName.DEVICERGB);
            byte[] rgb = new byte[4 * 4 * 3];
            for (int i = 0; i < rgb.length; i += 3) {
                rgb[i] = (byte) 200;
                rgb[i + 1] = (byte) 100;
                rgb[i + 2] = (byte) 50;
            }
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.drawImage(new PDInlineImage(d, rgb, null), 0, 0, W, H);
            }

            assertFalse(PageOrigin.probe(page).hasNativeDpi(),
                    "an inline image exposes no resolvable pixel dimensions");
        }
    }

    @Test
    void nativeRasterDpi_followsTheBestCoveringRaster() throws Exception {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(new PDRectangle(72, 72));
            doc.addPage(page);
            // A dense little logo that fills almost none of the page, then the
            // page-filling scan. The scan's resolution is the page's resolution.
            drawImage(doc, page, imageOfSize(doc, 600, 600), 10, 10, 2, 2);
            drawImage(doc, page, imageOfSize(doc, 144, 144), 0, 0, 72, 72);

            assertEquals(144.0, PageOrigin.probe(page).getNativeDpi(), 1.0,
                    "the page-filling raster sets the measured resolution");
        }
    }

    // ── helpers ────────────────────────────────────────────────────────

    private static PDDocument open(String path) throws Exception {
        File f = new File(path);
        assertTrue(f.exists(), "fixture " + path
                + " not found (run ./gradlew generateTestPdfs)");
        return org.apache.pdfbox.Loader.loadPDF(f);
    }

    private static PDImageXObject solidImage(PDDocument doc, Color color)
            throws Exception {
        BufferedImage img = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(color);
        g.fillRect(0, 0, 8, 8);
        g.dispose();
        return JPEGFactory.createFromImage(doc, img, 0.9f);
    }

    private static PDImageXObject imageOfSize(PDDocument doc, int w, int h)
            throws Exception {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.DARK_GRAY);
        g.fillRect(0, 0, w, h);
        g.dispose();
        return JPEGFactory.createFromImage(doc, img, 0.9f);
    }

    private static void drawImage(PDDocument doc, PDPage page,
                                  float x, float y, float w, float h)
            throws Exception {
        drawImage(doc, page, solidImage(doc, Color.DARK_GRAY), x, y, w, h);
    }

    private static void drawImage(PDDocument doc, PDPage page, PDImageXObject img,
                                  float x, float y, float w, float h)
            throws Exception {
        // Append: a second call must add to the page, not replace it, or the
        // page under test is not the page the test describes.
        try (PDPageContentStream cs = new PDPageContentStream(doc, page,
                PDPageContentStream.AppendMode.APPEND, true, true)) {
            cs.drawImage(img, x, y, w, h);
        }
    }
}
