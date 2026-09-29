package com.mrcpdf.quality.training;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;


/**
 * Re-runs the steering histogram QA against a map that already exists.
 *
 * <p>{@code dumpRealTrain} runs this same check at the end of a ninety-minute
 * generation. That is a poor place to first learn whether a change to the
 * check itself was correct, and the corpus is expensive enough that nobody
 * wants to regenerate one to find out. This reads the map and answers in
 * under a second.
 *
 * <p>The reachable range is an argument rather than something recomputed here:
 * it comes from a calibration probe, and re-probing would mean rendering every
 * reference page again. Pass what the run reported.
 */
public final class CorpusQa {

    public static void main(String[] args) throws Exception {
        Path map = null;
        double lo = ScoreSampler.DEFAULT_LO, hi = ScoreSampler.DEFAULT_HI;
        // Overridden by --good-above/--bad-below; the defaults are the terciles of
        // the [0.40,0.80] marginal that RealTrainDump steers by default.
        double goodAbove = 0.6667, badBelow = 0.5333;
        double fillLo = 0.0, fillHi = 1.0;
        int bins = 10;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--map" -> map = Path.of(args[++i]);
                case "--steer-lo" -> lo = Double.parseDouble(args[++i]);
                case "--steer-hi" -> hi = Double.parseDouble(args[++i]);
                case "--fill-lo" -> fillLo = Double.parseDouble(args[++i]);
                case "--fill-hi" -> fillHi = Double.parseDouble(args[++i]);
                case "--good-above" -> goodAbove = Double.parseDouble(args[++i]);
                case "--bad-below" -> badBelow = Double.parseDouble(args[++i]);
                case "--bins" -> bins = Integer.parseInt(args[++i]);
                default -> throw new IllegalArgumentException("Unknown arg: " + args[i]);
            }
        }
        if (map == null) {
            throw new IllegalArgumentException("--map is required");
        }

        List<String> lines = Files.readAllLines(map);
        if (lines.isEmpty()) {
            throw new IllegalStateException("empty map: " + map);
        }
        String[] header = lines.get(0).split(",", -1);
        Map<String, Integer> ix = new HashMap<>();
        for (int i = 0; i < header.length; i++) {
            ix.put(header[i], i);
        }
        for (String col : new String[] {"labelWordF1", "steerValue"}) {
            if (!ix.containsKey(col)) {
                throw new IllegalStateException(
                        "map has no " + col + " column; is it a steered corpus? " + map);
            }
        }

        List<Row> steered = new ArrayList<>();
        List<Double> proxy = new ArrayList<>();
        List<Double> proxyCol = new ArrayList<>();
        int clean = 0;
        int[] classMix = new int[3];
        for (int i = 1; i < lines.size(); i++) {
            if (lines.get(i).isBlank()) {
                continue;
            }
            String[] c = lines.get(i).split(",", -1);
            double mrc = Double.parseDouble(c[ix.get("labelWordF1")]);
            // The audit label has no natural good/bad cut -- it is a word F1, not
            // the R_mrc composite that used to carry the thresholds. RealTrainDump
            // derives the cuts from the marginal's terciles and prints them, so take
            // them as arguments; guessing them here would grade the corpus against a
            // partition it was not built for.
            int cls = mrc >= goodAbove ? 0 : (mrc < badBelow ? 2 : 1);
            classMix[cls]++;
            if (ix.containsKey("rMrcProxy")) {
                proxy.add(mrc);
                proxyCol.add(Double.parseDouble(c[ix.get("rMrcProxy")]));
            }
            if (c[ix.get("steerValue")].isBlank()) {
                clean++;
                continue;
            }
            steered.add(new Row(
                    Double.parseDouble(c[ix.get("steerValue")]),
                    Double.parseDouble(c[ix.get("steerTarget")]),
                    cls,
                    "true".equals(c[ix.get("onTarget")])));
        }

        int total = clean + steered.size();
        System.out.printf(Locale.ROOT, "%s: %d rows (%d steered, %d unsteered)%n",
                map, total, steered.size(), clean);
        System.out.printf(Locale.ROOT,
                "  class mix over all rows (label word F1): good %d (%.3f), middle %d (%.3f), bad %d (%.3f)%n",
                classMix[0], classMix[0] / (double) total, classMix[1],
                classMix[1] / (double) total, classMix[2], classMix[2] / (double) total);

        if (steered.isEmpty()) {
            System.out.println("  nothing steered; nothing to check");
            return;
        }

        // The label-grounding check this whole change exists to satisfy. The label
        // is now the audit, so it cannot disagree with itself -- which means the
        // useful question is whether the recorded proxy still tracks it, because
        // that is the number the design used to trust and the one a future
        // reader will reach for.
        if (!proxy.isEmpty()) {
            reportProxyAgreement(proxy, proxyCol);
        }

        ScoreCalibration.Histogram h = ScoreCalibration.histogram(steered, bins, lo, hi, fillLo, fillHi);
        h.print();
        if (h.ok()) {
            System.out.println("  ok: corpus passes steering QA");
            return;
        }
        h.problems().forEach(p -> System.out.println("  PROBLEM: " + p));
        throw new IllegalStateException("corpus failed steering QA: " + map);
    }

    /**
     * How well the recorded {@code R_mrc} proxy tracks the label.
     *
     * <p>Not a pass/fail gate. The label is the audit now, so this cannot drift
     * from the truth; what it shows is whether the proxy is still worth anything
     * as a reference-free stand-in, which is the one thing the design used to
     * depend on. It was +0.90 before steering and +0.47 after, so this line is
     * the number to watch on any future corpus.
     *
     * <p>Over <em>all</em> rows, not the steered ones. Restricted to steered
     * rows it is a meaningless -0.015, because their labels are pinned to the
     * targets the sampler chose and carry no information about the page -- which
     * is a number that reads like the proxy has stopped working, when in fact it
     * says nothing either way. The all-rows figure is +0.53 and is the one the
     * dump reports.
     */
    private static void reportProxyAgreement(List<Double> label, List<Double> proxy) {
        System.out.printf(Locale.ROOT,
                "  corr(label, rMrcProxy) over all rows: %+.3f "
                        + "(diagnostic; the label is the audit, the proxy is not load-bearing)%n",
                corr(label, proxy));
    }

    private static double corr(List<Double> a, List<Double> b) {
        if (a.size() != b.size() || a.size() < 3) {
            return Double.NaN;
        }
        double ma = 0, mb = 0;
        for (int i = 0; i < a.size(); i++) {
            ma += a.get(i);
            mb += b.get(i);
        }
        ma /= a.size();
        mb /= b.size();
        double num = 0, da = 0, db = 0;
        for (int i = 0; i < a.size(); i++) {
            double x = a.get(i) - ma, y = b.get(i) - mb;
            num += x * y;
            da += x * x;
            db += y * y;
        }
        return (da <= 0 || db <= 0) ? Double.NaN : num / Math.sqrt(da * db);
    }

    /** Minimal {@link ScoreSampler.SteeringRow} over one map line. */
    private record Row(double steer, double target, int cls, boolean onTarget)
            implements ScoreSampler.SteeringRow {

        @Override
        public double steer() {
            return steer;
        }

        @Override
        public double target() {
            return target;
        }

        @Override
        public boolean onTarget() {
            return onTarget;
        }

        @Override
        public int cls() {
            return cls;
        }
    }
}
