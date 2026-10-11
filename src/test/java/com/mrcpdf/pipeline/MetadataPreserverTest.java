package com.mrcpdf.pipeline;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.InflaterInputStream;
import java.util.stream.StreamSupport;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentNameDictionary;
import org.apache.pdfbox.pdmodel.PDEmbeddedFilesNameTreeNode;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageDestination;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MetadataPreserverTest {

    private final MetadataPreserver preserver = new MetadataPreserver();

    @TempDir
    java.nio.file.Path tempDir;

    /**
     * Regression test for the outline deep-copy bug: the old implementation
     * left /Dest references pointing at SOURCE pages, which PDFBox then
     * serialized into the output as a hidden second page tree (roughly
     * doubling the output size).  The outline must be rebuilt with
     * destinations remapped to the output pages.
     */
    @Test
    void preserve_remapsOutlineDestinationsWithoutCopyingSourcePages() throws IOException {
        File input = new File("tests/all-features.pdf");
        assertTrue(input.exists(),
                "fixture tests/all-features.pdf not found (run ./gradlew generateTestPdfs)");

        File saved = tempDir.resolve("out.pdf").toFile();
        try (PDDocument source = Loader.loadPDF(input);
             PDDocument output = blankOutputFor(source)) {
            var result = preserver.preserve(source, output, allPages(output));
            assertEquals(2, result.outlines(), "Should report 2 top-level bookmarks");
            output.save(saved);
        }

        // The saved file must contain exactly one page tree — the source pages
        // must not leak in through the copied outline.
        assertEquals(1, countPageTrees(saved), "Output must contain exactly one page tree");

        // Outline destinations must resolve to the output pages in order.
        try (PDDocument loaded = Loader.loadPDF(saved)) {
            PDDocumentOutline outline = loaded.getDocumentCatalog().getDocumentOutline();
            assertNotNull(outline, "Outline should be preserved");
            List<Integer> destIndices = new ArrayList<>();
            collectDestPageIndices(outline, loaded, destIndices);
            assertEquals(List.of(0, 1, 2, 3, 4, 5, 6, 7, 8, 9), destIndices,
                    "Outline destinations should map to output pages in order");
        }
    }

    private static void collectDestPageIndices(PDOutlineNode node, PDDocument doc,
                                               List<Integer> out) throws IOException {
        for (PDOutlineItem item : node.children()) {
            PDPageDestination dest = item.getDestination() instanceof PDPageDestination
                    ? (PDPageDestination) item.getDestination() : null;
            if (dest != null) {
                PDPage page = dest.getPage();
                if (page != null) out.add(doc.getPages().indexOf(page));
            }
            collectDestPageIndices(item, doc, out);
        }
    }

    private static int countPageTrees(File pdf) throws IOException {
        byte[] raw = Files.readAllBytes(pdf.toPath());
        StringBuilder hay = new StringBuilder(new String(raw, StandardCharsets.ISO_8859_1));
        String ascii = hay.toString();
        int searchFrom = 0;
        while (true) {
            int st = ascii.indexOf("stream", searchFrom);
            if (st < 0) break;
            int end = ascii.indexOf("endstream", st);
            if (end < 0) break;
            int dataStart = st + "stream".length();
            if (dataStart < end && ascii.charAt(dataStart) == '\r') dataStart++;
            if (dataStart < end && ascii.charAt(dataStart) == '\n') dataStart++;
            byte[] data = new byte[end - dataStart];
            System.arraycopy(raw, dataStart, data, 0, data.length);
            try (InflaterInputStream in = new InflaterInputStream(new ByteArrayInputStream(data))) {
                hay.append('\n').append(new String(in.readAllBytes(), StandardCharsets.ISO_8859_1));
            } catch (IOException ignored) {
            }
            searchFrom = end + "endstream".length();
        }
        String full = hay.toString();
        return full.split("/Type /Pages", -1).length - 1;
    }

    /**
     * Builds an output PDDocument with the same number of blank pages as the
     * source (metadata-only check — no image/text layers involved).
     */
    private static PDDocument blankOutputFor(PDDocument source) {
        PDDocument output = new PDDocument();
        for (int i = 0; i < source.getNumberOfPages(); i++) {
            PDRectangle cb = source.getPage(i).getCropBox();
            output.addPage(new PDPage(new PDRectangle(cb.getWidth(), cb.getHeight())));
        }
        return output;
    }

    private static List<PDPage> allPages(PDDocument doc) {
        List<PDPage> pages = new ArrayList<>();
        for (int i = 0; i < doc.getNumberOfPages(); i++) {
            pages.add(doc.getPage(i));
        }
        return pages;
    }

    @Test
    void preserve_copiesDocumentInfo() throws IOException {
        File input = new File("tests/with-annotations.pdf");
        try (PDDocument source = Loader.loadPDF(input);
             PDDocument output = blankOutputFor(source)) {
            preserver.preserve(source, output, allPages(output));

            var srcInfo = source.getDocumentInformation();
            var dstInfo = output.getDocumentInformation();
            assertEquals(srcInfo.getTitle(), dstInfo.getTitle());
            assertEquals(srcInfo.getAuthor(), dstInfo.getAuthor());
            assertEquals(srcInfo.getSubject(), dstInfo.getSubject());
            assertEquals(srcInfo.getKeywords(), dstInfo.getKeywords());
        }
    }

    @Test
    void preserve_copiesOutline() throws IOException {
        File input = new File("tests/with-annotations.pdf");
        try (PDDocument source = Loader.loadPDF(input);
             PDDocument output = blankOutputFor(source)) {
            preserver.preserve(source, output, allPages(output));

            PDDocumentOutline srcOutline = source.getDocumentCatalog().getDocumentOutline();
            PDDocumentOutline dstOutline = output.getDocumentCatalog().getDocumentOutline();
            assertNotNull(srcOutline, "Source fixture should have an outline");
            assertNotNull(dstOutline, "Outline should be copied when source has one");

            int srcCount = (int) StreamSupport.stream(srcOutline.children().spliterator(), false).count();
            int dstCount = (int) StreamSupport.stream(dstOutline.children().spliterator(), false).count();
            assertEquals(srcCount, dstCount, "Outline item count should match");
            assertEquals(3, srcCount, "with-annotations.pdf should have 3 bookmarks");
        }
    }

    @Test
    void preserve_copiesAnnotations() throws IOException {
        File input = new File("tests/with-annotations.pdf");
        try (PDDocument source = Loader.loadPDF(input);
             PDDocument output = blankOutputFor(source)) {
            preserver.preserve(source, output, allPages(output));

            for (int i = 0; i < source.getNumberOfPages() && i < output.getNumberOfPages(); i++) {
                int srcCount = source.getPage(i).getAnnotations().size();
                int dstCount = output.getPage(i).getAnnotations().size();
                assertEquals(srcCount, dstCount,
                        "Page " + (i + 1) + " annotation count should match");
            }
            assertEquals(2, source.getPage(0).getAnnotations().size(),
                    "with-annotations.pdf page 1 should have 2 annotations");
        }
    }

    @Test
    void preserve_copiesEmbeddedFiles() throws IOException {
        File input = new File("tests/with-attachments.pdf");
        try (PDDocument source = Loader.loadPDF(input);
             PDDocument output = blankOutputFor(source)) {
            preserver.preserve(source, output, allPages(output));

            PDDocumentNameDictionary srcNames = source.getDocumentCatalog().getNames();
            PDDocumentNameDictionary dstNames = output.getDocumentCatalog().getNames();
            assertNotNull(srcNames, "Source should have names dictionary");
            PDEmbeddedFilesNameTreeNode srcEmbedded = srcNames.getEmbeddedFiles();
            assertNotNull(srcEmbedded, "Source should have embedded files");

            assertNotNull(dstNames, "Output should have names dictionary after preserve");
            PDEmbeddedFilesNameTreeNode dstEmbedded = dstNames.getEmbeddedFiles();
            assertNotNull(dstEmbedded, "Output should have embedded files after preserve");

            var srcMap = srcEmbedded.getNames();
            var dstMap = dstEmbedded.getNames();
            assertNotNull(srcMap, "Source should have embedded file names");
            assertNotNull(dstMap, "Output should have embedded file names");
            assertEquals(srcMap.size(), dstMap.size(), "Embedded file count should match");
            assertEquals(srcMap.keySet(), dstMap.keySet(), "Embedded file names should match");
        }
    }

    @Test
    void preserve_returnsCounts() throws IOException {
        File input = new File("tests/with-attachments.pdf");
        try (PDDocument source = Loader.loadPDF(input);
             PDDocument output = blankOutputFor(source)) {
            var result = preserver.preserve(source, output, allPages(output));

            // with-attachments.pdf has 2 bookmarks and 2 embedded files
            assertEquals(2, result.outlines(), "Should report 2 bookmarks");
            assertEquals(2, result.embeddedFiles(), "Should report 2 embedded files");
        }
    }

    @Test
    void preserve_noMetadata_isNoop() throws IOException {
        File input = new File("tests/blank.pdf");
        try (PDDocument source = Loader.loadPDF(input);
             PDDocument output = blankOutputFor(source)) {
            var result = preserver.preserve(source, output, allPages(output));

            assertEquals(0, result.outlines());
            assertEquals(0, result.annotations());
            assertEquals(0, result.embeddedFiles());
        }
    }

    /**
     * A11: annotations are copied with their source /Rect, which is specified in
     * the source page's default user space BEFORE rotation and relative to the
     * crop box lower-left.  The output page is the rendered (rotated) page in a
     * fresh, un-rotated, zero-origin user space, so the rect must be transformed
     * through the same mapping PDFBox applies to the page content — otherwise
     * link targets and highlights land on the wrong part of the page.
     */
    @Test
    void preserve_remapsAnnotationGeometryForRotatedCropbox() throws IOException {
        // Crop box offset from the page origin (llx=40, lly=60) and 90-degree
        // rotation, so a wrongly-flipped transform fails loudly.
        PDDocument source = new PDDocument();
        PDPage srcPage = new PDPage(new PDRectangle(0, 0, 612, 792));
        srcPage.setCropBox(new PDRectangle(40, 60, 300, 500));
        srcPage.setRotation(90);
        PDAnnotationLink link = new PDAnnotationLink();
        link.setRectangle(new PDRectangle(100, 120, 50, 30)); // x0=100,y0=120,x1=150,y1=150 (page space)
        srcPage.getAnnotations().add(link);
        source.addPage(srcPage);

        try (source; PDDocument output = outputFor(source)) {
            preserver.preserve(source, output, allPages(output));

            PDRectangle out = output.getPage(0).getAnnotations().get(0).getRectangle();
            // R=90: out_x = (lly + H) - y, out_y = (llx + W) - x, on a page
            // (W=300, H=500).
            // (100,120) -> (60+500-120, 40+300-100) = (440, 240)
            // (150,150) -> (60+500-150, 40+300-150) = (410, 190)
            assertEquals(410f, out.getLowerLeftX(), 0.5f, "R=90 rect x0 — swapped axis");
            assertEquals(190f, out.getLowerLeftY(), 0.5f, "R=90 rect y0");
            assertEquals(440f, out.getUpperRightX(), 0.5f, "R=90 rect x1");
            assertEquals(240f, out.getUpperRightY(), 0.5f, "R=90 rect y1");
        }
    }

    /**
     * A11, un-rotated page with an offset crop box: only the origin translation
     * and the device y-flip apply (out_y = (lly + H) - y).
     */
    @Test
    void preserve_remapsAnnotationGeometryForOffsetCropbox() throws IOException {
        PDDocument source = new PDDocument();
        PDPage srcPage = new PDPage(new PDRectangle(0, 0, 612, 792));
        srcPage.setCropBox(new PDRectangle(40, 60, 300, 500));
        PDAnnotationLink link = new PDAnnotationLink();
        link.setRectangle(new PDRectangle(100, 120, 50, 30));
        srcPage.getAnnotations().add(link);
        source.addPage(srcPage);

        try (source; PDDocument output = outputFor(source)) {
            preserver.preserve(source, output, allPages(output));

            PDRectangle out = output.getPage(0).getAnnotations().get(0).getRectangle();
            // R=0: out_x = x - llx = 100-40 = 60; out_y = (lly+H) - y.
            // (100,120) -> (60, 560-120) = (60,440); (150,150) -> (110, 410).
            assertEquals(60f, out.getLowerLeftX(), 0.5f, "R=0 rect x0 — cropbox origin");
            assertEquals(410f, out.getLowerLeftY(), 0.5f, "R=0 rect y0 — y-flip");
            assertEquals(110f, out.getUpperRightX(), 0.5f, "R=0 rect x1");
            assertEquals(440f, out.getUpperRightY(), 0.5f, "R=0 rect y1");
        }
    }

    /**
     * Output document whose pages mirror the pipeline's: crop-box dimensions,
     * width/height swapped for 90/270 rotations, un-rotated.
     */
    private static PDDocument outputFor(PDDocument source) {
        PDDocument output = new PDDocument();
        for (int i = 0; i < source.getNumberOfPages(); i++) {
            PDPage src = source.getPage(i);
            PDRectangle cb = src.getCropBox();
            if (src.getRotation() % 180 != 0) {
                output.addPage(new PDPage(new PDRectangle(cb.getHeight(), cb.getWidth())));
            } else {
                output.addPage(new PDPage(new PDRectangle(cb.getWidth(), cb.getHeight())));
            }
        }
        return output;
    }
}
