package com.mrcpdf.pipeline;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentNameDictionary;
import org.apache.pdfbox.pdmodel.PDEmbeddedFilesNameTreeNode;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline;
import org.junit.jupiter.api.Test;

class MetadataPreserverTest {

    private final MetadataPreserver preserver = new MetadataPreserver();

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

            int srcCount = 0, dstCount = 0;
            for (var child : srcOutline.children()) srcCount++;
            for (var child : dstOutline.children()) dstCount++;
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
