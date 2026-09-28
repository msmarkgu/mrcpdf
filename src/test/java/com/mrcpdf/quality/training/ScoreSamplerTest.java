package com.mrcpdf.quality.training;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;

import com.mrcpdf.quality.model.MrcReadiness;

/**
 * Tests for the score-conditioned sampler's control logic, against a stub
 * {@link ScoreSampler.Render} rather than a real page.
 *
 * <p>No OCR anywhere in here. Every attempt a real sampler makes costs a full
 * Tesseract pass, so a test that exercised the loop honestly would need minutes
 * per case and would assert on a score surface that moves whenever a reference
 * constant is retuned. The stub hands back sub-scores directly, which makes
 * these tests about the loop -- convergence, re-aim direction, the evidence
 * gate, curve inversion -- and nothing else.
 */
class ScoreSamplerTest {

    /** A page that reports the given sub-scores, with glyph boxes well above the gate. */
    private static MrcReadiness.Breakdown page(double v, double k, double g) {
        return MrcReadiness.aggregate(v, k, 0.95, g, 50);
    }

    private static MrcReadiness.Breakdown page(int boxes) {
        return MrcReadiness.aggregate(0.9, 0.9, 0.95, 0.9, boxes);
    }

    /**
     * Reads the severity of the dimension the profile actually attacks.
     *
     * <p>{@link ScannerProfile} carries 18 parameters, but each dimension drives a
     * known subset and the rest are at identity, so a stub can recover the severity
     * by looking up whichever driver moved. Testing against the real profile keeps
     * the stub honest: if a dimension ever starts driving a different parameter,
     * these tests fail rather than silently passing against a stale mapping.
     */
    private static double severityOf(ScoreSampler.Dim dim, ScannerProfile p) {
        return switch (dim) {
            case VISUAL -> p.blurSigma() / SeverityScore.MAX_BLUR;
            case MASK -> p.whiteLevel() / SeverityScore.MAX_WHITE_LEVEL;
            case BG -> p.paperOpacity() / SeverityScore.MAX_PAPER;
        };
    }

    /** Wraps a sub-score function as a Render, recording the profiles it was asked for. */
    private static final class Stub implements ScoreSampler.Render {
        private final java.util.function.DoubleUnaryOperator response;
        final List<ScannerProfile> profiles = new ArrayList<>();
        int renders;

        Stub(java.util.function.DoubleUnaryOperator response) {
            this.response = response;
        }

        @Override
        public ScoreSampler.Rendered render(ScannerProfile profile, Random rng) {
            renders++;
            profiles.add(profile);
            double sev = severityOf(ScoreSampler.Dim.VISUAL, profile);
            // Not all profiles are VISUAL; ask the caller which dim it wants by
            // taking the max driver, which is unique per dim because each dim's
            // drivers are disjoint.
            sev = Math.max(sev, Math.max(profile.whiteLevel() / SeverityScore.MAX_WHITE_LEVEL,
                    profile.paperOpacity() / SeverityScore.MAX_PAPER));
            return ScoreSamplerTest.render(page(response.applyAsDouble(sev), 0.95, 0.95));
        }
    }

    private static ScoreSampler.Rendered render(MrcReadiness.Breakdown b) {
        BufferedImage img = new BufferedImage(4, 4, BufferedImage.TYPE_BYTE_GRAY);
        JpegCodec.FinalImage jpeg = new JpegCodec.FinalImage(new byte[0], img);
        return new ScoreSampler.Rendered(img, jpeg, null, null, b);
    }

    /** Monotone, roughly linear, and the direction a real degradation moves. */
    private static double monotone(double sev) {
        return Math.max(0.0, 1.0 - sev);
    }

    // ── convergence ───────────────────────────────────────────────────────────

    @Test
    void convergesOntoTargetOnAMonotoneResponse() throws IOException {
        ScoreSampler sampler = new ScoreSampler(0.05, 8);
        Stub stub = new Stub(ScoreSamplerTest::monotone);
        double target = 0.60;

        ScoreSampler.Sample s = sampler.sample(stub, new Random(7), target,
                ScoreSampler.Dim.VISUAL, null);

        assertTrue(s.onTarget(), "expected convergence, got steer=" + s.steer());
        assertEquals(target, s.steer(), 0.05);
        // Accepted mid-loop rather than by exhausting the budget.
        assertTrue(s.attempts() < 8, "converged only by using every attempt");
    }

    @Test
    void directionOfTheCorrectionFollowsTheTarget() throws IOException {
        // The inversion this test exists to catch: the loop must turn severity up
        // when the page measures cleaner than the target and down when it measures
        // dirtier. Getting the sense backwards makes the sampler diverge instead
        // of converge, and it still "fails gracefully" as a miss -- so only a
        // direct assertion on the first correction will catch it.
        assertFirstCorrectionGoes(0.35, +1);
        assertFirstCorrectionGoes(0.80, -1);
    }

    private void assertFirstCorrectionGoes(double target, int sign) throws IOException {
        Stub stub = new Stub(ScoreSamplerTest::monotone);
        new ScoreSampler(0.02, 4).sample(stub, new Random(11), target,
                ScoreSampler.Dim.VISUAL, null);

        assertTrue(stub.profiles.size() >= 2, "no correction was attempted");
        double first = severityOf(ScoreSampler.Dim.VISUAL, stub.profiles.get(0));
        double second = severityOf(ScoreSampler.Dim.VISUAL, stub.profiles.get(1));
        if (sign > 0) {
            assertTrue(second > first, String.format(
                    "target %.2f measures cleaner than the start, so severity should rise: "
                            + "%.3f -> %.3f", target, first, second));
        } else {
            assertTrue(second < first, String.format(
                    "target %.2f is dirtier than the start, so severity should fall: "
                            + "%.3f -> %.3f", target, first, second));
        }
    }

    @Test
    void backsOffWhenItOvershoots() throws IOException {
        // A steeper response: the loop's proportional step can overshoot on the
        // first correction, and it has to reverse rather than keep going.
        ScoreSampler sampler = new ScoreSampler(0.05, 10);
        Stub stub = new Stub(sev -> Math.max(0.0, 1.0 - 2.0 * sev));

        ScoreSampler.Sample s = sampler.sample(stub, new Random(3), 0.55,
                ScoreSampler.Dim.VISUAL, null);

        assertTrue(s.onTarget(), "overshoot was not recovered: " + s.steer());
    }

    @Test
    void reportsAMissInsteadOfThrowingWhenTheTargetIsUnreachable() throws IOException {
        // No response can reach 0.95 once any severity is applied. The row is
        // still returned, flagged: dropping it would bias the corpus toward
        // whichever dimensions have wide ranges.
        ScoreSampler sampler = new ScoreSampler(0.02, 4);
        Stub stub = new Stub(sev -> Math.max(0.0, 1.0 - 0.5 * sev));

        ScoreSampler.Sample s = sampler.sample(stub, new Random(5), 0.99,
                ScoreSampler.Dim.VISUAL, null);

        assertNotNull(s.rendered());
        assertFalse(s.onTarget());
        assertEquals(4, s.attempts());
        assertTrue(s.error() > 0.02);
    }

    // ── re-aim ────────────────────────────────────────────────────────────────

    @Test
    void reaimsOnTheBindingDimensionWhenTheDrawnOneIsDraggedUnder() throws IOException {
        // Attacking VISUAL drags MASK below it: fading the page to destabilize the
        // binarization also costs contrast, so the driver the caller picked stops
        // being the dimension that caps the page. Turning VISUAL's knob further
        // cannot help, so the sampler has to move to MASK.
        double target = 0.60;
        Stub stub = new Stub(sev -> {
            double v = 1.0 - sev;
            double k = Math.max(0.0, 1.0 - 1.6 * sev);
            return Math.min(v, k);
        });
        // Report the two sub-scores from one severity, which the single-value
        // stub cannot express, so drive it through a bespoke Render.
        ScoreSampler.Render render = (profile, rng) -> {
            stub.renders++;
            double sev = Math.max(severityOf(ScoreSampler.Dim.VISUAL, profile),
                    severityOf(ScoreSampler.Dim.MASK, profile));
            return render(page(1.0 - sev, Math.max(0.0, 1.0 - 1.6 * sev), 0.95));
        };

        ScoreSampler sampler = new ScoreSampler(0.05, 8);
        ScoreSampler.Sample s = sampler.sample(render, new Random(13), target,
                ScoreSampler.Dim.VISUAL, null);

        assertEquals(ScoreSampler.Dim.MASK, s.binding(),
                "fixture no longer exercises re-aim; MASK should cap the page");
        assertEquals(ScoreSampler.Dim.MASK, s.aimed(),
                "sampler should have re-aimed at the binding dimension");
    }

    @Test
    void bindingDimensionIsTheMinimum() {
        assertEquals(ScoreSampler.Dim.VISUAL, ScoreSampler.Dim.binding(page(0.2, 0.8, 0.9)));
        assertEquals(ScoreSampler.Dim.MASK, ScoreSampler.Dim.binding(page(0.8, 0.2, 0.9)));
        assertEquals(ScoreSampler.Dim.BG, ScoreSampler.Dim.binding(page(0.8, 0.9, 0.2)));
    }

    // ── the evidence gate ─────────────────────────────────────────────────────

    @Test
    void steerKappaIsZeroBelowTheGlyphBoxGate() {
        // The metrics steerKappa is built from are measured inside OCR word boxes.
        // With no boxes there are no strokes and no ambiguous pixels, so both
        // degenerate to 1.0 and a destroyed page would look clean.
        assertEquals(0.0, ScoreSampler.steerKappa(page(0)), "zero boxes must read as destroyed");
        assertEquals(0.0, ScoreSampler.steerKappa(
                MrcReadiness.aggregate(0.95, 0.95, 0.95, 0.95, MrcReadiness.MIN_GLYPH_BOXES - 1)),
                "just under the gate must read as destroyed");

        // At or above the gate the real values come through, so a destroyed page
        // can never masquerade as a clean one.
        assertTrue(ScoreSampler.steerKappa(
                MrcReadiness.aggregate(0.95, 0.95, 0.95, 0.95, MrcReadiness.MIN_GLYPH_BOXES)) > 0.9);
        assertEquals(0.0, ScoreSampler.steerKappa(page(1)),
                "one box is still below the gate");
    }

    @Test
    void steerKappaIsTheMinimumOfTheThreeDegradableDimensions() {
        assertEquals(0.3, ScoreSampler.steerKappa(page(0.9, 0.3, 0.8)), 1e-9);
        assertEquals(0.4, ScoreSampler.steerKappa(page(0.4, 0.9, 0.7)), 1e-9);
        assertEquals(0.5, ScoreSampler.steerKappa(page(0.7, 0.8, 0.5)), 1e-9);
    }

    // ── the calibration table ──────────────────────────────────────────────────

    @Test
    void curveInversionRoundTrips() {
        double[] sev = {0, 0.1, 0.2, 0.3, 0.4, 0.5, 0.6, 0.7, 0.8, 0.9, 1.0};
        double[] score = new double[sev.length];
        for (int i = 0; i < sev.length; i++) score[i] = monotone(sev[i]);
        ScoreCalibration.Curve c = ScoreCalibration.Curve.fit(ScoreSampler.Dim.VISUAL, sev, score);

        for (double t = 0.05; t < 1.0; t += 0.05) {
            double back = c.scoreAt(c.severityFor(t));
            assertEquals(t, back, 0.02, "round trip failed at target " + t);
        }
    }

    @Test
    void curveClampsOutsideItsMeasuredRange() {
        double[] sev = {0, 0.5, 1.0};
        double[] score = {0.95, 0.60, 0.35};
        ScoreCalibration.Curve c = ScoreCalibration.Curve.fit(ScoreSampler.Dim.MASK, sev, score);

        assertEquals(0.0, c.severityFor(1.0), "a target above the cleanest response is severity 0");
        assertEquals(1.0, c.severityFor(0.10), "a target below the dirtiest response is severity 1");
        assertEquals(0.95, c.cleanest(), 1e-9);
        assertEquals(0.35, c.dirtiest(), 1e-9);
    }

    @Test
    void curveForcesMonotonicityOverANoisyResponse() {
        // Real grid points cross over; a curve that crossed would hand the re-aim
        // loop a non-monotone target and send it oscillating between severities.
        double[] sev = {0, 0.1, 0.2, 0.3, 0.4};
        double[] score = {0.90, 0.62, 0.66, 0.40, 0.45};
        ScoreCalibration.Curve c = ScoreCalibration.Curve.fit(ScoreSampler.Dim.BG, sev, score);

        for (double s = 0.0; s <= 1.0; s += 0.02) {
            double hi = c.scoreAt(s);
            double lo = c.scoreAt(Math.min(1.0, s + 0.02));
            assertTrue(hi >= lo - 1e-9,
                    "score rose from " + hi + " to " + lo + " at severity " + s);
        }
    }

    @Test
    void curveReportsUnreachableTargets() {
        // This is the check that makes a lumpy histogram interpretable: a
        // dimension that bottoms out at 0.55 has a completely different reason
        // for missing targets than one that bottoms out at 0.05.
        double[] sev = {0, 0.5, 1.0};
        double[] score = {0.95, 0.80, 0.55};
        ScoreCalibration.Curve c = ScoreCalibration.Curve.fit(ScoreSampler.Dim.VISUAL, sev, score);

        assertFalse(c.reachable(0.30), "0.30 is below this dimension's floor");
        assertTrue(c.reachable(0.60));
        assertFalse(c.reachable(0.99), "0.99 is above this dimension's ceiling");
    }

    @Test
    void curveRejectsDegenerateInput() {
        double[] one = {0.5};
        assertThrows(IllegalArgumentException.class,
                () -> ScoreCalibration.Curve.fit(ScoreSampler.Dim.VISUAL, one, one));
        assertThrows(IllegalArgumentException.class,
                () -> ScoreCalibration.Curve.fit(ScoreSampler.Dim.VISUAL,
                        new double[] {0, 1}, new double[] {1}));
    }

    // ── the marginal ───────────────────────────────────────────────────────────

    @Test
    void targetsAreUniformOverTheMarginalAndFillTheMiddle() {
        Random rng = new Random(17);
        double lo = ScoreSampler.DEFAULT_LO, hi = ScoreSampler.DEFAULT_HI;
        int[] bins = new int[10];
        for (int i = 0; i < 20_000; i++) {
            double t = ScoreSampler.drawTarget(rng, lo, hi);
            assertTrue(t >= lo && t <= hi);
            int b = ScoreCalibration.bin(t, lo, hi, 10);
            assertTrue(b >= 0, "target " + t + " fell outside its own marginal");
            bins[b]++;
        }
        // 20k draws over 10 bins: a genuinely empty bin is not noise, and the
        // middle band is the whole point of the change.
        for (int b = 0; b < bins.length; b++) {
            assertTrue(bins[b] > 1500, "bin " + b + " got only " + bins[b] + " draws");
        }
    }

    @Test
    void degenerateMarginalYieldsItsSingleValue() {
        assertEquals(0.5, ScoreSampler.drawTarget(new Random(1), 0.5, 0.5), 1e-9);
        // An inverted range is a caller bug, not a random draw: answering with the
        // lower bound keeps the result in range instead of returning a number from
        // a negative-width interval.
        assertEquals(0.7, ScoreSampler.drawTarget(new Random(1), 0.7, 0.2), 1e-9);
    }

    // ── corpus QA ──────────────────────────────────────────────────────────────

    private static ScoreSampler.Sample fake(double steer, double rmrc, boolean onTarget) {
        return new ScoreSampler.Sample(render(page(rmrc, rmrc, rmrc)),
                ScoreSampler.Dim.VISUAL, ScoreSampler.Dim.VISUAL, steer, steer, 1, onTarget);
    }

    @Test
    void histogramPassesOnAUniformCorpus() {
        List<ScoreSampler.Sample> xs = new ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            double t = 0.20 + 0.75 * (i + 0.5) / 1000;
            xs.add(fake(t, t, true));
        }
        ScoreCalibration.Histogram h = ScoreCalibration.histogram(xs, 10, 0.20, 0.95);
        assertTrue(h.ok(), "uniform corpus should pass: " + h.problems());
        assertEquals(0, h.misses());
        assertEquals(1000, h.rows());
    }

    @Test
    void histogramFailsOnAnEmptyMiddle() {
        // The exact shape this change exists to eliminate: 750 rows in the tails,
        // nothing in between, so the model has no training signal where the
        // quality gate actually operates.
        List<ScoreSampler.Sample> xs = new ArrayList<>();
        for (int i = 0; i < 375; i++) xs.add(fake(0.90, 0.90, true));
        for (int i = 0; i < 375; i++) xs.add(fake(0.25, 0.25, true));
        ScoreCalibration.Histogram h = ScoreCalibration.histogram(xs, 10, 0.20, 0.95);

        assertFalse(h.ok(), "a corpus with no middle band must not pass");
        assertTrue(h.problems().stream().anyMatch(p -> p.contains("empty bin")),
                "expected an empty-bin complaint, got " + h.problems());
    }

    @Test
    void histogramCountsMissesSeparatelyFromEmptyBins() {
        // An unreachable target is not a distribution failure, and conflating the
        // two would send a reader looking for a class-mix bug that isn't there. The
        // miss band is the bottom of the marginal, which is the realistic case: a
        // dimension whose measured floor sits above the low end of the range.
        int rows = 1000;
        double lo = ScoreSampler.DEFAULT_LO, hi = ScoreSampler.DEFAULT_HI;
        int expectedMisses = 0;
        List<ScoreSampler.Sample> xs = new ArrayList<>();
        for (int i = 0; i < rows; i++) {
            double t = lo + (hi - lo) * (i + 0.5) / rows;
            boolean reachable = t >= 0.30;
            if (!reachable) expectedMisses++;
            xs.add(fake(t, t, reachable));
        }
        ScoreCalibration.Histogram h = ScoreCalibration.histogram(xs, 10, lo, hi);

        assertTrue(h.ok(), "misses alone should not fail the histogram: " + h.problems());
        assertEquals(expectedMisses, h.misses());
        assertEquals(133, h.misses(), "fixture sanity: the 0.20-0.30 band is 13.3% of the range");
        assertEquals(133 / 1000.0, h.missRate(), 1e-9);
    }

    @Test
    void histogramFailsOnAnEmptyCorpus() {
        ScoreCalibration.Histogram h =
                ScoreCalibration.histogram(List.of(), 10, 0.20, 0.95);
        assertFalse(h.ok());
        assertEquals(0, h.rows());
    }

    @Test
    void histogramGradesClassesOnTheFullCompositeNotTheSteerValue() {
        // The steer value excludes S_ocr, so it sits above the composite; on a
        // real page the gap is a few points. Grading steer values against
        // GOOD_ABOVE would over-count `good` by construction and report a
        // healthier mix than the corpus has, so the mix must come from the
        // composite. A 0.11 gap is far larger than reality precisely to make the
        // over-count impossible to miss.
        int rows = 1000;
        double lo = ScoreSampler.DEFAULT_LO, hi = ScoreSampler.DEFAULT_HI;
        int gradedOnSteer = 0;
        List<ScoreSampler.Sample> xs = new ArrayList<>();
        for (int i = 0; i < rows; i++) {
            double steer = lo + (hi - lo) * (i + 0.5) / rows;
            double composite = steer - 0.11;
            if (steer >= MrcReadiness.GOOD_ABOVE) gradedOnSteer++;
            xs.add(fake(steer, composite, true));
        }
        ScoreCalibration.Histogram h = ScoreCalibration.histogram(xs, 10, lo, hi);

        int good = h.classCounts()[0], middle = h.classCounts()[1], bad = h.classCounts()[2];
        assertEquals(rows, good + middle + bad, "class mix must account for every row");
        assertTrue(good > 0 && middle > 0 && bad > 0, "all three tiers should be populated");
        assertTrue(good < gradedOnSteer, String.format(
                "grading the steer value would report %d good rows, but the composite "
                        + "only supports %d", gradedOnSteer, good));
    }
}
