package com.mrcpdf;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import picocli.CommandLine;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end test over the full MrcPdf pipeline on the synthetic
 * scanned-text.pdf fixture (grayscale page images + an invisible
 * searchable text layer).
 */
class MrcPdfIntegrationTest {

    @TempDir
    Path tempDir;

    @Test
    void scannedTextPdf_compressesAndPreservesSearchableText() throws Exception {
        File input = new File("tests/scanned-text.pdf");
        assertTrue(input.exists(),
                "fixture tests/scanned-text.pdf not found (run ./gradlew generateTestPdfs)");

        int inputWords = countWords(input);
        assertTrue(inputWords > 0, "fixture should contain a searchable text layer");

        File output = tempDir.resolve("out.pdf").toFile();
        int exitCode = new CommandLine(new MrcPdf()).execute(
                input.getAbsolutePath(), "-o", output.getAbsolutePath());
        assertEquals(0, exitCode, "mrcpdf run should succeed");

        assertTrue(output.exists(), "output file should be written");
        assertTrue(output.length() < input.length(),
                "MRC output should be smaller than input (input=" + input.length()
                        + ", output=" + output.length() + ")");

        int outputWords = countWords(output);
        assertTrue(outputWords > 0, "searchable text must be preserved in the output");
    }

    /**
     * E2E over the foreground-color (soft-mask) path: the pipeline must succeed,
     * preserve searchable text, and the assembled pages must contain a /SMask
     * image. (Output may grow relative to the plain path because the color plane
     * adds a layer, so no size assertion is made here.)
     */
    @Test
    void scannedTextPdf_foregroundColor_preservesTextAndAddsSMask() throws Exception {
        File input = new File("tests/scanned-text.pdf");
        assertTrue(input.exists(),
                "fixture tests/scanned-text.pdf not found (run ./gradlew generateTestPdfs)");

        File output = tempDir.resolve("out-fgcolor.pdf").toFile();
        int exitCode = new CommandLine(new MrcPdf()).execute(
                input.getAbsolutePath(), "-o", output.getAbsolutePath(), "--fg-color");
        assertEquals(0, exitCode, "mrcpdf run with --fg-color should succeed");

        assertTrue(output.exists(), "output file should be written");

        int outputWords = countWords(output);
        assertTrue(outputWords > 0, "searchable text must be preserved in fg-color output");

        try (PDDocument doc = Loader.loadPDF(output)) {
            boolean foundSmask = false;
            PDPage page = doc.getPage(0);
            for (COSName name : page.getResources().getXObjectNames()) {
                var xobj = page.getResources().getXObject(name);
                if (xobj instanceof PDImageXObject img) {
                    if (!img.getCOSObject().getBoolean(COSName.IMAGE_MASK, false)
                            && img.getCOSObject().getItem(COSName.SMASK) != null) {
                        foundSmask = true;
                    }
                }
            }
            assertTrue(foundSmask,
                    "fg-color output should contain a soft-masked foreground image");
        }
    }

    private static int countWords(File pdf) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            String text = stripper.getText(doc);
            if (text == null || text.isBlank()) return 0;
            return text.trim().split("\\s+").length;
        }
    }
}
