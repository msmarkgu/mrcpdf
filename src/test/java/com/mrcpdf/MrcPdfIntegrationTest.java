package com.mrcpdf;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
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
