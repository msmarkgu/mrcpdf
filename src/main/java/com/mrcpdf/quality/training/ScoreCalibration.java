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
    /**
     * Rows below which bin occupancy is reported but not enforced.
     *
     * <p>Ten rows per bin, so a 10-bin check is judging something. A 30-row
     * slice expects 3 per bin and empties a quarter of them by chance alone, which
     * makes any per-bin rule either fail a good corpus or pass a bad one.
     */
    public static final int MIN_QA_ROWS = 100;

    /**
     * Share of steered rows allowed to miss their target.
     *
     * <p>Misses are a recorded outcome, not a silent one: an unreachable target
     * still yields a page, flagged, and the map carries it. What matters is that
     * they stay rare enough that the corpus is still mostly where the marginal
     * asked. Measured 22% on the 375-row corpus, rising with the target because
     * the response flattens near its ceiling, so the ceiling is set well above
     * the observed rate -- a regression that doubles it should fail, and a run
     * that cannot hit two thirds of its targets should too.
     */
    public static final double MAX_MISS_RATE = 0.35;
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
        public static Curve fit(ScoreSampler.Dim dim, double[] severities, double[] scores) {            if (severities.length != scores.length) {
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

        /**
         * Combines curves measured on different reference pages.
         *
         * <p>Evaluates every curve on one common severity grid and takes the
         * <em>median</em> at each point. Median rather than mean because the
         * result is only a seed for the sampler's bisection, so what matters is
         * that it lands near the middle of how pages actually respond; one
         * atypical reference page should not drag it, and should not be averaged
         * into a target nothing reaches.
         *
         * <p>The extremes are the union across references, not the median's, so
         * the reachable range QA is checked against stays the full span the
         * corpus can produce.
         */
        public static Curve merge(Curve... curves) {
            if (curves.length == 0) throw new IllegalArgumentException("no curves to merge");
            if (curves.length == 1) return curves[0];
            ScoreSampler.Dim dim = curves[0].dim;
            int points = 21;
            double[][] responses = new double[curves.length][points];
            for (int c = 0; c < curves.length; c++) {
                for (int i = 0; i < points; i++) {
                    responses[c][i] = curves[c].responseAt((double) i / (points - 1));
                }
            }
            double[] severity = new double[points];
            double[] score = new double[points];
            for (int i = 0; i < points; i++) {
                double[] column = new double[curves.length];
                for (int c = 0; c < curves.length; c++) column[c] = responses[c][i];
                Arrays.sort(column);
                int mid = column.length / 2;
                // Average the middle pair on an even count. Taking the upper one
                // instead would bias every seed toward whichever page responded
                // best, which is the opposite of what "typical" means here.
                severity[i] = (double) i / (points - 1);
                score[i] = column.length % 2 == 1
                        ? column[mid]
                        : (column[mid - 1] + column[mid]) / 2.0;
            }
            Curve median = fit(dim, severity, score);
            return new Curve(dim, median.severity, median.score, sumRaw(curves));
        }

        private static int sumRaw(Curve[] curves) {
            int n = 0;
            for (Curve c : curves) n += c.rawPoints;
            return n;
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

        /** The response interpolated at an arbitrary severity, for merging. */
        public double responseAt(double sev) {
            if (sev <= severity[0]) return score[0];
            int last = severity.length - 1;
            if (sev >= severity[last]) return score[last];
            int i = 0;
            while (i < last && severity[i + 1] < sev) i++;
            double span = severity[i + 1] - severity[i];
            if (span <= 0) return score[i];
            double t = (sev - severity[i]) / span;
            return score[i] + t * (score[i + 1] - score[i]);
        }
    }

    // ── The three-dimension calibration ───────────────────────────────────────

    /**
     * One curve per degradation dimension.
     *
     * @param reference a description of the pages the curves were measured on, for
     *                  the report.
     *
     * <p>The curves are page-specific by nature -- a clean typeset page and a dense
     * one have different blur responses -- so they cannot be transferred between
     * pages exactly. They are probed on a small fixed sample of pages spread across
     * the corpus and merged, rather than on whichever page happened to arrive
     * first, because a single reference is a single draw from that spread. On the
     * 10-page slice the one-page probe reported a cleanest of 0.66 and left the
     * 0.58-0.65 bin of the target marginal structurally empty: targets just under
     * the reference's own ceiling had no curve to resolve them against.
     */
    public record Calibration(Curve visual, Curve mask, Curve bg, String reference,
                              double fillLo, double fillHi) {

        /**
         * A calibration measured on one page, whose reachable range is that page's.
         */
        public Calibration(Curve visual, Curve mask, Curve bg, String reference) {
            this(visual, mask, bg, reference,
                    spanOf(visual, mask, bg)[0], spanOf(visual, mask, bg)[1]);
        }

        private static double[] spanOf(Curve... curves) {
            double low = 1.0, high = 0.0;
            for (Curve c : curves) {
                low = Math.min(low, c.dirtiest());
                high = Math.max(high, c.cleanest());
            }
            return new double[]{Math.max(0.0, low), Math.min(1.0, high)};
        }

        /**
         * Merges per-reference calibrations into one, dimension by dimension.
         *
         * <p>The curves become the median page's response, because they are only a
         * bisection seed and should be typical rather than extreme. The reachable
         * range is the union across references instead, because that answers a
         * different question -- what any page in the corpus can produce -- and
         * taking the median there would hide a range the generator really reaches.
         */
        public static Calibration merge(List<Calibration> parts) {
            if (parts.isEmpty()) throw new IllegalArgumentException("no calibrations to merge");
            if (parts.size() == 1) return parts.get(0);
            double low = 1.0, high = 0.0;
            for (Calibration c : parts) {
                low = Math.min(low, c.fillLo());
                high = Math.max(high, c.fillHi());
            }
            return new Calibration(
                    Curve.merge(parts.stream().map(c -> c.visual).toArray(Curve[]::new)),
                    Curve.merge(parts.stream().map(c -> c.mask).toArray(Curve[]::new)),
                    Curve.merge(parts.stream().map(c -> c.bg).toArray(Curve[]::new)),
                    "merged from " + parts.size() + " pages: "
                            + parts.stream().map(c -> c.reference).toList(),
                    Math.max(0.0, low), Math.min(1.0, high));
        }

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
         * The span of targets that <em>some</em> dimension can reach.
         *
         * <p>The union of the three curves' ranges, which is not the same as any
         * one of them.  The QA needs this because the target marginal is a policy
         * choice while the reachable range is a property of the generator: the
         * realism texture (motion blur, JPEG, skew, curl) puts a floor under how
         * clean any degraded page can measure, so on the 10-page slice no
         * dimension exceeded 0.81 and the top of a [0.20, 0.95] marginal was
         * unreachable by construction.  Requiring those bins to be filled would
         * make the check unsatisfiable, and a check that always fails stops being
         * read.
         *
         * <p>The clean variant carries the pristine end of the range, so a
         * degraded page that cannot reach 0.95 is not a gap in the corpus.
         */
        public double[] fillableRange() {
            return new double[]{fillLo, fillHi};
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
            double[] fill = fillableRange();
            if (fill[0] > lo || fill[1] < hi) {
                System.out.printf(Locale.ROOT,
                        "  the sampler's reachable range is [%.2f,%.2f]; bins outside it are not "
                                + "required to be filled.%n", fill[0], fill[1]);
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
                            double fillLo, double fillHi,
                            int[] steerCounts, int[] classCounts, int misses,
                            double maxTargetError, List<String> problems, List<String> notes) {

        /** Whether the check passed, i.e. no problems were found. */
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
                boolean fillable = fillHi > fillLo;
                String tag = (fillable && (b <= fillLo || a >= fillHi)) ? "  (unreachable)" : "";
                System.out.printf(Locale.ROOT, "    %.2f-%.2f   %-6d %.3f%s%n",
                        a, b, steerCounts[i], share(steerCounts[i]), tag);
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
        return histogram(rows, bins, lo, hi, lo, hi);
    }

    /**
     * As the six-argument form, but with the span of targets that is actually
     * reachable.  Bins lying entirely outside it are reported and exempt.
     *
     * @see Calibration#fillableRange()
     */
    public static Histogram histogram(List<? extends ScoreSampler.SteeringRow> rows, int bins,
                                      double lo, double hi, double fillLo, double fillHi) {
        int n = Math.max(1, bins);
        int[] steerCounts = new int[n];
        int[] classCounts = new int[3];
        int misses = 0;
        int converged = 0;
        double worst = 0.0;
        List<String> notes = new ArrayList<>();

        for (ScoreSampler.SteeringRow s : rows) {
            int c = s.cls();
            classCounts[c >= 0 && c < 3 ? c : 2]++;
            worst = Math.max(worst, Math.abs(s.steer() - s.target()));
            if (!s.onTarget()) {
                misses++;
            } else {
                // Uniformity is judged on the rows that landed where they were
                // asked to. A miss is a page the generator could not place, and it
                // is already counted, gated and reported in its own right; folding
                // it into the distribution as though it were a placement would ask
                // the marginal to be uniform over targets the generator cannot
                // reach, which is the same unsatisfiable demand as requiring a bin
                // no dimension can fill.
                int b = bin(s.steer(), lo, hi, n);
                if (b >= 0) steerCounts[b]++;
                converged++;
            }
        }
        int samples = rows.size();

        List<String> problems = new ArrayList<>();
        if (samples == 0) {
            problems.add("no steered samples at all");
        } else {
            // A uniformity check needs power.  With 10 bins a 30-row slice expects
            // 3 per bin, and roughly a quarter of the bins come out empty by chance
            // alone -- so report the sample as too small to judge rather than
            // failing a corpus that may well be fine, and rather than passing it
            // because the noise happened to land favourably.
            double missRate = (double) misses / samples;
            if (missRate > MAX_MISS_RATE) {
                problems.add(String.format(
                        Locale.ROOT, "miss rate %.3f exceeds %.3f: the marginal asks for targets "
                                + "the generator cannot reach", missRate, MAX_MISS_RATE));
            }
            notes.add(String.format(Locale.ROOT,
                    "%d of %d rows missed their target (%.1f%%); uniformity judged on the "
                            + "remaining %d", misses, samples, 100 * missRate, converged));

            boolean powered = converged >= MIN_QA_ROWS;
            if (!powered) {
                notes.add(String.format(
                        Locale.ROOT,
                        "only %d converged rows: a %d-bin uniformity check has no power at this "
                                + "size (needs %d); bin occupancy was not enforced",
                        converged, n, MIN_QA_ROWS));
            }
            double expected = (double) converged / n;
            // Three sigma below the expected count.  A floor of a fixed fraction of
            // the rows would be wrong: requiring every bin to hold at least the
            // mean is unsatisfiable, because half of a uniform sample's bins are
            // below the mean by definition.
            int floor = powered
                    ? (int) Math.max(1, Math.ceil(expected - 3 * Math.sqrt(expected)))
                    : 1;
            boolean fillable = fillHi > fillLo;
            for (int i = 0; i < n; i++) {
                double a = lo + (hi - lo) * i / n;
                double b = lo + (hi - lo) * (i + 1) / n;
                // A bin the sampler cannot reach is not a distribution failure, so
                // it is neither required nor counted against the corpus.
                if (fillable && (b <= fillLo || a >= fillHi)) continue;
                if (steerCounts[i] < floor) {
                    problems.add(String.format(
                            "empty bin %.2f-%.2f: %d rows, expected %.1f, floor %d; "
                                    + "steering is not uniform over the marginal",
                            a, b, steerCounts[i], expected, floor));
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
        return new Histogram(samples, n, lo, hi, fillLo, fillHi, steerCounts, classCounts,
                misses, worst, problems, notes);
    }

    static int bin(double v, double lo, double hi, int bins) {
        if (Double.isNaN(v) || v < lo || v > hi) return -1;
        int b = (int) Math.floor((v - lo) / (hi - lo) * bins);
        return Math.max(0, Math.min(bins - 1, b));
    }
}
