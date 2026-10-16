package com.mrcpdf.quality.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.mrcpdf.model.TextBlock;

class MrcReadinessTest {

    private static final int DPI = 300;
    private static final double PT = 300.0 / 72.0;

    // ── Aggregation algebra (design doc §4.2) ───────────────────────────────

    @Test
    void allDimensionsPerfectScoresOne() {
        MrcReadiness.Breakdown b = perfect(1.0, 1.0, 1.0, 1.0);
        assertEquals(1.0, b.sVisual(), 1e-9, "clean glyphs must be pristine");
        assertEquals(1.0, b.sMask(), 1e-9, "stable binarization must be pristine");
        assertEquals(1.0, b.sOcr(), 1e-9, "trusted OCR must be pristine");
        assertEquals(1.0, b.sBg(), 1e-9, "clean background must be pristine");
        assertEquals(1.0, b.kappa(), 1e-9);
        assertEquals(1.0, b.bottleneck(), 1e-9, "kappa >= TAU_CRIT must not penalize");
        assertEquals(1.0, b.score(), 1e-9, "a flawless page scores exactly 1");
        assertEquals("good", b.tier());
    }

    @Test
    void geometricMeanIsGeometricNotArithmetic() {
        MrcReadiness.Breakdown b = perfect(0.40, 0.40, 0.40, 0.40);
        assertEquals(0.40, b.rGeom(), 1e-9, "equal dimensions must collapse to themselves");
        // A linear mean would be (0.90+0.40+0.40+0.40)/4 = 0.525; the geometric
        // mean must be far lower because it is dragged down by the weak terms.
        assertTrue(b.rGeom() < 0.50, "weak dimensions must dominate, got " + b.rGeom());
    }

    @Test
    void bottleneckPenaltyOnlyFiresBelowTauCrit() {
        MrcReadiness.Breakdown above = perfect(0.90, 0.90, 0.90, 0.36);
        assertEquals(1.0, above.bottleneck(), 1e-9, "just above TAU_CRIT is unpenalized");
        assertEquals(above.rGeom(), above.score(), 1e-9);

        MrcReadiness.Breakdown below = perfect(0.90, 0.90, 0.90, 0.20);
        assertTrue(below.bottleneck() < 1.0, "below TAU_CRIT must be penalized");
        assertTrue(below.score() < below.rGeom(), "score must drop below rGeom");
    }

    @Test
    void bottleneckIsTheWorstDimensionNotTheAverage() {
        // Good margins cannot rescue destroyed glyphs (Liebig's law).
        MrcReadiness.Breakdown b = perfect(0.20, 0.95, 0.95, 0.95);
        assertEquals(0.20, b.kappa(), 1e-9, "kappa is the minimum");
        assertTrue(b.score() < b.rGeom() * 0.6, "a single bad dimension must dominate");
    }

    @Test
    void tierBoundariesMatchDesignDoc() {
        assertEquals("good", perfect(0.95, 0.95, 0.95, 0.95).tier());
        assertEquals("middle", perfect(0.70, 0.70, 0.70, 0.70).tier());
        assertEquals("bad", perfect(0.44, 0.44, 0.44, 0.44).tier());
    }

    @Test
    void scoreAlwaysStaysInUnitInterval() {
        for (double s : new double[]{0.0, 0.05, 0.5, 1.0}) {
            MrcReadiness.Breakdown b = perfect(s, s, s, s);
            assertTrue(b.score() >= 0.0 && b.score() <= 1.0,
                    "score out of range: " + b.score());
        }
    }

    // ── Glyph locality: the whole point of R_MRC ────────────────────────────

    @Test
    void textRegionMaskCoversGlyphBoxes() {
        List<TextBlock> boxes = List.of(
                new TextBlock("Recurrent", rect(10, 10, 80, 20), 90),
                new TextBlock("Networks", rect(100, 10, 90, 20), 90));
        boolean[] region = regionMask(boxes, 400, 200);
        assertTrue(region[10 * 400 + 10], "box origin must be inside the text region");
        assertTrue(region[29 * 400 + 89], "box far corner must be inside the text region");
        assertTrue(!region[150 * 400 + 350], "empty margin must stay outside");
    }

    @Test
    void masksAndFragmentsRespondToRealInk() {
        int w = 420, h = 160;
        int[] page = syntheticPage();
        MrcReadiness.Breakdown b = MrcReadiness.compute(page, w, h, SYNTH_BOXES);
        // Absolute sub-score values are calibrated against a measured clean
        // scan of a real page, and this fixture is a near-ideal raster with
        // ~1 component per character, so it will not reproduce a real page's
        // absolute numbers.  What must hold is that a degraded page scores
        // strictly below the same content scanned cleanly.
        MrcReadiness.Breakdown dirty =
                MrcReadiness.compute(gaussianBlur(page, 2.0), w, h, SYNTH_BOXES);
        assertTrue(dirty.mBlur() < b.mBlur(),
                "blur must lower mBlur: " + dirty.mBlur() + " vs " + b.mBlur());
        assertTrue(dirty.mContrast() <= b.mContrast(),
                "blur must not raise mContrast: " + dirty.mContrast() + " vs " + b.mContrast());
        assertTrue(b.sMask() > dirty.sMask(),
                "clean ink must mask better than blurred ink: "
                        + b.sMask() + " vs " + dirty.sMask());
        assertTrue(b.sVisual() > dirty.sVisual(),
                "clean ink must be visually better than blurred ink: "
                        + b.sVisual() + " vs " + dirty.sVisual());
    }

    /**
     * Pins the measured blur band and records the numbers it came from.
     *
     * <p>BLUR_GOOD is the 10th percentile re-blur residual of 125 clean scans of
     * real pages and BLUR_BAD the 90th percentile of the destroyed anchor; see
     * {@code notes/mrc-calibration.md}.  The synthetic fixture below is a
     * near-ideal raster, so only the ordering is asserted, not the raw levels.
     */
    @Test
    void blurReferenceCalibration() {
        double hard = creteResidual(syntheticPage());
        double soft = creteResidual(gaussianBlur(syntheticPage(), 2.0));
        assertTrue(hard > 5 * soft,
                "a hard edge must hold far more acutance than a sigma=2 edge: "
                        + hard + " vs " + soft);
        assertTrue(MrcRefs.BLUR_GOOD > MrcRefs.BLUR_BAD,
                "the band must be ordered so more residual is better: "
                        + MrcRefs.BLUR_GOOD + " vs " + MrcRefs.BLUR_BAD);
        assertTrue(MrcRefs.BLUR_GOOD >= 3 * MrcRefs.BLUR_BAD,
                "the band must span at least 3x between the worst clean page and "
                        + "the destroyed anchor, got " + MrcRefs.BLUR_GOOD
                        + " vs " + MrcRefs.BLUR_BAD);
    }

    /**
     * The property that actually matters: more Gaussian blur, lower score.
     *
     * <p>Monotonicity is the contract; the absolute floor is not asserted
     * because a near-ideal synthetic raster still clears a band calibrated on
     * real scans.  The end-to-end penalty for heavy blur is covered by
     * {@code calibratedBandsPutCleanScansInTheGoodBand}.
     */
    @Test
    void blurResponseIsMonotoneInSigma() {
        int[] base = syntheticPage();
        double previous = Double.MAX_VALUE;
        for (double sigma : new double[]{0.0, 0.5, 1.0, 1.5, 2.0, 3.0, 4.0}) {
            double m = MrcReadiness.compute(gaussianBlur(base, sigma), 420, 160, SYNTH_BOXES)
                    .mBlur();
            assertTrue(m <= previous + 1e-9,
                    "mBlur rose at sigma=" + sigma + ": " + m + " > " + previous);
            previous = m;
        }
    }

    /**
     * The guard that was missing when the bands were derived mechanically: the
     * calibration must actually place a clean scan of a real page in the good
     * band, and a destroyed one below the bad cut.
     *
     * <p>Measured over the 125-page review corpus (300 DPI, JPEG q0.92):
     * clean median 0.83, photocopy 0.48, destroyed 0.10.  The thresholds below
     * are the ones the labeler uses.
     */
    @Test
    void calibratedBandsPutCleanScansInTheGoodBand() {
        // The measured populations put the clean median at 0.83 and the
        // photocopy median at 0.48, so the cuts have to sit between them:
        // a blurred-but-readable page must land in the middle band, not in
        // "good" and not in "bad".
        assertTrue(MrcReadiness.GOOD_ABOVE < 0.83,
                "the good cut must be below the clean median, got "
                        + MrcReadiness.GOOD_ABOVE);
        assertTrue(MrcReadiness.BAD_BELOW > 0.10,
                "the bad cut must be above the destroyed median, got "
                        + MrcReadiness.BAD_BELOW);
        assertTrue(MrcReadiness.GOOD_ABOVE > MrcReadiness.BAD_BELOW,
                "the two cuts must not cross");
    }

    /**
     * Pins the measured reference constants, and pins the sBg bands against the
     * anchor distributions they were derived from.
     *
     * <p>The pinning half guards against silent drift: these are numbers pasted
     * out of a calibration run, and nothing else in the test suite would notice
     * a later edit.
     *
     * <p>The band half is the actual regression.  The sBg bad endpoints used to
     * be the 90th percentile of the destroyed anchor alone, which published
     * {@code NOISE_BAD = 0.942} while an ordinary photocopy measured 2.03 — so
     * the band did not cover the corpus it was supposed to describe, and the
     * pages it floored collapsed {@code R_mrc} to zero.  These assertions state
     * the requirement directly: the band must contain the worst value observed
     * in every degraded anchor, and the good end must stay below the clean
     * median, or sBg is again inert or again clipping.
     */
    @Test
    void sBgBandCoversEveryDegradedAnchor() {
        assertEquals(0.263484, MrcRefs.NOISE_GOOD, 1e-9);
        assertEquals(2.447642, MrcRefs.NOISE_BAD, 1e-9);
        assertEquals(0.069338, MrcRefs.UNIFORM_GOOD, 1e-9);
        assertEquals(9.034715, MrcRefs.UNIFORM_BAD, 1e-9);

        // The regression.  Each degraded anchor's own p90 must sit inside the
        // band, so no population's body is clipped.  The old endpoints came
        // from the destroyed anchor alone (noise 0.942, tile 1.470) and the
        // photocopy population measured 2.025 and 2.506 — so the published
        // "worthless background" was less dirty than an ordinary photocopy,
        // which is what floored 120 of the 500 pilot pages to R_mrc = 0.
        assertTrue(MrcRefs.NOISE_BAD >= 2.025,
                "noise band must cover the photocopy p90 (2.025), got " + MrcRefs.NOISE_BAD);
        assertTrue(MrcRefs.NOISE_BAD >= 1.003,
                "noise band must cover the debris p90 (1.003), got " + MrcRefs.NOISE_BAD);
        assertTrue(MrcRefs.UNIFORM_BAD >= 2.506,
                "uniformity band must cover the photocopy p90 (2.506), got "
                        + MrcRefs.UNIFORM_BAD);
        assertTrue(MrcRefs.UNIFORM_BAD >= 1.798,
                "uniformity band must cover the debris p90 (1.798), got "
                        + MrcRefs.UNIFORM_BAD);

        // The good ends must sit below the clean medians (0.312 / 0.116) or a
        // clean scan would not reach full credit.
        assertTrue(MrcRefs.NOISE_GOOD < 0.312, "noise good end above the clean median");
        assertTrue(MrcRefs.UNIFORM_GOOD < 0.116, "uniformity good end above the clean median");

        // Widening the band that far makes sBg a tail detector rather than a
        // gradient, which is acceptable, but it must still collapse on a
        // genuinely filthy page -- that is what protects a "good" label.
        double filthy = MrcReadiness.score(syntheticRaw(12.0, 40.0), SYNTH_BOXES, 420, 160)
                .sBg();
        assertTrue(filthy < 0.05,
                "a pathologically noisy, uneven page must fall off the sBg band, got "
                        + filthy);
    }

    /** A {@link MrcReadiness.Raw} carrying only the two background terms. */
    private static MrcReadiness.Raw syntheticRaw(double noise, double tile) {
        return new MrcReadiness.Raw(10.0, 0.12, 0.11, 0.11, 3.0, 90.0, noise, tile);
    }

    @Test
    void noOcrBoxesYieldsNoGatedConfidence() {
        int w = 200, h = 200;
        int[] page = new int[w * h];
        java.util.Arrays.fill(page, 255);
        MrcReadiness.Breakdown b = MrcReadiness.compute(page, w, h, List.of());
        assertEquals(0, b.glyphBoxes());
        assertEquals(0.0, b.sOcr(), 1e-9, "no words means no evidence of legibility");
        assertEquals(0.0, b.score(), 1e-9, "a textless page is not readable");
    }

    @Test
    void subFourPixelSpecksAreCountedAsSpeckle() {
        int w = 300, h = 300;
        int[] page = new int[w * h];
        java.util.Arrays.fill(page, 255);
        // Two-pixel specks on clean paper, well outside any text box.
        for (int i = 0; i < 200; i++) {
            int x = (i * 7) % (w - 4);
            int y = (i * 13) % (h - 4);
            page[y * w + x] = 10;
            page[y * w + x + 1] = 10;
        }
        MrcReadiness.Breakdown b = MrcReadiness.compute(page, w, h, List.of());
        assertTrue(b.mSpeckle() < 0.9, "200 specks over 0.09 MP is ~2200/MP, got " + b.mSpeckle());
    }

    /**
     * Speckle density must not depend on what is inside the text region.
     *
     * <p>The numerator counted every sub-4px component on the page while the
     * denominator counted only non-text background, so small text fragments
     * inflated the count and the paper they covered shrank the pool, at the same
     * time.  The metric tracked document content rather than paper dirt, and the
     * clean baseline it produced was ~850 blobs/MP on ordinary pages.
     *
     * <p>The fixture puts 435 paper specks across the top of the page and 625
     * isolated 2x2 "characters" across the bottom, inside word boxes.  With the
     * ratio fixed, adding the text only removes 2500 pixels from the
     * denominator and nothing from the numerator, so the density rises by
     * about 3%.  With the bug, the 2500 text pixels are counted as speckle too
     * and the density nearly quadruples.
     *
     * <p>Asserted on the <em>raw</em> density, not {@code mSpeckle}: both
     * quantities sit far past the bad endpoint, so the mapped sub-score clamps
     * to 0 either way and an assertion on it passes with the bug present.
     */
    @Test
    void speckleDensityIgnoresTextInsideWordBoxes() {
        int w = 300, h = 300;
        int[] paperOnly = pageWithSpecks(w, h);
        List<TextBlock> boxes = new ArrayList<>();

        // Inside each word box, variant "blocks" scatters 25 isolated 2x2 marks
        // (100 dark pixels, every mark exactly at SPECKLE_MAX_PX); variant
        // "solid" puts the same 100 pixels into one 10x10 component.  The dark
        // pixel count is identical, so both pages have the same background
        // area and therefore the same denominator: the only quantity that can
        // differ is the numerator.
        int[] blocks = pageWithSpecks(w, h);
        int[] solid = pageWithSpecks(w, h);
        for (int r = 0; r < 5; r++) {
            for (int c = 0; c < 5; c++) {
                int x0 = 10 + c * 50;
                int y0 = 170 + r * 25;
                for (int by = 0; by < 5; by++) {
                    for (int bx = 0; bx < 5; bx++) {
                        int x = x0 + bx * 4, y = y0 + by * 4;
                        blocks[y * w + x] = 12;
                        blocks[y * w + x + 1] = 12;
                        blocks[(y + 1) * w + x] = 12;
                        blocks[(y + 1) * w + x + 1] = 12;
                    }
                }
                for (int y = y0 + 5; y < y0 + 15; y++) {
                    for (int x = x0 + 5; x < x0 + 15; x++) {
                        solid[y * w + x] = 12;
                    }
                }
                boxes.add(new TextBlock("t" + r + c, new Rectangle(x0, y0, 20, 20), 90));
            }
        }

        double scattered = MrcReadiness.measure(blocks, w, h, boxes,
                SauvolaMask.compute(blocks, w, h)).specklePerMpx();
        double oneBlob = MrcReadiness.measure(solid, w, h, boxes,
                SauvolaMask.compute(solid, w, h)).specklePerMpx();
        double paperAlone = MrcReadiness.measure(paperOnly, w, h, List.of(),
                SauvolaMask.compute(paperOnly, w, h)).specklePerMpx();

        assertTrue(paperAlone > 0.0, "the paper specks must be counted at all");
        assertEquals(oneBlob, scattered, 1e-9,
                "the same 100 dark pixels inside a word box must count as speckle "
                        + "only if they are isolated marks, not glyph fragments");
    }

    /** White paper with isolated 2px specks across the top two thirds. */
    private static int[] pageWithSpecks(int w, int h) {
        int[] page = new int[w * h];
        java.util.Arrays.fill(page, 255);
        for (int y = 10; y < 150; y += 10) {
            for (int x = 10; x < w - 10; x += 10) {
                page[y * w + x] = 10;
                page[y * w + x + 1] = 10;
            }
        }
        return page;
    }



    @Test
    void realPageScoresAboveZeroWithUsableBreakdown() {
        int w = 300, h = 120;
        int[] page = new int[w * h];
        java.util.Arrays.fill(page, 252);
        List<TextBlock> boxes = new ArrayList<>();
        String[] words = {"Introduction", "Recurrent", "Networks", "RNNs", "sequence"};
        int x = 10;
        for (int i = 0; i < words.length; i++) {
            int ww = 40 + i * 4;
            for (int y = 30; y < 90; y++) {
                for (int dx = 0; dx < ww; dx++) {
                    if (y > 30 + (dx % (12 + i * 3)) && y < 88 - (dx % 7)) {
                        page[y * w + x + dx] = 15;
                    }
                }
            }
            boxes.add(new TextBlock(words[i], rect(x, 30, ww, 60), 88 + i));
            x += ww + 6;
        }
        MrcReadiness.Breakdown b = MrcReadiness.compute(page, w, h, boxes);
        assertEquals(5, b.glyphBoxes());
        assertTrue(b.score() > 0.0, "legible synthetic text must score above zero");
        assertTrue(b.score() <= 1.0, "score must stay normalized");
        // Five boxes is 5/50 of the evidence floor, so sOcr is scaled down even
        // though every word is real text with a high confidence.  That is the
        // gate working as intended: on a real page this few surviving boxes
        // means the text is gone, not that the page is short.
        double evidence = b.glyphBoxes() / (double) MrcReadiness.EVIDENCE_MIN_BOXES;
        assertTrue(b.sOcr() < 0.8,
                "a 5-box page is evidence-starved and must be scaled down, got " + b.sOcr());
        assertTrue(b.sOcr() > 0.0,
                "but real text must still contribute something, got " + b.sOcr());
        assertTrue(evidence < 1.0, "fixture is deliberately below the evidence floor");
    }

    /**
     * A page that has lost its text must not look like a pristine blank sheet.
     *
     * <p>This is the false positive that mattered most.  The pixel dimensions
     * score a smooth, uncluttered, ink-free page near-perfectly, and the four
     * confidence statistics were taken over however few boxes the engine
     * happened to return -- so a page with three surviving glyphs out of several
     * hundred words came out {@code good} while Tesseract recovered nothing.
     * Over-weighting a bad page only costs disk; over-weighting a good page
     * ships a visible artifact, so the gate must be unambiguous.
     */
    @Test
    @DisplayName("a page with almost no readable text cannot score good")
    void evidenceGateRejectsPagesThatLostTheirText() {
        int w = 600, h = 800;
        int[] gray = new int[w * h];
        java.util.Arrays.fill(gray, 235);   // clean blank paper, no ink at all
        int[] blurred = new int[w * h];
        // A few isolated marks so the page is not trivially empty, mirroring the
        // pilot's false positives, which had 1-3 usable boxes and audit F1 ~ 0.
        for (int k = 0; k < 3; k++) {
            for (int y = 100 + k * 40; y < 112 + k * 40; y++) {
                for (int x = 60; x < 140; x++) blurred[y * w + x] = 30;
            }
        }
        List<TextBlock> three = new ArrayList<>();
        for (int k = 0; k < 3; k++) {
            three.add(new TextBlock("a", rect(60, 100 + k * 40, 80, 12), 95));
        }

        MrcReadiness.Breakdown b = MrcReadiness.compute(blurred, w, h, three);
        assertTrue(b.glyphBoxes() == 3, "fixture should yield 3 usable boxes");
        assertTrue(b.sOcr() < 0.15,
                "3 boxes is far below the evidence floor, so sOcr must collapse, got "
                        + b.sOcr());
        assertTrue(b.score() < MrcReadiness.BAD_BELOW,
                "a page with 3 readable glyphs must not reach the good band, got "
                        + b.score());
        assertNotEquals("good", b.tier(),
                "tiering must refuse to call an unreadable page good");
    }

    /**
     * The gate is a floor on evidence, not a quality threshold: a real text page
     * carries hundreds of boxes and must be completely unaffected by it.
     */
    @Test
    @DisplayName("the evidence gate does not penalise a page full of real text")
    void evidenceGateLeavesDenseTextAlone() {
        String[] words = {"alpha", "bravo", "charlie", "delta", "echo", "foxtrot",
                "golf", "hotel", "india", "juliet"};
        List<TextBlock> boxes = new ArrayList<>();
        int rows = 8;
        int x = 10;
        for (int r = 0; r < rows; r++) {
            x = 10;
            for (int i = 0; i < words.length; i++) {
                int ww = 6 * words[i].length();
                boxes.add(new TextBlock(words[i], rect(x, 10 + r * 14, ww, 10), 90 + i));
                x += ww + 3;
            }
        }
        double sOcr = MrcReadiness.sOcrOf(boxes);
        assertTrue(boxes.size() >= MrcReadiness.EVIDENCE_MIN_BOXES,
                "fixture must actually meet the evidence floor, had " + boxes.size());
        assertEquals(1.0, MrcReadiness.mEvidence(boxes.size()), 1e-12,
                "a page at or above the floor is fully credited");
        assertTrue(sOcr > 0.8,
                "a dense, real-word page must be fully credited, got " + sOcr);
    }

    /**
     * The bottleneck must stay continuous.  It used to be able to reach exactly
     * zero, which pinned whole pages to R_mrc = 0 and destroyed the ranking the
     * tier boundaries depend on.
     */
    @Test
    @DisplayName("the bottleneck penalty is floored, never exactly zero")
    void bottleneckIsFlooredAboveZero() {
        double zeroDims = MrcReadiness.aggregate(0.0, 0.0, 0.0, 0.0).score();
        assertTrue(zeroDims > 0.0,
                "an all-zero page must not be pinned to exactly 0, got " + zeroDims);
        double slightlyLess = MrcReadiness.aggregate(0.01, 0.01, 0.01, 0.01).score();
        assertTrue(slightlyLess > zeroDims,
                "scores must stay ordered above the floor: " + slightlyLess
                        + " should exceed " + zeroDims);
    }

    // ── Fixtures ────────────────────────────────────────────────────────────

    private static final List<TextBlock> SYNTH_BOXES = synthBoxes();

    /**
     * Known-sharp text-like rendering: each word box holds five separate vertical
     * bars, one per character.
     *
     * <p>The bar count is deliberate and satisfies two requirements at once.  A
     * solid rectangle would be the wrong fixture for acutance, since its interior
     * is uniform and carries no internal edges at all.  Conversely, a comb of
     * many thin bars would be wrong for fragmentation, because {@code M_frag}
     * measures components per character and would report a page of 2px-wide
     * comb teeth as shattered text.  Five components over a five-character word
     * puts the fixture at the ~1.15 ratio that well-formed Latin text produces,
     * while still placing a hard edge inside every box.
     */
    private static int[] syntheticPage() {
        int w = 420, h = 160;
        int[] page = new int[w * h];
        java.util.Arrays.fill(page, 250);
        for (TextBlock tb : SYNTH_BOXES) {
            Rectangle b = tb.getBbox();
            for (int i = 0; i < BARS_PER_WORD; i++) {
                int x0 = b.x + i * BAR_PITCH;
                for (int y = b.y; y < b.y + b.height; y++) {
                    for (int dx = 0; dx < BAR_WIDTH; dx++) {
                        page[y * w + x0 + dx] = 20;
                    }
                }
            }
        }
        return page;
    }

    private static final int BARS_PER_WORD = 5;
    private static final int BAR_PITCH = 5;
    private static final int BAR_WIDTH = 3;

    private static List<TextBlock> synthBoxes() {
        List<TextBlock> boxes = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            int x = 12 + i * 40;
            // The label must be exactly BARS_PER_WORD alphanumeric characters:
            // M_frag divides the component count by the character count, so a
            // trailing digit would put the fixture off its intended ratio.
            boxes.add(new TextBlock("WORDS", rect(x, 40, BARS_PER_WORD * BAR_PITCH, 40), 95));
        }
        return boxes;
    }

    /** Separable box-blur approximation of a Gaussian, {@code passes} wide. */
    private static int[] gaussianBlur(int[] src, double sigma) {
        if (sigma <= 0) return src.clone();
        int radius = Math.max(1, (int) Math.round(2.5 * sigma));
        int w = 420, h = 160;
        double[] tmp = new double[src.length];
        for (int y = 0; y < h; y++) {
            int row = y * w;
            for (int x = 0; x < w; x++) {
                double sum = 0;
                int n = 0;
                for (int k = -radius; k <= radius; k++) {
                    int xx = x + k;
                    if (xx < 0 || xx >= w) continue;
                    sum += src[row + xx];
                    n++;
                }
                tmp[row + x] = sum / n;
            }
        }
        double[] out = new double[src.length];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                double sum = 0;
                int n = 0;
                for (int k = -radius; k <= radius; k++) {
                    int yy = y + k;
                    if (yy < 0 || yy >= h) continue;
                    sum += tmp[yy * w + x];
                    n++;
                }
                out[y * w + x] = sum / n;
            }
        }
        int[] rounded = new int[src.length];
        for (int i = 0; i < rounded.length; i++) rounded[i] = (int) Math.round(out[i]);
        return rounded;
    }

    /** Crete p90 residual over the synthetic glyphs, for calibration only. */
    private static double creteResidual(int[] gray) {
        int w = 420, h = 160;
        List<Double> diffs = new ArrayList<>();
        for (TextBlock tb : SYNTH_BOXES) {
            Rectangle b = tb.getBbox();
            for (int y = b.y + 1; y < b.y + b.height - 1; y++) {
                for (int x = b.x + 1; x < b.x + b.width - 1; x++) {
                    int i = y * w + x;
                    int sum = (gray[i - w - 1] + 2 * gray[i - w] + gray[i - w + 1]
                            + 2 * gray[i - 1] + 4 * gray[i] + 2 * gray[i + 1]
                            + gray[i + w - 1] + 2 * gray[i + w] + gray[i + w + 1]) / 16;
                    diffs.add((double) Math.abs(gray[i] - sum));
                }
            }
        }
        java.util.Collections.sort(diffs);
        return diffs.get((int) Math.round(0.90 * (diffs.size() - 1)));
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    /**
     * {@link MrcReadiness#aggregate} fills the per-metric slots from the four
     * dimension scores, so a synthesized breakdown with chosen sub-scores is
     * exactly what the fusion step would produce.
     */
    private static MrcReadiness.Breakdown perfect(double v, double k, double o, double bg) {
        return MrcReadiness.aggregate(v, k, o, bg);
    }

    private static boolean[] regionMask(List<TextBlock> boxes, int w, int h) {
        return MrcReadiness.textRegionMask(boxes, w, h);
    }

    private static Rectangle rect(int x, int y, int width, int height) {
        return new Rectangle(x, y, width, height);
    }

    @SuppressWarnings("unused")
    private static double ptToPx(double pt) {
        return pt * PT;
    }
}
