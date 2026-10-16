package com.mrcpdf;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import picocli.CommandLine;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end tests over the full MrcPdf pipeline. The synthetic fixtures cover
 * the three origins the pipeline has to tell apart: {@code scanned-text.pdf}
 * (grayscale page images + an invisible searchable text layer), {@code
 * born-digital.pdf} (both pages vector) and {@code mixed-origin.pdf} (one of
 * each in a single document).
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

    // ──────────────────────────────────────────────────────────────────────
    // Digital-native pass-through
    // ──────────────────────────────────────────────────────────────────────

    /**
     * E2E for pass-through: both pages of {@code born-digital.pdf} are vector,
     * so the pipeline must report them native and carry them over untouched --
     * the source's single small figure survives as the same image, and no page
     * gains a render that would have replaced the glyphs with pixels.
     */
    @Test
    void bornDigitalPdf_passesThroughRatherThanRasterising() throws Exception {
        File input = new File("tests/born-digital.pdf");
        assertTrue(input.exists(),
                "fixture tests/born-digital.pdf not found (run ./gradlew generateTestPdfs)");

        int inputWords = countWords(input);
        int[][] inputImages = imagesPerPage(input);
        assertEquals(0, inputImages[0].length, "page 1 of the fixture must be pure vector");
        assertEquals(1, inputImages[1].length, "page 2 carries exactly the one figure");

        File output = tempDir.resolve("born.pdf").toFile();
        CliRun run = runCli(input.getAbsolutePath(), "-o", output.getAbsolutePath());
        assertEquals(0, run.exit(), run.log());
        assertTrue(run.log().contains("Pass-through: on"), run.log());
        assertTrue(run.log().contains("Pass-through pages: 2 (digital native)"), run.log());

        assertEquals(inputWords, countWords(output),
                "pass-through must not re-rasterise the text layer");
        assertEquals(java.util.Arrays.deepToString(inputImages),
                java.util.Arrays.deepToString(imagesPerPage(output)),
                "the source images must come through unchanged -- a render would "
                        + "replace every page's content with a full-page raster");
    }

    /**
     * The contrast case: the same input with pass-through switched off must be
     * rendered and rebuilt, which is the only thing the flag changes. Without
     * this, a detector that mislabelled every page native would still pass
     * {@link #bornDigitalPdf_passesThroughRatherThanRasterising} -- and so would
     * a pipeline that ignored the flag entirely.
     */
    @Test
    void noPassThroughRendersEvenVectorPages() throws Exception {
        File input = new File("tests/born-digital.pdf");
        assertTrue(input.exists(),
                "fixture tests/born-digital.pdf not found (run ./gradlew generateTestPdfs)");

        int inputWords = countWords(input);
        int[][] inputImages = imagesPerPage(input);

        File output = tempDir.resolve("born-nopass.pdf").toFile();
        CliRun run = runCli(input.getAbsolutePath(), "-o", output.getAbsolutePath(),
                "--no-pass-through");
        assertEquals(0, run.exit(), run.log());
        assertTrue(run.log().contains("Pass-through: off"), run.log());
        assertFalse(run.log().contains("Pass-through pages:"),
                "the origin pass must not run when pass-through is off: " + run.log());

        int[][] outputImages = imagesPerPage(output);
        assertEquals(inputWords, countWords(output), "the text layer must still be rebuilt");
        assertTrue(outputImages[0].length > inputImages[0].length,
                "forcing the render path must give the vector page a raster it never had");
    }

    /**
     * One document, both origins: page 1 vector, page 2 a full-page scan. The
     * decision has to be per page -- a document-level answer would either lose
     * the pass-through on page 1 or skip MRC on page 2.
     */
    @Test
    void mixedOriginPdf_decidesPerPage() throws Exception {
        File input = new File("tests/mixed-origin.pdf");
        assertTrue(input.exists(),
                "fixture tests/mixed-origin.pdf not found (run ./gradlew generateTestPdfs)");

        int inputWords = countWords(input);
        assertTrue(inputWords > 0, "fixture's vector page should contain real text");

        File output = tempDir.resolve("mixed.pdf").toFile();
        CliRun run = runCli(input.getAbsolutePath(), "-o", output.getAbsolutePath());
        assertEquals(0, run.exit(), run.log());
        assertTrue(run.log().contains("Pass-through pages: 1 (digital native)"), run.log());

        int[][] outputImages = imagesPerPage(output);
        assertEquals(0, outputImages[0].length,
                "the vector page must be imported, not rendered");
        assertTrue(outputImages[1].length > 0,
                "the scanned page must have been rebuilt");
        assertTrue(strip(output).contains("Mixed origin page 1: vector"),
                "page 1's vector text must survive the import intact");
    }

    /**
     * The default, not just the setting: a user's own settings file may predate
     * {@code pipeline.origin.passThrough} entirely, and the value must still
     * resolve to on. Asserted through {@code --settings} rather than by
     * deleting the key from the bundled file, which no test should edit.
     */
    @Test
    void passThroughDefaultsOnWhenSettingsOmitTheKey() throws Exception {
        File minimal = tempDir.resolve("minimal-settings.jsonc").toFile();
        java.nio.file.Files.writeString(minimal.toPath(),
                "{ \"pipeline.mrc.enabled\": false }");

        File input = new File("tests/born-digital.pdf");
        assertTrue(input.exists(),
                "fixture tests/born-digital.pdf not found (run ./gradlew generateTestPdfs)");

        File output = tempDir.resolve("born-minimal.pdf").toFile();
        String previous = System.getProperty("mrcpdf.settings");
        try {
            CliRun run = runCli("--settings", minimal.getAbsolutePath(),
                    input.getAbsolutePath(), "-o", output.getAbsolutePath());
            assertEquals(0, run.exit(), run.log());
            assertTrue(run.log().contains("Pass-through: on"), run.log());
            assertTrue(run.log().contains("Pass-through pages: 2 (digital native)"),
                    run.log());
        } finally {
            // --settings sticks on the system property for the rest of the JVM.
            if (previous == null) System.clearProperty("mrcpdf.settings");
            else System.setProperty("mrcpdf.settings", previous);
        }
    }

    /** One CLI run with stdout captured, so log assertions are possible. */
    private static CliRun runCli(String... args) {
        PrintStream previous = System.out;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (PrintStream capture = new PrintStream(buffer, true, StandardCharsets.UTF_8)) {
            System.setOut(capture);
            int exit = new CommandLine(new MrcPdf()).execute(args);
            return new CliRun(exit, buffer.toString(StandardCharsets.UTF_8));
        } finally {
            System.setOut(previous);
        }
    }

    private record CliRun(int exit, String log) {}

    /**
     * Width×10000+height of every image reachable from each page, in page
     * order, recursing through form XObjects. Direct dimensions rather than
     * bytes: a pass-through and a re-encode of the same source produce equal
     * numbers, a render produces a page-filling one that was never there.
     */
    private static int[][] imagesPerPage(File pdf) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            int[][] out = new int[doc.getNumberOfPages()][];
            for (int i = 0; i < out.length; i++) {
                List<Integer> sizes = new ArrayList<>();
                collectImages(doc.getPage(i).getResources(), 0, sizes);
                out[i] = sizes.stream().mapToInt(Integer::intValue).toArray();
            }
            return out;
        }
    }

    private static void collectImages(PDResources resources, int depth, List<Integer> sizes)
            throws IOException {
        if (resources == null || depth > 8) return;
        for (COSName name : resources.getXObjectNames()) {
            PDXObject xobject = resources.getXObject(name);
            if (xobject instanceof PDImageXObject image) {
                sizes.add(image.getWidth() * 10000 + image.getHeight());
            } else if (xobject instanceof PDFormXObject form) {
                collectImages(form.getResources(), depth + 1, sizes);
            }
        }
    }

    private static int countWords(File pdf) throws IOException {
        String text = strip(pdf);
        if (text.isBlank()) return 0;
        return text.trim().split("\\s+").length;
    }

    private static String strip(File pdf) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            return stripper.getText(doc);
        }
    }
}
