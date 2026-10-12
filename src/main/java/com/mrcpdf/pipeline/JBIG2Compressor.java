package com.mrcpdf.pipeline;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.graphics.image.CCITTFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;

import com.mrcpdf.util.PlatformUtils;
import com.mrcpdf.util.PlatformUtils.Arch;
import com.mrcpdf.util.PlatformUtils.Os;
import com.mrcpdf.util.Settings;
import com.mrcpdf.util.SubprocessRunner;

/**
 * Compresses a binary foreground mask via jbig2enc subprocess,
 * falling back to CCITT Group 4 fax encoding via PDFBox when
 * the native binary is unavailable.
 *
 * jbig2enc flags:
 *   -p  — Produce a global symbol dictionary (shared across pages).
 *   -s  — Refine smearing to improve symbol matching.
 */
public class JBIG2Compressor {

    /**
     * Wall-clock limit for one jbig2enc invocation when neither the caller nor
     * settings says otherwise. A batch that hangs is worse than one that falls
     * back, so even the default is a bound rather than no limit.
     */
    private static final int DEFAULT_TIMEOUT_MS = 300_000;

    private final String nativeDir;

    /**
     * Explicit timeout in milliseconds, or &le;0 to read
     * {@code jbig2enc.timeoutMs} from settings (falling back to
     * {@link #DEFAULT_TIMEOUT_MS}).
     */
    private final long timeoutMs;

    public JBIG2Compressor() {
        this("deps/jbig2enc");
    }

    public JBIG2Compressor(String nativeDir) {
        this(nativeDir, 0L);
    }

    public JBIG2Compressor(String nativeDir, long timeoutMs) {
        this.nativeDir = nativeDir;
        this.timeoutMs = timeoutMs;
    }

    private long resolveTimeoutMs() {
        if (timeoutMs > 0) return timeoutMs;
        return Settings.getInstance().getInt("jbig2enc.timeoutMs", DEFAULT_TIMEOUT_MS);
    }

    /**
     * Holds compressed foreground-mask data.
     *
     * isJbig2  true = JBIG2 format (from jbig2enc); false = CCITT G4 fallback.
     * data     The compressed byte stream.
     * width    Original mask width  in pixels.
     * height   Original mask height in pixels.
     */
    public static class CompressionResult {
        private final boolean isJbig2;
        private final byte[] data;
        private final int width;
        private final int height;

        public CompressionResult(byte[] data, int width, int height, boolean isJbig2) {
            this.data = data;
            this.width = width;
            this.height = height;
            this.isJbig2 = isJbig2;
        }

        public boolean isJbig2() { return isJbig2; }
        public byte[] getData() { return data; }
        public int getWidth() { return width; }
        public int getHeight() { return height; }
    }

    /**
     * Holds the output of a batch JBIG2 compression run:
     * a single shared global symbol dictionary and per-page page data.
     */
    public static class BatchResult {
        private final byte[] globalSym;
        private final List<CompressionResult> pages;

        public BatchResult(byte[] globalSym, List<CompressionResult> pages) {
            this.globalSym = globalSym;
            this.pages = pages;
        }

        public byte[] getGlobalSym() { return globalSym; }
        public List<CompressionResult> getPages() { return pages; }
    }

    /**
     * Compresses foreground mask PNGs in a single jbig2enc invocation
     * with the {@code -p} flag, producing one shared symbol dictionary
     * and per-page JBIG2 streams.
     *
     * @param maskDir    Directory containing mask PNGs named {@code mask-0.png},
     *                   {@code mask-1.png}, ..., {@code mask-{pageCount-1}.png}.
     * @param pageCount  Number of masks / pages.
     * @param imgWidth   Width of each mask in pixels.
     * @param imgHeight  Height of each mask in pixels.
     * @return BatchResult with shared global sym and per-page data, or null if
     *         jbig2enc is unavailable or fails (caller should use CCITT fallback).
     */
    public BatchResult compressAllFromDir(File maskDir, int pageCount, int[] imgWidths, int[] imgHeights) throws IOException {
        File jbig2enc = findJbig2enc();
        if (jbig2enc == null) return null;

        List<String> imagePaths = new ArrayList<>(pageCount);
        for (int i = 0; i < pageCount; i++) {
            imagePaths.add(new File(maskDir, "mask-" + i + ".png").getAbsolutePath());
        }

        // Use a UUID-scoped basename to keep JBIG2 output files (.sym, .0000, ...)
        // separate from input BMPs and avoid collision between concurrent runs.
        String basename = new File(maskDir, "jbig2-" + UUID.randomUUID()).getAbsolutePath();

        String flags = Settings.getInstance().getString("jbig2enc.flags", "-p -s");
        List<String> args = new ArrayList<>();
        String[] parts = flags.split("\\s+");
        for (int i = 0; i < parts.length; i++) {
            if ("-b".equals(parts[i])) { i++; continue; }
            if (!parts[i].isEmpty()) args.add(parts[i]);
        }
        args.add("-b");
        args.add(basename);
        args.addAll(imagePaths);

        SubprocessRunner.Result result;
        try {
            result = SubprocessRunner.run(jbig2enc, args, resolveTimeoutMs());
        } catch (IOException e) {
            // The binary could not even be started (missing interpreter, wrong
            // architecture, exec permission, timeout). Surface it and let the
            // caller use CCITT G4 rather than aborting the whole document.
            System.err.println("  Warning: jbig2enc could not run (" + e.getMessage()
                    + ") — using CCITT G4 fallback.");
            return null;
        }
        if (result.getExitCode() != 0) {
            System.err.println("  Warning: jbig2enc exited " + result.getExitCode()
                    + formatStderr(result) + " — using CCITT G4 fallback.");
            return null;
        }

        // Read output files, then delete them immediately
        try {
            // Read shared global symbol dictionary
            File symFile = new File(basename + ".sym");
            byte[] globalSym = symFile.exists() ? Files.readAllBytes(symFile.toPath()) : new byte[0];
            if (globalSym.length == 0) {
                System.err.println("  Warning: jbig2enc produced no symbol dictionary"
                        + " — using CCITT G4 fallback.");
                return null;
            }

            // Read per-page data (jbig2enc -p writes <basename>.0000, .0001, ...)
            List<CompressionResult> pages = new ArrayList<>(pageCount);
            for (int i = 0; i < pageCount; i++) {
                File pageFile = new File(basename + "." + String.format("%04d", i));
                byte[] pageData = pageFile.exists() ? Files.readAllBytes(pageFile.toPath()) : new byte[0];
                pages.add(new CompressionResult(pageData, imgWidths[i], imgHeights[i], true));
            }

            return new BatchResult(globalSym, pages);
        } finally {
            new File(basename + ".sym").delete();
            for (int i = 0; i < pageCount; i++) {
                new File(basename + "." + String.format("%04d", i)).delete();
            }
        }
    }

    /**
     * Attempts JBIG2 compression via jbig2enc; falls back to CCITT G4 on failure.
     *
     * @param foregroundMask  Binary image (TYPE_BYTE_BINARY, black=foreground).
     * @return CompressionResult with either JBIG2 or CCITT G4 data.
     */
    public CompressionResult compress(BufferedImage foregroundMask) throws IOException {
        File jbig2enc = findJbig2enc();
        if (jbig2enc != null) {
            try {
                return compressWithJbig2enc(jbig2enc, foregroundMask);
            } catch (IOException e) {
                // jbig2enc failed → fall through to fallback
                System.err.println("  Warning: jbig2enc failed (" + e.getMessage()
                        + ") — using CCITT G4 fallback.");
            }
        }
        return compressWithFallback(foregroundMask);
    }

    /**
     * Formats a failed run's stderr for a one-line warning: empty when there was
     * none, otherwise the first line (a jbig2enc failure can be verbose).
     */
    private static String formatStderr(SubprocessRunner.Result result) {
        String err = result.getStderrString().trim();
        if (err.isEmpty()) return "";
        int nl = err.indexOf('\n');
        if (nl >= 0) err = err.substring(0, nl) + " ...";
        return ": " + err;
    }

    /**
     * Locates the jbig2enc binary for the current OS and architecture.
     *
     * Search order:
     *   1. System property "mrcpdf.jbig2enc.binary" (full path to a custom binary).
     *   2. {nativeDir}/{os}-{arch}/jbig2enc (e.g. linux-arm64, mac-arm64).
     *   3. {nativeDir}/{os}/jbig2enc (legacy single-arch layout).
     * On Windows the binary name is "jbig2enc.exe".
     *
     * @return The executable File, or null if not found.
     */
    private File findJbig2enc() {
        String custom = System.getProperty("mrcpdf.jbig2enc.binary");
        if (custom != null && !custom.isEmpty()) {
            File f = new File(custom);
            if (f.canExecute()) return f;
        }

        Os os = PlatformUtils.detectOs();
        Arch arch = PlatformUtils.detectArch();
        String osName;
        switch (os) {
            case WINDOWS: osName = "win"; break;
            case MAC: osName = "mac"; break;
            default: osName = "linux";
        }
        String archName;
        switch (arch) {
            case AARCH64: archName = "arm64"; break;
            default: archName = "x64";
        }
        String exeName = (os == Os.WINDOWS) ? "jbig2enc.exe" : "jbig2enc";

        File exe = new File(nativeDir + "/" + osName + "-" + archName, exeName);
        if (exe.canExecute()) return exe;
        exe = new File(nativeDir + "/" + osName, exeName);
        if (exe.canExecute()) return exe;

        System.out.println(
            "  Warning: jbig2enc not found for " + osName + "-" + archName
                + " — using CCITT G4 fallback. Install jbig2enc or set"
                + " -Dmrcpdf.jbig2enc.binary=<path>.");
        return null;
    }

    /**
     * Runs jbig2enc and concatenates global sym + page jbig2 into one byte array.
     *
     * PDF JBIG2Decode streams require global segments (symbol dictionary)
     * followed by page data — exactly the .sym + .jbig2 layout that
     * jbig2enc produces.
     */
    private CompressionResult compressWithJbig2enc(File jbig2enc, BufferedImage mask) throws IOException {
        Files.createDirectories(Path.of("temp"));
        File tempBmp = Files.createTempFile(Path.of("temp"), "mrcpdf-jbig2-", ".bmp").toFile();

        try {
            // Write the mask inside the try so the finally block always cleans up,
            // even if ImageIO.write fails (no deleteOnExit / global shutdown hook).
            javax.imageio.ImageIO.write(mask, "bmp", tempBmp);

            String prefix = tempBmp.getAbsolutePath().replaceAll("\\.bmp$", "");

            String flags = Settings.getInstance().getString("jbig2enc.flags", "-p -s");
            java.util.List<String> args = new java.util.ArrayList<>();
            String[] parts = flags.split("\\s+");
            for (int i = 0; i < parts.length; i++) {
                if ("-b".equals(parts[i])) { i++; continue; }
                if (!parts[i].isEmpty()) args.add(parts[i]);
            }
            args.add("-b");
            args.add(prefix);
            args.add(tempBmp.getAbsolutePath());

            SubprocessRunner.Result result = SubprocessRunner.run(jbig2enc, args, resolveTimeoutMs());
            if (result.getExitCode() != 0) {
                throw new IOException("jbig2enc failed: " + result.getStderrString());
            }

            // With -p flag, jbig2enc produces <prefix>.sym + <prefix>.0000
            File symFile = new File(prefix + ".sym");
            File pageFile = new File(prefix + ".0000");

            byte[] symData = symFile.exists() ? java.nio.file.Files.readAllBytes(symFile.toPath()) : new byte[0];
            byte[] pageData = pageFile.exists() ? java.nio.file.Files.readAllBytes(pageFile.toPath()) : new byte[0];

            byte[] combined = new byte[symData.length + pageData.length];
            System.arraycopy(symData, 0, combined, 0, symData.length);
            System.arraycopy(pageData, 0, combined, symData.length, pageData.length);

            return new CompressionResult(combined, mask.getWidth(), mask.getHeight(), true);
        } finally {
            String prefix = tempBmp.getAbsolutePath().replaceAll("\\.bmp$", "");
            for (String ext : new String[]{".bmp", ".sym", ".0000"}) {
                File f = new File(prefix + ext);
                if (f.exists()) f.delete();
            }
        }
    }

    /**
     * Fallback: encodes the mask as CCITT Group 4 fax using PDFBox's CCITTFactory.
     */
    private CompressionResult compressWithFallback(BufferedImage mask) throws IOException {
        // Create a throwaway PDDocument to use CCITTFactory
        try (PDDocument doc = new PDDocument()) {
            PDImageXObject ccitt = CCITTFactory.createFromImage(doc, mask);
            return new CompressionResult(extractStreamBytes(ccitt), mask.getWidth(), mask.getHeight(), false);
        }
    }

    private static byte[] extractStreamBytes(PDImageXObject image) throws IOException {
        try (java.io.InputStream is = image.createInputStream()) {
            return is.readAllBytes();
        }
    }
}
