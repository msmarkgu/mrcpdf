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
}
