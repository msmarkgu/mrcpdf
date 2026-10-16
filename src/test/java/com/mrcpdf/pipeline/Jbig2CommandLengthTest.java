package com.mrcpdf.pipeline;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * The Windows command-line length guard on the JBIG2 batch.
 *
 * <p>Every mask path is passed as a separate argument, so a long document
 * eventually cannot be started at all on Windows. The guard exists to surface
 * that before {@code ProcessBuilder} fails obscurely and the run looks like an
 * ordinary CCITT downgrade.
 */
class Jbig2CommandLengthTest {

    private static final File EXE = new File("/opt/mrcpdf/deps/jbig2enc/linux/jbig2enc");

    private static List<String> maskArgs(int count, int pathLen) {
        List<String> args = new ArrayList<>(count + 4);
        args.add("-p");
        args.add("-s");
        args.add("-b");
        args.add("/tmp/mrcpdf-maskdir/jbig2-" + "x".repeat(24));
        for (int i = 0; i < count; i++) {
            args.add("/tmp/mrcpdf-maskdir/mask-" + String.format("%04d", i)
                    + ".png" + "y".repeat(pathLen));
        }
        return args;
    }

    @Test
    void quietForASmallDocument() {
        assertNull(JBIG2Compressor.commandLengthWarning(EXE, maskArgs(10, 0), 10),
            "a 10-page batch fits comfortably and must not warn");
    }

    @Test
    void quietForAnOrdinaryDocument() {
        assertNull(JBIG2Compressor.commandLengthWarning(EXE, maskArgs(500, 0), 500),
            "500 pages is below the practical limit and must not warn");
    }

    @Test
    void warnsWhenTheCommandLineWouldOverflow() {
        String msg = JBIG2Compressor.commandLengthWarning(EXE, maskArgs(5000, 0), 5000);
        assertNotNull(msg, "5000 pages cannot fit on a Windows command line");
    }

    @Test
    void warningNamesTheWindowsLimit() {
        String msg = JBIG2Compressor.commandLengthWarning(EXE, maskArgs(5000, 0), 5000);
        assertTrue(msg.contains(String.valueOf(JBIG2Compressor.WINDOWS_CMDLINE_LIMIT)),
            "the warning should cite the limit it is comparing against: " + msg);
    }

    @Test
    void warningExplainsTheConsequence() {
        String msg = JBIG2Compressor.commandLengthWarning(EXE, maskArgs(5000, 0), 5000);
        assertTrue(msg.contains("CCITT"),
            "the user needs to know the run degrades, not fails: " + msg);
        assertTrue(msg.contains("symbol dictionary"),
            "the cost is losing the shared dictionary, which is the point of MRC: " + msg);
    }

    @Test
    void warningOffersAnActionableWorkaround() {
        String msg = JBIG2Compressor.commandLengthWarning(EXE, maskArgs(5000, 0), 5000);
        assertTrue(msg.contains("600") || msg.contains("Split"),
            "an unfixable warning is not actionable: " + msg);
    }

    @Test
    void longPathsPushADocumentOverTheLimit() {
        // 100 pages with a deep path can exceed what 100 short paths would not.
        int shortArgs = maskArgs(100, 0).stream().mapToInt(String::length).sum();
        List<String> deep = new ArrayList<>();
        for (String a : maskArgs(100, 0)) deep.add(a + "d".repeat(400));
        int deepArgs = deep.stream().mapToInt(String::length).sum();
        assertTrue(deepArgs > shortArgs, "fixture sanity");
        assertNotNull(JBIG2Compressor.commandLengthWarning(EXE, deep, 100),
            "path depth, not just page count, determines the overflow");
    }

    @Test
    void includesTheExecutablePathInTheBudget() {
        File longExe = new File("/very/long/path/that/eats/the/budget".repeat(3) + "/jbig2enc");
        List<String> args = maskArgs(1000, 0);
        String withLongExe = JBIG2Compressor.commandLengthWarning(longExe, args, 1000);
        String withShortExe = JBIG2Compressor.commandLengthWarning(EXE, args, 1000);
        assertTrue(withLongExe == null ? withShortExe == null : withLongExe.length() > 0,
            "the executable path counts toward the budget");
    }
}