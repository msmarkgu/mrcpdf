package com.mrcpdf;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The expansion guard.
 *
 * <p>MRC can make a file larger: an already-optimized bilevel page gains a
 * background JPEG that is larger than the mask it replaced. The tool must
 * notice and say so rather than shipping a bigger file under the banner of
 * compression.
 */
class ExpansionGuardTest {

    private static final long KB = 1024L;
    private static final long MB = 1024L * KB;

    @Test
    void silentWhenOutputIsSmaller() {
        assertNull(MrcPdf.expansionNotice(10 * MB, 4 * MB, false),
            "a 2.5x reduction is the normal case and must not warn");
    }

    @Test
    void warnsWhenOutputIsLarger() {
        assertNotNull(MrcPdf.expansionNotice(4 * MB, 10 * MB, false),
            "an expansion is the whole reason this guard exists");
    }

    @Test
    void equalSizeIsNotAnExpansion() {
        assertNull(MrcPdf.expansionNotice(4 * MB, 4 * MB, false),
            "equal size is not an expansion and must stay quiet");
    }

    @Test
    void reportsHowMuchLarger() {
        String msg = MrcPdf.expansionNotice(1000, 2000, false);
        assertTrue(msg.contains("100.0%"),
            "the growth should be quantified so the user can judge it: " + msg);
    }

    @Test
    void namesBothSizes() {
        String msg = MrcPdf.expansionNotice(2 * MB, 3 * MB, false);
        assertTrue(msg.contains("2.0 MB") && msg.contains("3.0 MB"),
            "both sizes so the user can see the comparison: " + msg);
    }

    @Test
    void suggestsTheLikelyCause() {
        String msg = MrcPdf.expansionNotice(1000, 2000, false);
        assertTrue(msg.contains("already well compressed"),
            "an unexplained warning is not actionable: " + msg);
    }

    @Test
    void defaultModeSuggestsTheFlag() {
        assertTrue(MrcPdf.expansionNotice(1000, 2000, false)
            .contains("--keep-smaller-only"),
            "default mode must point at the opt-in that prevents this");
    }

    @Test
    void keepSmallerModeReportsThatOriginalWasKept() {
        String msg = MrcPdf.expansionNotice(1000, 2000, true);
        assertTrue(msg.contains("original bytes were kept"),
            "in keep-smaller mode the user must be told the file was NOT the MRC"
            + " result, or they will measure the wrong thing: " + msg);
    }

    @Test
    void keepSmallerModeDoesNotAlsoSuggestTheFlag() {
        String msg = MrcPdf.expansionNotice(1000, 2000, true);
        assertTrue(!msg.contains("Pass --keep-smaller-only"),
            "do not suggest a flag the user already passed: " + msg);
    }

    @Test
    void zeroLengthInputDoesNotDivideByZero() {
        String msg = MrcPdf.expansionNotice(0, 1000, false);
        assertNotNull(msg, "an empty input that produces output is still worth flagging");
        assertTrue(msg.contains("unknown"),
            "no percentage is computable from a zero-byte input: " + msg);
    }
}