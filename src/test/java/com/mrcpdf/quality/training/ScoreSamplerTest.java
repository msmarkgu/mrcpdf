package com.mrcpdf.quality.training;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
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
            double v = response.applyAsDouble(sev);
            return ScoreSamplerTest.render(v, page(v, 0.95, 0.95));
        }
    }

    private static ScoreSampler.Rendered render(MrcReadiness.Breakdown b) {
        return render(Double.NaN, b);
    }

    /** A render whose audit F1 is {@code audit}; NaN models "not measurable". */
    private static ScoreSampler.Rendered render(double audit, MrcReadiness.Breakdown b) {
        BufferedImage img = new BufferedImage(4, 4, BufferedImage.TYPE_BYTE_GRAY);
        JpegCodec.FinalImage jpeg = new JpegCodec.FinalImage(new byte[0], img);
        ReadabilityOcr.Result ocr =
                new ReadabilityOcr.Result(List.of(), List.of(), audit);
        return new ScoreSampler.Rendered(img, jpeg, ocr, null, b);
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
        // No response can reach 0.99: this page tops out at 0.80 even untouched.
        // The row is still returned, flagged: dropping it would bias the corpus
        // toward whichever dimensions have wide ranges.
        ScoreSampler sampler = new ScoreSampler(0.02, 4);
        Stub stub = new Stub(sev -> Math.max(0.0, 0.80 - 0.5 * sev));

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
            double v = 1.0 - sev, k = Math.max(0.0, 1.0 - 1.6 * sev);
            return render(Math.min(v, k), page(v, k, 0.95));
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
    void theSteeringValueIsZeroBelowTheGlyphBoxGate() {
        // A page with no OCR boxes has no words to compare, so the audit is
        // undefined rather than low. Undefined must not read as clean.
        assertEquals(0.0, ScoreSampler.steerValue(ocr(0.99), page(0)),
                "zero boxes must read as destroyed");
        assertEquals(0.0, ScoreSampler.steerValue(ocr(0.99),
                MrcReadiness.aggregate(0.95, 0.95, 0.95, 0.95, MrcReadiness.MIN_GLYPH_BOXES - 1)),
                "just under the gate must read as destroyed");
        assertEquals(0.0, ScoreSampler.steerValue(ocr(0.99), page(1)),
                "one box is still below the gate");

        // At or above the gate the audit comes through untouched.
        assertEquals(0.62, ScoreSampler.steerValue(ocr(0.62),
                MrcReadiness.aggregate(0.95, 0.95, 0.95, 0.95, MrcReadiness.MIN_GLYPH_BOXES)), 1e-9);
    }

    @Test
    void anUnmeasurableAuditIsTreatedAsDestroyed() {
        // NaN is what a page with too few reference words produces. Letting NaN
        // through would put NaN in the target comparison and the page would be
        // recorded as converged onto nothing.
        assertEquals(0.0, ScoreSampler.steerValue(ocr(Double.NaN), page(50)), 1e-9);
    }

    @Test
    void theSteeringValueIgnoresTheSubScoresEntirely() {
        // The regression that cost a corpus build. These sub-scores are the ones
        // that used to be the target, and they say a destroyed page is clean:
        // every one of them is near the top while the audit is at the bottom. If
        // the sampler still consulted them it would accept a 0.97 target on a
        // page that reads at 0.05.
        MrcReadiness.Breakdown lying = page(0.97, 0.96, 0.98);
        assertEquals(0.05, ScoreSampler.steerValue(ocr(0.05), lying), 1e-9);
    }

    private static ReadabilityOcr.Result ocr(double audit) {
        return new ReadabilityOcr.Result(List.of(), List.of(), audit);
    }

    // ── the label-grounding gate ──────────────────────────────────────────────

    @Test
    void theProxyAgreementIsMeasuredOverAllRowsNotTheSteeredSubset() {
        // This measurement is easy to get wrong in a way that inverts its
        // meaning. A steered row's label is pinned to the sampler's target, so
        // within that subset the correlation says "does the proxy track the
        // target", not "does the proxy track the truth" -- and it came out +0.02,
        // which would have refused a perfectly good corpus. Over all 500 rows,
        // where the clean pages supply the label variation the sampler does not
        // control, it is +0.60. The failure mode is a gate that fires on the
        // subset where its quantity is least meaningful.
        double[] all = {0.9, 0.8, 0.7, 0.6, 0.5, 0.4};
        double[] proxy = {0.85, 0.75, 0.66, 0.55, 0.45, 0.35};
        double pinnedLabel = RealTrainDump.correlation(all, proxy);
        assertTrue(pinnedLabel > 0.95,
                "an ordered proxy tracks an ordered label");

        // Hold the label constant, as the sampler does on steered rows: the
        // correlation is undefined, not low, which is the point.
        double[] constant = {0.5, 0.5, 0.5, 0.5, 0.5, 0.5};
        assertTrue(Double.isNaN(RealTrainDump.correlation(constant, proxy)),
                "a pinned label has no variance to correlate against");
    }

    @Test
    void correlationIsPearsonAndRefusesDegenerateInput() {
        double[] a = {1, 2, 3, 4, 5};
        assertEquals(1.0, RealTrainDump.correlation(a, a), 1e-12);
        assertEquals(-1.0, RealTrainDump.correlation(a, new double[] {5, 4, 3, 2, 1}), 1e-12);
        // A constant column has no variance, so correlation is undefined rather
        // than zero -- reporting 0.0 there would read as "unrelated" and fail the
        // build for the wrong reason.
        assertTrue(Double.isNaN(RealTrainDump.correlation(a, new double[] {1, 1, 1, 1, 1})));
        assertTrue(Double.isNaN(RealTrainDump.correlation(new double[] {1, 2}, new double[] {1, 2})));
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
        return new ScoreSampler.Sample(render(rmrc, page(rmrc, rmrc, rmrc)),
                ScoreSampler.Dim.VISUAL, ScoreSampler.Dim.VISUAL, ScoreSampler.Dim.VISUAL,
                steer, steer, 1, onTarget);
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
    void missesAreExcludedFromTheBinsAndGatedByRateInstead() {
        // A miss is a page the generator could not place, and the marginal asks
        // for targets it cannot reach. Folding those rows into the distribution
        // would demand a uniformity the design cannot deliver -- the same
        // unsatisfiable demand as requiring a bin no dimension can fill. So they
        // are counted, gated, and reported, and the bins describe the rows that
        // did land.
        int rows = 1000;
        double lo = 0.20, hi = 0.80;
        List<ScoreSampler.Sample> xs = new ArrayList<>();
        int expectedMisses = 0;
        for (int i = 0; i < rows; i++) {
            double t = lo + (hi - lo) * (i + 0.5) / rows;
            // 10% miss, spread evenly so no single bin is distorted.
            boolean reachable = i % 10 != 0;
            if (!reachable) expectedMisses++;
            xs.add(fake(t, t, reachable));
        }
        ScoreCalibration.Histogram h = ScoreCalibration.histogram(xs, 10, lo, hi);

        assertTrue(h.ok(), "a 10% miss rate should pass: " + h.problems());
        assertEquals(expectedMisses, h.misses());
        assertEquals(expectedMisses / (double) rows, h.missRate(), 1e-9);
        assertTrue(h.notes().stream().anyMatch(n -> n.contains("remaining 900")),
                "the note should say what the uniformity check actually judged: " + h.notes());
    }

    @Test
    void tooManyMissesFailsEvenWhenTheConvergedRowsLookPerfect() {
        // The gate is what keeps excluding misses honest. Without it, dropping
        // every miss would produce a flawless-looking distribution from a corpus
        // the generator could not actually build.
        int rows = 1000;
        double lo = 0.20, hi = 0.80;
        List<ScoreSampler.Sample> xs = new ArrayList<>();
        for (int i = 0; i < rows; i++) {
            double t = lo + (hi - lo) * (i + 0.5) / rows;
            xs.add(fake(t, t, i % 10 >= 5));   // 50% miss
        }
        ScoreCalibration.Histogram h = ScoreCalibration.histogram(xs, 10, lo, hi);

        assertFalse(h.ok());
        assertTrue(h.problems().stream().anyMatch(p -> p.contains("miss rate")),
                "expected a miss-rate problem, got " + h.problems());
    }

    @Test
    void aLopsidedRunOfMissesStillFailsTheBinCheck() {
        // Excluding misses must not become a way to empty a bin. Here the misses
        // are all in the bottom band, so excluding them leaves that bin empty even
        // though the overall rate is under the gate.
        int rows = 1000;
        double lo = 0.20, hi = 0.80;
        List<ScoreSampler.Sample> xs = new ArrayList<>();
        for (int i = 0; i < rows; i++) {
            double t = lo + (hi - lo) * (i + 0.5) / rows;
            xs.add(fake(t, t, t >= 0.30));
        }
        ScoreCalibration.Histogram h = ScoreCalibration.histogram(xs, 10, lo, hi);

        assertTrue(h.problems().stream().anyMatch(p -> p.contains("0.20-0.26")),
                "the emptied bin should still be reported, got " + h.problems());
    }

    @Test
    void aGenuinelyUniformCorpusPassesEvenThoughHalfItsBinsSitBelowTheMean() {
        // The previous rule required every bin to hold at least 10% of the rows.
        // With 10 bins that is the mean, and half of a uniform sample is below the
        // mean by definition, so the check rejected well-behaved corpora.
        List<ScoreSampler.Sample> xs = new ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            double t = 0.20 + 0.75 * (i + 0.5) / 1000;
            xs.add(fake(t, t, true));
        }
        ScoreCalibration.Histogram h = ScoreCalibration.histogram(xs, 10, 0.20, 0.95);
        assertTrue(h.ok(), "a uniform corpus must pass: " + h.problems());
        int spread = Arrays.stream(h.steerCounts()).max().getAsInt()
                - Arrays.stream(h.steerCounts()).min().getAsInt();
        assertEquals(0, spread, "fixture is not actually uniform, counts=" +
                Arrays.toString(h.steerCounts()));
    }

    @Test
    void aGenuinelyLopsidedCorpusStillFails() {
        // The other half of the previous rule: a corpus crammed into one bin must
        // not pass just because the threshold was loosened.
        List<ScoreSampler.Sample> xs = new ArrayList<>();
        for (int i = 0; i < 900; i++) xs.add(fake(0.50, 0.50, true));
        for (int i = 0; i < 100; i++) xs.add(fake(0.30 + 0.001 * i, 0.30, true));
        ScoreCalibration.Histogram h = ScoreCalibration.histogram(xs, 10, 0.20, 0.95);
        assertFalse(h.ok(), "a corpus with 900 of 1000 rows in one bin must fail");
    }

    @Test
    void tooFewRowsToJudgeIsReportedRatherThanEnforced() {
        // A 30-row slice empties a quarter of its bins by chance. Reporting that
        // as a failure trains the reader to ignore the check; reporting it as a
        // pass hides a real problem. Say the sample is too small and stop there.
        List<ScoreSampler.Sample> xs = new ArrayList<>();
        for (int i = 0; i < 30; i++) xs.add(fake(0.90, 0.90, true));

        ScoreCalibration.Histogram h = ScoreCalibration.histogram(xs, 10, 0.20, 0.95);
        assertTrue(h.notes().stream().anyMatch(n -> n.contains("no power")),
                "expected an underpowered note, got " + h.notes());
        assertTrue(h.notes().stream().anyMatch(n -> n.contains("100")),
                "the note should name the rows it needs, got " + h.notes());
    }

    @Test
    void aFullSizedCorpusIsJudgedEvenWhenOneBinIsEmpty() {
        // Under the same 30 rows this is excused; at 300 rows an empty bin is a
        // real gap in the marginal and must be reported.
        List<ScoreSampler.Sample> xs = new ArrayList<>();
        for (int i = 0; i < 300; i++) {
            double t = 0.20 + 0.75 * (i + 0.5) / 300;
            if (t > 0.60 && t < 0.68) continue;   // leave one hole
            xs.add(fake(t, t, true));
        }
        ScoreCalibration.Histogram h = ScoreCalibration.histogram(xs, 10, 0.20, 0.95);
        // The note about what the check judged is always present now; what this
        // test cares about is that the run is not excused for lack of power.
        assertTrue(h.notes().stream().noneMatch(n -> n.contains("no power")),
                "300 rows is enough to judge: " + h.notes());
        assertTrue(h.problems().stream().anyMatch(p -> p.contains("0.58-0.65")),
                "the hole should be reported, got " + h.problems());
    }

    @Test
    void mergingKeepsTheUnionRangeButSeedsFromTheMedianPage() {
        // Two pages that respond differently: one holds up better under severity.
        // The merged curve is only a bisection seed, so it should sit near the
        // middle page rather than be dragged by either, but the range QA is checked
        // against has to stay the full span the corpus can actually produce.
        double[] sev = {0, 0.5, 1.0};
        ScoreCalibration.Calibration weak = new ScoreCalibration.Calibration(
                ScoreCalibration.Curve.fit(ScoreSampler.Dim.VISUAL, sev, new double[] {0.55, 0.30, 0.10}),
                ScoreCalibration.Curve.fit(ScoreSampler.Dim.MASK, sev, new double[] {0.60, 0.35, 0.10}),
                ScoreCalibration.Curve.fit(ScoreSampler.Dim.BG, sev, new double[] {0.70, 0.45, 0.10}),
                "weak page");
        ScoreCalibration.Calibration strong = new ScoreCalibration.Calibration(
                ScoreCalibration.Curve.fit(ScoreSampler.Dim.VISUAL, sev, new double[] {0.95, 0.70, 0.40}),
                ScoreCalibration.Curve.fit(ScoreSampler.Dim.MASK, sev, new double[] {0.96, 0.72, 0.42}),
                ScoreCalibration.Curve.fit(ScoreSampler.Dim.BG, sev, new double[] {0.97, 0.80, 0.50}),
                "strong page");

        ScoreCalibration.Calibration merged =
                ScoreCalibration.Calibration.merge(List.of(weak, strong));

        assertEquals(0.10, merged.fillableRange()[0], 1e-6, "dirtiest across the union");
        assertEquals(0.97, merged.fillableRange()[1], 1e-6, "cleanest across the union");
        assertTrue(merged.reference().contains("weak page")
                        && merged.reference().contains("strong page"),
                "the report should name what it merged, got " + merged.reference());

        // The seed for a mid target should be between the two pages' own seeds,
        // not equal to either: a seed tuned to one page is the thing being fixed.
        double weakSeed = weak.severityFor(ScoreSampler.Dim.VISUAL, 0.50);
        double strongSeed = strong.severityFor(ScoreSampler.Dim.VISUAL, 0.50);
        double mergedSeed = merged.severityFor(ScoreSampler.Dim.VISUAL, 0.50);
        assertTrue(mergedSeed > weakSeed && mergedSeed < strongSeed,
                "expected the median seed between the two pages, got " + mergedSeed
                        + " not in (" + weakSeed + ", " + strongSeed + ")");
    }

    @Test
    void mergingTakesTheMedianSoOneOddPageCannotSwampTheSeed() {
        // Three pages, one of which barely responds at all. A mean would be pulled
        // most of the way to the odd page and produce a seed nothing benefits from.
        double[] sev = {0, 1.0};
        ScoreCalibration.Curve normal =
                ScoreCalibration.Curve.fit(ScoreSampler.Dim.VISUAL, sev, new double[] {0.90, 0.50});
        ScoreCalibration.Curve alsoNormal =
                ScoreCalibration.Curve.fit(ScoreSampler.Dim.VISUAL, sev, new double[] {0.90, 0.50});
        ScoreCalibration.Curve odd =
                ScoreCalibration.Curve.fit(ScoreSampler.Dim.VISUAL, sev, new double[] {0.90, 0.10});

        double merged = ScoreCalibration.Curve.merge(normal, alsoNormal, odd)
                .responseAt(1.0);
        assertEquals(0.50, merged, 1e-6, "the median of two normal pages and one odd one");
    }

    @Test
    void convergesInFewRendersWhenTheCalibrationIsFromADifferentPage() throws IOException {
        // The 375-row corpus had 25% of pages exhaust all six attempts, which is
        // most of the corpus's render budget spent walking to a target. The step
        // rule was proportional to severity and could not see how far off the page
        // was, so a page off by 0.4 and one off by 0.02 moved the same distance.
        //
        // The stub stands in for a page whose response differs from the
        // calibration's -- a steeper curve than the table was built from, which is
        // the normal case once the curves are merged across reference pages.
        Stub stub = new Stub(sev -> Math.max(0.0, 1.0 - 1.8 * sev * sev - 0.4 * sev));
        ScoreSampler sampler = new ScoreSampler(0.05, 6);

        int renders = 0;
        for (double target : new double[] {0.25, 0.40, 0.55, 0.70, 0.78}) {
            ScoreSampler.Sample s =
                    sampler.sample(stub, new Random(11), target, ScoreSampler.Dim.VISUAL, null);
            assertTrue(s.onTarget(), "target " + target + " not reached, got " + s.steer());
            assertTrue(s.attempts() < 6,
                    "target " + target + " should not exhaust the budget, took " + s.attempts());
            renders += s.attempts();
        }
        // Five targets against a six-render budget each. A rule keyed to severity
        // spends most of that budget walking to the target.
        assertTrue(renders <= 15, "expected ~3 renders per target, took " + renders);
    }

    @Test
    void theStepRuleActuallyRespondsToTheSizeOfTheError() throws IOException {
        // Same measured error, two different starting severities: a rule keyed to
        // severity takes the same step for both, one keyed to the error does not.
        // Here the page is far off the target, so the second move should be large
        // in both cases and land close, rather than creeping in severity-sized hops.
        Stub stub = new Stub(sev -> 1.0 - sev);
        ScoreSampler.Sample s = new ScoreSampler(0.02, 8)
                .sample(stub, new Random(5), 0.30, ScoreSampler.Dim.VISUAL, null);

        assertTrue(s.onTarget(), "should converge, got " + s.steer());
        assertTrue(s.attempts() <= 4, "a unit-slope response should need very few renders, took "
                + s.attempts());
    }

    @Test
    void anUnreachableTargetStillStopsAndIsRecordedRatherThanLooping() throws IOException {
        // The step is bounded and the attempt budget is finite, so a target no
        // severity can reach must return the best attempt and count as a miss.
        Stub stub = new Stub(sev -> Math.max(0.0, 0.30 - sev));
        ScoreSampler.Sample s = new ScoreSampler(0.05, 6)
                .sample(stub, new Random(3), 0.95, ScoreSampler.Dim.VISUAL, null);

        assertFalse(s.onTarget());
        assertEquals(6, s.attempts(), "it should spend its budget and stop");
        assertTrue(s.error() > 0.05);
        assertNotNull(s.rendered(), "a miss is still a page, and the map needs it");
    }

    @Test
    void reAimStartsAFreshBracketBecauseTheOldOneWasMeasuredOnAnotherDimension() throws IOException {
        // After a re-aim the previous bracket was measured through a different
        // dimension's drivers and says nothing about this one. Carrying it over
        // can pin the search outside the range the new dimension reaches.
        Stub stub = new Stub(sev -> {
            double v = 1.0 - sev;
            double k = Math.max(0.0, 1.0 - 1.6 * sev);
            return Math.min(v, k);
        });
        ScoreSampler.Render render = (profile, rng) -> {
            stub.renders++;
            double sev = Math.max(severityOf(ScoreSampler.Dim.VISUAL, profile),
                    severityOf(ScoreSampler.Dim.MASK, profile));
            double v = 1.0 - sev, k = Math.max(0.0, 1.0 - 1.6 * sev);
            return render(Math.min(v, k), page(v, k, 0.95));
        };

        ScoreSampler.Sample s = new ScoreSampler(0.05, 8).sample(
                render, new Random(13), 0.60, ScoreSampler.Dim.VISUAL, null);

        assertEquals(ScoreSampler.Dim.MASK, s.aimed());
        assertTrue(s.onTarget(), "should still converge after re-aim, got " + s.steer());
    }

    @Test
    void histogramFailsOnAnEmptyCorpus() {
        ScoreCalibration.Histogram h =
                ScoreCalibration.histogram(List.of(), 10, 0.20, 0.95);
        assertFalse(h.ok());
        assertEquals(0, h.rows());
    }

    @Test
    void fillableRangeIsTheUnionOfTheDimensionsNotAnyOne() {
        // The three curves cover different bands -- visual is the widest, mask
        // tops out low, bg bottoms out high -- so the union is strictly wider than
        // any single one. Taking any one curve would over-report the range and let
        // QA demand bins the other two can fill.
        double[] sev = {0, 0.5, 1.0};
        ScoreCalibration.Calibration cal = new ScoreCalibration.Calibration(
                ScoreCalibration.Curve.fit(ScoreSampler.Dim.VISUAL, sev, new double[] {0.90, 0.50, 0.10}),
                ScoreCalibration.Curve.fit(ScoreSampler.Dim.MASK, sev, new double[] {0.75, 0.50, 0.45}),
                ScoreCalibration.Curve.fit(ScoreSampler.Dim.BG, sev, new double[] {0.95, 0.80, 0.40}),
                "fixture");
        double[] fill = cal.fillableRange();
        assertEquals(0.10, fill[0], 1e-9, "the dirtiest anything can get");
        assertEquals(0.95, fill[1], 1e-9, "the cleanest anything can get");
        assertTrue(cal.curveFor(ScoreSampler.Dim.MASK).dirtiest() > fill[0],
                "mask alone cannot reach the bottom of the union");
    }

    @Test
    void unreachableBinsAreExemptButStillReported() {
        // The realism texture puts a ceiling under how clean a degraded page can
        // measure, so the top of a [0.20,0.95] marginal is unreachable by
        // construction. Requiring those bins would make the check unsatisfiable,
        // and a check that always fails stops being read.
        List<ScoreSampler.Sample> xs = new ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            // Fill 0.20-0.80 densely and leave 0.80-0.95 empty.
            double t = 0.20 + 0.60 * (i + 0.5) / 1000;
            xs.add(fake(t, t, true));
        }
        ScoreCalibration.Histogram strict =
                ScoreCalibration.histogram(xs, 10, 0.20, 0.95);
        assertFalse(strict.ok(), "with no reachable range the empty top is a real failure");

        ScoreCalibration.Histogram aware =
                ScoreCalibration.histogram(xs, 10, 0.20, 0.95, 0.20, 0.80);
        assertTrue(aware.ok(), "an unreachable bin should not be a failure: " + aware.problems());
    }

    @Test
    void aBinStraddlingTheReachableEdgeIsStillChecked() {
        // Exempting bins outside the reachable range must not exempt a bin that
        // merely overlaps it. Bins are 0.075 wide here, so a reachable top of 0.60
        // leaves 0.50-0.58 and 0.58-0.65 partly fillable and 0.65-0.73 wholly not.
        List<ScoreSampler.Sample> xs = new ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            double t = 0.20 + 0.30 * (i + 0.5) / 1000;
            xs.add(fake(t, t, true));
        }
        ScoreCalibration.Histogram h =
                ScoreCalibration.histogram(xs, 10, 0.20, 0.95, 0.20, 0.60);

        assertTrue(h.problems().stream().anyMatch(p -> p.contains("0.50-0.58")),
                "a bin inside the reachable range should be required, got " + h.problems());
        assertTrue(h.problems().stream().anyMatch(p -> p.contains("0.58-0.65")),
                "the bin straddling the edge should be required, got " + h.problems());
        assertFalse(h.problems().stream().anyMatch(p -> p.contains("0.65-0.73")),
                "a bin wholly above the edge should be exempt, got " + h.problems());
    }

    @Test
    void reAimIsVisibleSoTheCorpusCannotLookCleanlySingleAxis() throws IOException {
        // A page drawn as background noise and converged on visual has degradation
        // in two dimensions. Reporting only the converged one would make the
        // corpus look like it satisfied the single-dimension premise, so the draw
        // has to survive into the map.
        Stub stub = new Stub(ScoreSamplerTest::monotone);
        ScoreSampler.Render render = (profile, rng) -> {
            stub.renders++;
            // Driving background noise also costs contrast, so the visual
            // sub-score caps the page from the very first attempt: the dimension
            // drawn is never the one binding.
            double sev = Math.max(severityOf(ScoreSampler.Dim.BG, profile),
                    severityOf(ScoreSampler.Dim.VISUAL, profile));
            return render(0.98 - 1.6 * sev, page(0.98 - 1.6 * sev, 0.99, 0.99));
        };

        ScoreSampler.Sample s = new ScoreSampler(0.05, 8).sample(
                render, new Random(29), 0.60, ScoreSampler.Dim.BG, null);

        assertEquals(ScoreSampler.Dim.BG, s.drawn(), "the draw must be preserved");
        assertEquals(ScoreSampler.Dim.VISUAL, s.binding(),
                "fixture no longer exercises re-aim; visual should cap the page");
        assertEquals(ScoreSampler.Dim.VISUAL, s.aimed());
        assertTrue(s.reaimed());
        assertTrue(s.onTarget(), "expected convergence, got steer=" + s.steer());
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

    // ── choosing which dimension to attack ─────────────────────────────────────

    /**
     * A curve spanning {@code [lo, hi]}, straight-line, so the fit's dirtiest
     * really is {@code lo}. The endpoints must be the values at the ends of the
     * line: starting the sequence anywhere else lets PAVA pool it upward and
     * hide the floor the caller is asserting about.
     *
     * <p>The defaults are the measured response of a real run: fully degrading a
     * page visually still measures 0.892 of its words readable, because a scan
     * that merely looks bad is still a legible scan.
     */
    private static ScoreCalibration.Curve curve(ScoreSampler.Dim d, double lo, double hi) {
        double[] sev = {0, 0.25, 0.5, 0.75, 1.0};
        double[] val = new double[5];
        for (int i = 0; i < 5; i++) {
            val[i] = hi + (lo - hi) * (i / 4.0);
        }
        return ScoreCalibration.Curve.fit(d, sev, val);
    }

    private static ScoreSampler.Steering calibratedSteering() {
        ScoreSampler.Steering st = new ScoreSampler.Steering(true, 0.40, 0.80, 0.10, 6,
                new ScoreSampler.Dim[] {ScoreSampler.Dim.VISUAL,
                        ScoreSampler.Dim.MASK, ScoreSampler.Dim.BG});
        st.calibrateWithoutRendering(new ScoreCalibration.Calibration(
                curve(ScoreSampler.Dim.VISUAL, 0.892, 1.000),
                curve(ScoreSampler.Dim.MASK, 0.929, 1.000),
                curve(ScoreSampler.Dim.BG, 0.201, 0.998),
                "measured"));
        return st;
    }

    @Test
    void aDimensionThatCannotReachTheTargetIsNotChosen() {
        // Degrading a page's visual quality barely touches word legibility -- a
        // fully visual-degraded page still measures 0.892 -- so VISUAL and MASK
        // physically cannot serve the bottom of the marginal. Aiming them at a
        // 0.30 target is a wasted re-aim: the sampler turns a knob that cannot
        // move the score and exhausts the budget. Over a 375-row build VISUAL was
        // drawn for 80% of pages and could not go below 0.241, and 109 of 137
        // misses landed *above* target, which is the signature of exactly that.
        ScoreSampler.Steering st = calibratedSteering();
        for (int seed = 0; seed < 60; seed++) {
            // Spread seeds, not 0..n: java.util.Random's low bits are weak, so a
            // run of consecutive seeds keeps drawing the same index and this test
            // would pass for the wrong reason.
            assertEquals(ScoreSampler.Dim.BG, st.drawDim(new Random(seed * 7919L), 0.30),
                    "only BG can reach 0.30; VISUAL floors at 0.892 and MASK at 0.929");
            assertEquals(ScoreSampler.Dim.BG, st.drawDim(new Random(seed * 7919L), 0.60),
                    "only BG can reach 0.60");
        }
    }

    @Test
    void aReachableTargetMayUseAnyDimensionThatCanReachIt() {
        // 0.95 sits in the overlap of visual [0.892,1.000] and bg
        // [0.201,0.998], so neither may be excluded -- the point is to avoid
        // collapsing onto one dimension, not to always pick the same one.
        ScoreSampler.Steering st = calibratedSteering();
        java.util.Set<ScoreSampler.Dim> seen = new java.util.HashSet<>();
        for (int seed = 0; seed < 200; seed++) {
            seen.add(st.drawDim(new Random(seed * 7919L), 0.95));
        }
        assertTrue(seen.contains(ScoreSampler.Dim.VISUAL) && seen.contains(ScoreSampler.Dim.BG),
                "expected both reachable dimensions, saw " + seen);
    }

    @Test
    void theDimensionDrawIsUniformBeforeAnythingHasBeenCalibrated() {
        // With no table there is nothing to reason from, and guessing from priors
        // would just relocate the problem somewhere less visible.
        ScoreSampler.Steering st = new ScoreSampler.Steering(true, 0.40, 0.80, 0.10, 6,
                new ScoreSampler.Dim[] {ScoreSampler.Dim.VISUAL, ScoreSampler.Dim.MASK});
        java.util.Set<ScoreSampler.Dim> seen = new java.util.HashSet<>();
        for (int seed = 0; seed < 200; seed++) {
            seen.add(st.drawDim(new Random(seed * 7919L), 0.30));
        }
        assertEquals(2, seen.size());
    }

    @Test
    void theReAimOntoTheBindingDimensionRespectsReachability() throws IOException {
        // The binding dimension is the lowest sub-score, so on a page whose
        // background was left clean it is VISUAL -- which cannot descend past
        // 0.892. Following it there on a low target burns the whole budget on a
        // dead knob and leaves the page far above target.
        //
        // The stub reports VISUAL as binding on every render and answers a
        // response that only BG could produce, so the only way to converge is to
        // refuse the re-aim and keep turning the dimension already in hand.
        Stub stub = new Stub(sev -> Math.max(0.0, 1.0 - 1.5 * sev));
        ScoreSampler.Sample s = new ScoreSampler(0.10, 6)
                .sample(stub, new Random(3), 0.45, ScoreSampler.Dim.BG,
                        new ScoreCalibration.Calibration(
                                curve(ScoreSampler.Dim.VISUAL, 0.892, 1.000),
                                curve(ScoreSampler.Dim.MASK, 0.929, 1.000),
                                curve(ScoreSampler.Dim.BG, 0.05, 1.000),
                                "measured"));

        assertEquals(ScoreSampler.Dim.BG, s.aimed(),
                "must not re-aim onto a dimension that cannot reach 0.45");
        assertTrue(s.onTarget(), "expected convergence, got " + s.steer());
    }
}
