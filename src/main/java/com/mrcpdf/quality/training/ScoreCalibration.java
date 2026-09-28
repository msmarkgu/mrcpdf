package com.mrcpdf.quality.training;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * Turns "which severity produces which measured score" into something the
 * {@link ScoreSampler} can aim with.
 *
 * <p>Two jobs.  First, fit one monotone response curve per degradation dimension
 * by rendering a reference page across a severity grid, so the sampler's first
 * attempt already lands in the right neighborhood instead of spending three or
 * four OCR passes getting there.  Second, report which targets are actually
 * reachable, because a dimension whose measured range does not cover the target
 * marginal is a silent source of misses that the score histogram alone cannot
 * explain.
 *
 * <p>The curves are fit by pool-adjacent-violators, not by a free spline.  The
 * response is monotone -- more degradation never measures cleaner -- but the
 * individual grid points are noisy enough to cross over, and a curve that
 * crosses over would hand the sampler's re-aim loop a non-monotone target and
 * send it oscillating between severities.  PAVA is the smallest fit that
 * guarantees monotonicity.
 */
public final class ScoreCalibration {

    /** Severity grid points per dimension. 11 is enough to pin the curve's knee. */
    public static final int DEFAULT_STEPS = 11;
    /** Histogram bins over the target marginal. */
    public static final int DEFAULT_BINS = 10;
    /** A bin holding less than this fraction of the rows counts as empty. */
    public static final double MAX_EMPTY_FRACTION = 0.10;
    /** Largest mean |achieved - target| inside a bin that still passes QA. */
    public static final double MAX_TARGET_ERROR = 0.06;

    private ScoreCalibration() {
    }

    // ── Response curves ───────────────────────────────────────────────────────

    /**
     * A monotone severity-to-score response for one degradation dimension.
     *
     * <p>Stored as decreasing (more severity, lower score) to match the physical
     * direction, which is also the direction {@link ScoreSampler}'s re-aim loop
     * moves in.
     */
    public static final class Curve {

        private final ScoreSampler.Dim dim;
        private final double[] severity;
        private final double[] score;
        private final int rawPoints;

        private Curve(ScoreSampler.Dim dim, double[] severity, double[] score, int rawPoints) {
            this.dim = dim;
            this.severity = severity;
            this.score = score;
            this.rawPoints = rawPoints;
        }

        /** Fits a curve from (severity, score) samples in any order. */
        public static Curve fit(ScoreSampler.Dim dim, double[] severities, double[] scores) {
            if (severities.length != scores.length) {
                throw new IllegalArgumentException(
                        severities.length + " severities but " + scores.length + " scores");
            }
            if (severities.length < 2) {
                throw new IllegalArgumentException("need at least 2 grid points, got " + severities.length);
            }
            Integer[] order = new Integer[severities.length];
            for (int i = 0; i < order.length; i++) order[i] = i;
            Arrays.sort(order, (a, b) -> Double.compare(severities[a], severities[b]));

            // Pool adjacent violators, on x-mean and y-mean per block.  Weights
            // are equal (one grid point each); only the x spacing is uneven, and
            // taking the block's mean x is the standard approximation for that.
            List<double[]> blocks = new ArrayList<>();
            for (Integer i : order) {
                blocks.add(new double[] {severities[i], scores[i]});
                while (blocks.size() >= 2) {
                    int n = blocks.size();
                    if (blocks.get(n - 2)[1] >= blocks.get(n - 1)[1]) break;
                    double[] a = blocks.remove(n - 2);
                    double[] b = blocks.remove(n - 2);
                    blocks.add(new double[] {(a[0] + b[0]) / 2.0, (a[1] + b[1]) / 2.0});
                }
            }

            double[] sx = new double[blocks.size()];
            double[] sy = new double[blocks.size()];
            for (int i = 0; i < blocks.size(); i++) {
                sx[i] = blocks.get(i)[0];
                sy[i] = blocks.get(i)[1];
            }
            // Collapse the flat runs PAVA leaves behind, so interpolation happens
            // on genuine crossings rather than on a staircase of equal values.
            List<Integer> keep = new ArrayList<>();
            for (int i = 0; i < sx.length; i++) {
                if (i + 1 < sx.length && sy[i] == sy[i + 1]) continue;
                keep.add(i);
            }
            if (keep.size() < 2) {
                return new Curve(dim, new double[] {0, 1}, new double[] {sy[0], sy[0]},
                        severities.length);
            }
            double[] fx = new double[keep.size()];
            double[] fy = new double[keep.size()];
            for (int i = 0; i < keep.size(); i++) {
                fx[i] = sx[keep.get(i)];
                fy[i] = sy[keep.get(i)];
            }
            return new Curve(dim, fx, fy, severities.length);
        }

        public ScoreSampler.Dim dim() {
            return dim;
        }

        /** Highest score this dimension measured: its cleanest response. */
        public double cleanest() {
            return score[0];
        }

        /** Lowest score this dimension measured: its dirtiest response. */
        public double dirtiest() {
            return score[score.length - 1];
        }

        /** How many grid points went in before pooling; a fit with a large drop is noisy. */
        public int rawPoints() {
            return rawPoints;
        }

        /**
         * Whether the dimension can reach {@code target} at all.
         *
         * <p>The re-aim loop cannot escape this range, so a target outside it is a
         * guaranteed miss and the corpus report should say so instead of leaving
         * the reader to infer it from a lumpy histogram.
         */
        public boolean reachable(double target) {
            return target >= dirtiest() && target <= cleanest();
        }

        /** The measured score at a given severity, by linear interpolation. */
        public double scoreAt(double sev) {
            double s = ScoreSampler.clamp01(sev);
            if (s <= severity[0]) return score[0];
            int last = severity.length - 1;
            if (s >= severity[last]) return score[last];
            int i = 0;
            while (i < last && severity[i + 1] < s) i++;
            double span = severity[i + 1] - severity[i];
            if (span <= 0) return score[i + 1];
            double t = (s - severity[i]) / span;
            return score[i] + t * (score[i + 1] - score[i]);
        }

        /**
         * Inverts the curve: the severity whose measured score is nearest
         * {@code target}.
         *
         * <p>This is the sampler's first guess, and it is clamped rather than
         * extrapolated.  Asking for a score above the cleanest measured response
         * means the reference page itself is the best this dimension can do, so
         * the answer is severity 0 -- there is nothing gentler available.
         */
        public double severityFor(double target) {
            int last = severity.length - 1;
            if (target >= score[0]) return 0.0;
            if (target <= score[last]) return 1.0;
            int i = 0;
            while (i < last && score[i + 1] > target) i++;
            double drop = score[i] - score[i + 1];
            if (drop <= 0) return severity[i + 1];
            double t = (score[i] - target) / drop;
            return severity[i] + t * (severity[i + 1] - severity[i]);
        }
    }

    // ── The three-dimension calibration ───────────────────────────────────────

    /**
     * One curve per degradation dimension.
     *
     * @param reference a description of the page the curves were measured on, for
     *                  the report.  The curves are page-specific by nature -- a
     *                  clean typeset page and a dense one have different blur
     *                  responses -- which is why they are probed once per run
     *                  against a fixed reference rather than per page.
     */
    public record Calibration(Curve visual, Curve mask, Curve bg, String reference) {

        public Curve curveFor(ScoreSampler.Dim dim) {
            return switch (dim) {
                case VISUAL -> visual;
                case MASK -> mask;
                case BG -> bg;
            };
        }

        public double severityFor(ScoreSampler.Dim dim, double target) {
            return curveFor(dim).severityFor(target);
        }

        /**
         * Prints each dimension's measured range and flags any that does not
         * cover the target marginal.
         *
         * <p>Without this, a dimension that bottoms out at 0.55 looks the same in
         * the histogram as one that bottoms out at 0.05, and the two have
         * completely different reasons for producing misses.
         */
        public void report(double lo, double hi) {
            System.out.printf("  steering calibration (reference: %s)%n", reference);
            System.out.printf("    %-7s %-13s %-13s %s%n", "dim", "cleanest", "dirtiest", "covers marginal");
            for (ScoreSampler.Dim d : ScoreSampler.Dim.values()) {
                Curve c = curveFor(d);
                boolean covers = c.reachable(lo) && c.reachable(hi);
                String note = covers
                        ? "yes"
                        : String.format("NO  [%.2f,%.2f] does not reach %.2f or %.2f",
                                c.dirtiest(), c.cleanest(), lo, hi);
                System.out.printf("    %-7s %-13.3f %-13.3f %s%n",
                        d.slug(), c.cleanest(), c.dirtiest(), note);
            }
        }
    }

    /**
     * Measures a reference page across a severity grid and fits the three curves.
     *
     * <p>Costs {@code 3 * steps} OCR passes, so callers run it once per run and
     * reuse the result across every page and every shard.  The texture jitter is
     * drawn once and held across the whole grid: if it were redrawn per point the
     * grid would measure the dimension's response plus texture noise, and the
     * fitted curve would be flatter than the response the sampler actually sees
     * on a real page.
     */
    public static Calibration probe(ScoreSampler.Render render, Random rng, int steps,
                                    String reference) throws java.io.IOException {
        int n = Math.max(2, steps);
        ScoreSampler.Texture tex = ScoreSampler.Texture.draw(rng);
        Curve[] curves = new Curve[ScoreSampler.Dim.values().length];
        for (ScoreSampler.Dim dim : ScoreSampler.Dim.values()) {
            double[] sev = new double[n];
            double[] kappa = new double[n];
            for (int i = 0; i < n; i++) {
                double s = (double) i / (n - 1);
                sev[i] = s;
                ScoreSampler.Rendered r =
                        render.render(ScoreSampler.profileFor(dim, s, tex), rng);
                kappa[i] = ScoreSampler.steerKappa(r.mrc());
            }
            curves[dim.ordinal()] = Curve.fit(dim, sev, kappa);
        }
        return new Calibration(curves[0], curves[1], curves[2], reference);
    }

    // ── Corpus QA ─────────────────────────────────────────────────────────────

    /**
     * What a histogram check found.
     *
     * @param misses rows that did not converge onto their target; reported rather
     *               than dropped, since an unreachable target still yields a
     *               usable training row
     * @param problems human-readable failures; empty means the corpus passed
     */
    public record Histogram(int rows, int bins, double lo, double hi,
                            int[] steerCounts, int[] classCounts, int misses,
                            double maxTargetError, List<String> problems) {

        public boolean ok() {
            return problems.isEmpty();
        }

        public double missRate() {
            return rows == 0 ? 0.0 : (double) misses / rows;
        }

        public void print() {
            System.out.printf("  steering histogram over [%.2f,%.2f], %d bins, %d rows%n",
                    lo, hi, bins, rows);
            System.out.printf("    %-13s %-6s %s%n", "bin", "rows", "share");
            for (int i = 0; i < bins; i++) {
                double a = lo + (hi - lo) * i / bins;
                double b = lo + (hi - lo) * (i + 1) / bins;
                System.out.printf(Locale.ROOT, "    %.2f-%.2f   %-6d %.3f%n",
                        a, b, steerCounts[i], share(steerCounts[i]));
            }
            // The class mix is a separate three-number summary, not a per-bin
            // breakdown: it is graded on the full R_mrc rather than the steer
            // value, so putting it in the bin table would imply the two are
            // per-row comparable when they are not.
            System.out.printf(Locale.ROOT, "  class mix (full R_mrc): good %d (%.3f), "
                            + "middle %d (%.3f), bad %d (%.3f)%n",
                    classCounts[0], share(classCounts[0]),
                    classCounts[1], share(classCounts[1]),
                    classCounts[2], share(classCounts[2]));
            System.out.printf(Locale.ROOT, "  %d/%d rows missed their target (%.1f%%), "
                            + "worst error %.3f%n", misses, rows, 100 * missRate(), maxTargetError);
            for (String p : problems) System.out.printf("  PROBLEM: %s%n", p);
        }

        private double share(int n) {
            return rows == 0 ? 0.0 : (double) n / rows;
        }
    }

    /**
     * Checks that the corpus fills the target marginal and that the class mix
     * came out as intended.
     *
     * <p>The two are read off different numbers on purpose.  The histogram bins
     * the <em>steering</em> value, which excludes {@code S_ocr} and so sits
     * systematically above the full composite; grading those bins against
     * {@code MrcReadiness.GOOD_ABOVE} / {@code BAD_BELOW} would over-count
     * {@code good} pages by construction and report a healthier mix than the
     * corpus has.  So the mix comes from the full {@code R_mrc} and the
     * histogram from the steer value, and neither is asked to stand in for the
     * other.
     */
    public static Histogram histogram(List<? extends ScoreSampler.SteeringRow> rows, int bins,
                                      double lo, double hi) {
        int n = Math.max(1, bins);
        int[] steerCounts = new int[n];
        int[] classCounts = new int[3];
        int misses = 0;
        double worst = 0.0;

        for (ScoreSampler.SteeringRow s : rows) {
            int b = bin(s.steer(), lo, hi, n);
            if (b >= 0) steerCounts[b]++;
            int c = s.cls();
            classCounts[c >= 0 && c < 3 ? c : 2]++;
            if (!s.onTarget()) misses++;
            worst = Math.max(worst, Math.abs(s.steer() - s.target()));
        }
        int samples = rows.size();

        List<String> problems = new ArrayList<>();
        if (samples == 0) {
            problems.add("no steered samples at all");
        } else {
            int maxEmpty = (int) Math.floor(MAX_EMPTY_FRACTION * samples);
            for (int i = 0; i < n; i++) {
                // Strictly below the threshold, and zero always: a bin holding
                // exactly MAX_EMPTY_FRACTION of the rows is a populated bin, and
                // with a uniform marginal that is the expected outcome rather than
                // a failure.  The explicit zero keeps small corpora honest, where
                // the floor rounds down to nothing.
                if (steerCounts[i] == 0 || steerCounts[i] < maxEmpty) {
                    double a = lo + (hi - lo) * i / n;
                    double b = lo + (hi - lo) * (i + 1) / n;
                    problems.add(String.format(
                            "empty bin %.2f-%.2f: steering is not uniform over the marginal", a, b));
                }
            }
            if (worst > MAX_TARGET_ERROR && misses == 0) {
                problems.add(String.format(
                        "worst target error %.3f exceeds %.3f with no misses recorded",
                        worst, MAX_TARGET_ERROR));
            }
            int total = classCounts[0] + classCounts[1] + classCounts[2];
            if (total != samples) {
                problems.add("class mix does not account for every row");
            }
        }
        return new Histogram(samples, n, lo, hi, steerCounts, classCounts,
                misses, worst, problems);
    }

    static int bin(double v, double lo, double hi, int bins) {
        if (Double.isNaN(v) || v < lo || v > hi) return -1;
        int b = (int) Math.floor((v - lo) / (hi - lo) * bins);
        return Math.max(0, Math.min(bins - 1, b));
    }
}
