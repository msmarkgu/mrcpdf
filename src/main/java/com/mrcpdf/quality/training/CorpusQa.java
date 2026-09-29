package com.mrcpdf.quality.training;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.mrcpdf.quality.model.MrcReadiness;

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
        double fillLo = 0.0, fillHi = 1.0;
        int bins = 10;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--map" -> map = Path.of(args[++i]);
                case "--steer-lo" -> lo = Double.parseDouble(args[++i]);
                case "--steer-hi" -> hi = Double.parseDouble(args[++i]);
                case "--fill-lo" -> fillLo = Double.parseDouble(args[++i]);
                case "--fill-hi" -> fillHi = Double.parseDouble(args[++i]);
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
        int clean = 0;
        int[] classMix = new int[3];
        for (int i = 1; i < lines.size(); i++) {
            if (lines.get(i).isBlank()) {
                continue;
            }
            String[] c = lines.get(i).split(",", -1);
            double mrc = Double.parseDouble(c[ix.get("labelWordF1")]);
            int cls = mrc >= MrcReadiness.GOOD_ABOVE ? 0
                    : (mrc < MrcReadiness.BAD_BELOW ? 2 : 1);
            classMix[cls]++;
            if (c[ix.get("steerValue")].isBlank()) {
                clean++;
                continue;
            }
            if (ix.containsKey("rMrcProxy")) {
                proxy.add(Double.parseDouble(c[ix.get("rMrcProxy")]));
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
        if (proxy.size() == steered.size()) {
            reportProxyAgreement(steered, proxy);
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
     */
    private static void reportProxyAgreement(List<Row> steered, List<Double> proxy) {
        // The accepted attempt's audit F1 *is* the label for a steered row, so
        // this compares the proxy against the label itself, not against the
        // target the sampler aimed at.
        List<Double> label = new ArrayList<>();
        for (Row r : steered) {
            label.add(r.steer());
        }
        System.out.printf(Locale.ROOT,
                "  corr(label, rMrcProxy) over steered rows: %+.3f%n",
                corr(proxy, label));
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
