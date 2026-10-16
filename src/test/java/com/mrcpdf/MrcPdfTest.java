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

    // ── per-page effective render DPI ──────────────────────────────────

    @Test
    void effectiveRenderDpi_neverExceedsConfigured() {
        assertEquals(300f, MrcPdf.effectiveRenderDpi(300f, 600.0), 1e-4,
                "a source richer than the configured target is still rendered at the target");
        assertEquals(300f, MrcPdf.effectiveRenderDpi(300f, 300.0), 1e-4);
    }

    @Test
    void effectiveRenderDpi_dropsToNativeWhenLower() {
        assertEquals(72f, MrcPdf.effectiveRenderDpi(300f, 72.0), 1e-4,
                "a low-resolution source renders at its own resolution, not the configured one");
    }

    @Test
    void effectiveRenderDpi_defaultsToConfiguredWhenUnknown() {
        assertEquals(300f, MrcPdf.effectiveRenderDpi(300f, 0.0), 1e-4,
                "an unmeasured page keeps the configured resolution");
    }

    @Test
    void effectiveRenderDpi_isFlooredToAvoidDegenerateRenders() {
        assertEquals(MrcPdf.MIN_RENDER_DPI, MrcPdf.effectiveRenderDpi(300f, 5.0), 1e-4,
                "an implausibly small measurement is clamped up to the floor");
    }
}
