package com.mrcpdf;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Truth table for {@link MrcPdf#runMrc}, the conjunction the whole pipeline is
 * built on: a page is MRC-compressed only when it is <em>not</em> digital
 * native <em>and</em> the screen says it is worth compressing.
 *
 * <p>Every combination of the four inputs is spelled out here rather than
 * inferred through the worker's branch structure, because the worker has no
 * cheap way to be observed: a wrong conjunction shows up as a missing or
 * unwanted image object in a rendered PDF, which is a slow and indirect signal.
 */
class MrcPdfTest {

    @Test
    void digitalNativePageIsNeverCompressed() {
        for (boolean useMrc : new boolean[]{true, false}) {
            for (boolean gateEnabled : new boolean[]{true, false}) {
                for (boolean screenGood : new boolean[]{true, false}) {
                    assertFalse(MrcPdf.runMrc(true, useMrc, gateEnabled, screenGood),
                            "a native page passes through even when the screen calls it good"
                                    + " (useMrc=" + useMrc + ", gate=" + gateEnabled
                                    + ", good=" + screenGood + ")");
                }
            }
        }
    }

    @Test
    void nonNativePageFollowsTheScreen() {
        assertTrue(MrcPdf.runMrc(false, true, true, true),
                "a non-native page the screen calls good must be compressed");
        assertFalse(MrcPdf.runMrc(false, true, true, false),
                "a non-native page the screen calls not-good must keep the fidelity path");
    }

    @Test
    void mrcOffCompressesNothing() {
        assertFalse(MrcPdf.runMrc(false, false, true, true));
        assertFalse(MrcPdf.runMrc(false, false, true, false));
        assertFalse(MrcPdf.runMrc(false, false, false, true));
        assertFalse(MrcPdf.runMrc(false, false, false, false));
    }

    @Test
    void gateDisabledCompressesEveryNonNativePage() {
        assertTrue(MrcPdf.runMrc(false, true, false, true),
                "with the gate off the screen must not be consulted");
        assertTrue(MrcPdf.runMrc(false, true, false, false),
                "with the gate off a not-good page is compressed anyway");
    }
}
