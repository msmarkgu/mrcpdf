package com.mrcpdf.pipeline;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Tests for JBIG2Compressor with synthetic binary images.
 *
 * Covers both paths: the CCITT G4 fallback, and -- when the bundled jbig2enc
 * binary is discoverable -- the batch shared-symbol-dictionary path.
 *
 * The batch tests previously wrote probe masks as {@code mask-N.bmp} while
 * production reads {@code mask-N.png}, so the probe always failed and the
 * availability check {@code return}ed early.  The result was that the batch
 * symbol-dictionary path had no executing test anywhere: the assertions below
 * were never reached and the tests reported as passing.  Masks are now written
 * as PNG, and an unavailable binary is now an explicit assumption failure
 * (reported as *skipped*) rather than a silent pass.
 */
class JBIG2CompressorTest {

    static JBIG2Compressor compressor;
    static JBIG2Compressor fallbackCompressor;

    @BeforeAll
    static void setup() {
        compressor = new JBIG2Compressor();
        fallbackCompressor = new JBIG2Compressor("/nonexistent");
    }

    @Test
    void compress_blankImage_returnsNonNull() throws IOException {
        BufferedImage img = new BufferedImage(100, 100, BufferedImage.TYPE_BYTE_BINARY);
        JBIG2Compressor.CompressionResult result = compressor.compress(img);
        assertNotNull(result);
        assertNotNull(result.getData());
    }

    @Test
    void compress_preservesDimensions() throws IOException {
        BufferedImage img = new BufferedImage(200, 300, BufferedImage.TYPE_BYTE_BINARY);
        JBIG2Compressor.CompressionResult result = compressor.compress(img);
        assertEquals(200, result.getWidth());
        assertEquals(300, result.getHeight());
    }

    @Test
    void compress_allWhite_returnsNonNull() throws IOException {
        BufferedImage img = new BufferedImage(100, 100, BufferedImage.TYPE_BYTE_BINARY);
        for (int y = 0; y < 100; y++) {
            for (int x = 0; x < 100; x++) {
                img.setRGB(x, y, 0xFFFFFFFF);
            }
        }
        JBIG2Compressor.CompressionResult result = compressor.compress(img);
        assertNotNull(result);
        assertNotNull(result.getData());
    }

    @Test
    void compress_withForegroundPixels_returnsNonNull() throws IOException {
        BufferedImage img = new BufferedImage(100, 100, BufferedImage.TYPE_BYTE_BINARY);
        for (int y = 0; y < 100; y++) {
            for (int x = 0; x < 100; x++) {
                img.setRGB(x, y, 0xFFFFFFFF);
            }
        }
        for (int x = 10; x < 90; x++) {
            img.setRGB(x, 50, 0xFF000000);
        }
        JBIG2Compressor.CompressionResult result = compressor.compress(img);
        assertNotNull(result);
        assertNotNull(result.getData());
    }

    @Test
    void compress_fallbackIsNotJbig2() throws IOException {
        BufferedImage img = new BufferedImage(50, 50, BufferedImage.TYPE_BYTE_BINARY);
        JBIG2Compressor.CompressionResult result = fallbackCompressor.compress(img);
        assertNotNull(result);
        assertFalse(result.isJbig2(), "Should fallback when jbig2enc unavailable");
    }

    /**
     * Regression test for the batch-JBIG2 shared-dictionary bug.
     *
     * Verifies that compressAllFromDir returns null (graceful fallback)
     * when jbig2enc is not available, proving the API doesn't throw.
     */
    @Test
    void compressAllFromDir_returnsNullWhenJbig2encMissing() throws IOException {
        JBIG2Compressor missing = new JBIG2Compressor("/nonexistent");
        JBIG2Compressor.BatchResult result = missing.compressAllFromDir(
            new File("temp"), 2,
            new int[]{100, 100}, new int[]{100, 100});
        assertNull(result, "Should return null when jbig2enc not found");
    }

    /**
     * Regression test for the batch-JBIG2 shared-dictionary bug.
     *
     * When jbig2enc IS available, compressAllFromDir should produce a
     * BatchResult with a non-empty global symbol dictionary and per-page data.
     * When unavailable the test is silently skipped.
     */
    @Test
    void compressAllFromDir_returnsBatchResultWhenJbig2encAvailable() throws IOException {
        JBIG2Compressor realCompressor = new JBIG2Compressor();
        assumeJbig2encAvailable(realCompressor);

        // Create two masks with different text patterns
        File maskDir = new File("temp", "mrcpdf-jbig2-test-"
            + System.nanoTime());
        maskDir.mkdirs();
        try {
            for (int i = 0; i < 2; i++) {
                BufferedImage mask = new BufferedImage(100, 100, BufferedImage.TYPE_BYTE_BINARY);
                for (int x = 10; x < 90; x++) {
                    mask.setRGB(x, 50 + i * 20, 0xFF000000);
                }
                ImageIO.write(mask, "png", new File(maskDir, "mask-" + i + ".png"));
            }

            JBIG2Compressor.BatchResult result = realCompressor.compressAllFromDir(maskDir, 2,
                new int[]{100, 100}, new int[]{100, 100});
            assertNotNull(result,
                "BatchResult should not be null when jbig2enc is available");
            assertNotNull(result.getGlobalSym(),
                "Global symbol dictionary should not be null");
            assertTrue(result.getGlobalSym().length > 0,
                "Global symbol dictionary should have data");
            assertEquals(2, result.getPages().size(),
                "Should have per-page data for 2 pages");
            for (int i = 0; i < 2; i++) {
                JBIG2Compressor.CompressionResult page = result.getPages().get(i);
                assertEquals(100, page.getWidth());
                assertEquals(100, page.getHeight());
                assertTrue(page.isJbig2(), "Batch page should be JBIG2 (not fallback)");
            }
        } finally {
            for (File f : maskDir.listFiles()) f.delete();
            maskDir.delete();
        }
    }

    /**
     * Regression test for mixed page-size JBIG2 dimensions (Bug 3).
     *
     * Verifies that per-page dimensions are correctly stored in each
     * CompressionResult when different pages have different sizes.
     */
    @Test
    void compressAllFromDir_preservesPerPageDimensions() throws IOException {
        JBIG2Compressor realCompressor = new JBIG2Compressor();
        assumeJbig2encAvailable(realCompressor);

        // Different page sizes: page 0 = 100x200, page 1 = 150x250
        File maskDir = new File("temp", "mrcpdf-jbig2-mixed-"
            + System.nanoTime());
        maskDir.mkdirs();
        try {
            for (int i = 0; i < 2; i++) {
                int w = i == 0 ? 100 : 150;
                int h = i == 0 ? 200 : 250;
                BufferedImage mask = new BufferedImage(w, h, BufferedImage.TYPE_BYTE_BINARY);
                for (int x = 10; x < w - 10; x++) {
                    mask.setRGB(x, h / 2, 0xFF000000);
                }
                ImageIO.write(mask, "png", new File(maskDir, "mask-" + i + ".png"));
            }

            JBIG2Compressor.BatchResult result = realCompressor.compressAllFromDir(maskDir, 2,
                new int[]{100, 150}, new int[]{200, 250});
            assertNotNull(result, "BatchResult should not be null");
            assertEquals(2, result.getPages().size());

            assertEquals(100, result.getPages().get(0).getWidth(),
                "Page 0 width should be 100");
            assertEquals(200, result.getPages().get(0).getHeight(),
                "Page 0 height should be 200");
            assertEquals(150, result.getPages().get(1).getWidth(),
                "Page 1 width should be 150");
            assertEquals(250, result.getPages().get(1).getHeight(),
                "Page 1 height should be 250");
        } finally {
            for (File f : maskDir.listFiles()) f.delete();
            maskDir.delete();
        }
    }

    /**
     * Error-surfacing regression: a binary that exists and is marked executable
     * but cannot actually be started -- here a directory, which passes the
     * {@code canExecute()} check -- makes {@code ProcessBuilder.start()} throw
     * IOException. The batch used to propagate that and abort the entire run; it
     * must instead log and fall back to CCITT G4.
     */
    @Test
    void compressAllFromDir_unrunnableBinary_fallsBackInsteadOfAborting() throws IOException {
        Files.createDirectories(Path.of("temp"));
        Path unrunnable = Files.createTempDirectory(Path.of("temp"), "mrcpdf-unrunnable-jbig2-");
        File maskDir = new File("temp", "mrcpdf-jbig2-unrunnable-" + System.nanoTime());
        maskDir.mkdirs();
        writePngMask(maskDir, 0, 50, 50);

        String prev = System.getProperty("mrcpdf.jbig2enc.binary");
        System.setProperty("mrcpdf.jbig2enc.binary", unrunnable.toString());
        try {
            JBIG2Compressor.BatchResult result = new JBIG2Compressor()
                    .compressAllFromDir(maskDir, 1, new int[]{50}, new int[]{50});
            assertNull(result, "an unrunnable binary must fall back, not abort the run");
        } finally {
            restoreProperty("mrcpdf.jbig2enc.binary", prev);
            deleteTree(maskDir);
            Files.deleteIfExists(unrunnable);
        }
    }

    /**
     * A jbig2enc run that hangs must be killed by a timeout, not left to stall
     * the whole batch. The timeout trips the same fallback as any other failure.
     */
    @Test
    void compressAllFromDir_timedOutBinary_fallsBack() throws IOException {
        Files.createDirectories(Path.of("temp"));
        Path slow = Files.createTempFile(Path.of("temp"), "mrcpdf-slow-jbig2-", ".sh");
        // exec replaces the shell with sleep, so there is only one process (as
        // with the real single-process jbig2enc): a plain `sleep` would leave a
        // grandchild holding the inherited pipes open after the timeout kill.
        Files.writeString(slow, "#!/bin/sh\nexec sleep 8\n");
        assertTrue(slow.toFile().setExecutable(true), "test script must be executable");
        File maskDir = new File("temp", "mrcpdf-jbig2-slow-" + System.nanoTime());
        maskDir.mkdirs();
        writePngMask(maskDir, 0, 50, 50);

        String prev = System.getProperty("mrcpdf.jbig2enc.binary");
        System.setProperty("mrcpdf.jbig2enc.binary", slow.toString());
        try {
            long t0 = System.nanoTime();
            // 300 ms budget: the 8 s script must be killed and fall back quickly.
            JBIG2Compressor.BatchResult result = new JBIG2Compressor("", 300L)
                    .compressAllFromDir(maskDir, 1, new int[]{50}, new int[]{50});
            long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;
            assertNull(result, "a timed-out run must fall back to CCITT");
            assertTrue(elapsedMs < 4000,
                    "the timeout must cut the run short, elapsed=" + elapsedMs + "ms");
        } finally {
            restoreProperty("mrcpdf.jbig2enc.binary", prev);
            deleteTree(maskDir);
            Files.deleteIfExists(slow);
        }
    }

    /**
     * Skips the calling test unless jbig2enc actually works on this machine.
     *
     * This probes the real batch entry point with a single mask named exactly as
     * production names them ({@code mask-0.png}).  The old probe wrote
     * {@code mask-0.bmp}, which production never looks for, so the probe always
     * failed and the caller {@code return}ed early -- reporting a pass without
     * ever running an assertion.  An unavailable binary is now an explicit
     * assumption failure: JUnit reports the test as *skipped*, which is visible
     * in the build output and cannot be mistaken for coverage.
     */
    private static void assumeJbig2encAvailable(JBIG2Compressor compressor) throws IOException {
        Files.createDirectories(Path.of("temp"));
        BufferedImage probe = new BufferedImage(50, 50, BufferedImage.TYPE_BYTE_BINARY);
        probe.setRGB(25, 25, 0xFF000000);
        // Deliberately the single-image path, NOT compressAllFromDir. If the probe
        // used the batch entry point, a break in the shared-dictionary handling
        // would make the probe return null and downgrade this test to *skipped* --
        // turning a red test green again, which is the exact defect being fixed
        // here. The single-image path shares only binary discovery with the batch
        // path, so it reports honestly on whether jbig2enc works at all.
        JBIG2Compressor.CompressionResult result = compressor.compress(probe);
        Assumptions.assumeTrue(result != null && result.isJbig2(),
            "jbig2enc unavailable on this machine — batch symbol-dictionary path"
            + " cannot be exercised (SKIPPED, not passed)");
    }

    private static void writePngMask(File dir, int index, int w, int h) throws IOException {
        BufferedImage mask = new BufferedImage(w, h, BufferedImage.TYPE_BYTE_BINARY);
        mask.setRGB(w / 2, h / 2, 0xFF000000);
        ImageIO.write(mask, "png", new File(dir, "mask-" + index + ".png"));
    }

    private static void restoreProperty(String key, String previous) {
        if (previous == null) System.clearProperty(key);
        else System.setProperty(key, previous);
    }

    private static void deleteTree(File dir) {
        File[] children = dir.listFiles();
        if (children != null) {
            for (File f : children) f.delete();
        }
        dir.delete();
    }
}
