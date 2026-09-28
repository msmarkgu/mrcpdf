package com.mrcpdf.quality.training;

import java.awt.image.BufferedImage;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;

import com.mrcpdf.model.TextBlock;
import com.mrcpdf.quality.model.MrcReadiness;

/**
 * Measures the {@link MrcReadiness.Raw} distribution of the corpus so the
 * sub-score references in {@code MrcRefs} describe real scans instead of an
 * idealized raster.
 *
 * <h2>Why</h2>
 *
 * Referenced to a perfect raster, a clean scan of a real page scored
 * {@code MASK = 0.01..0.12}: a sigma=0.5 Gaussian plus noise frays anti-aliased
 * glyph edges into sub-4px blobs and roughly halves the re-blur residual, so the
 * weakest dimension pinned the composite and 100% of a 500-page pilot came out
 * {@code bad}. See {@code MrcRefs} for the full write-up.
 *
 * <h2>Method</h2>
 *
 * Three passes over the same rasters, all measured through
 * {@link MrcReadiness#measure} — the same entry point {@code compute} uses, so the
 * distributions cannot drift away from the metric:
 * <ul>
 *   <li><b>clean</b>: {@code SyntheticCorpus.cleanScan}, which is what the corpus
 *       labels {@code tier=clean}. Supplies the <i>good</i> endpoint.</li>
 *   <li><b>photocopy</b>: {@link #MID_PROFILE}, an old photocopy (sigma=2.0 blur,
 *       sigma=1.5 noise). Not a label anchor — blur <i>removes</i> specks and
 *       <i>merges</i> strokes, so a merely blurry page is not a bad page on the
 *       speckle and fragmentation metrics. It is measured as a midpoint sanity
 *       check: a page that OCRs cleanly should land mid-scale.</li>
 *   <li><b>destroyed</b>: {@link #badProfile()}, a fixed {@link ScannerTier#OCR_STRUGGLES}
 *       draw. Supplies the <i>bad</i> endpoint, because "worthless" means the mask
 *       has actually broken — downsampled, eroded and noisy — not merely soft.</li>
 * </ul>
 *
 * All three passes get the same {@link SyntheticCorpus#LABEL_JPEG_QUALITY} encode, so
 * the JPEG contributes equally to each population and cancels out of the band widths.
 *
 * <p>Endpoints are robust percentiles rather than min/max: a handful of pages are
 * pathological (near-blank forms, heavy rules and tables) and a single outlier must
 * not be able to set a reference. The good end is the 10th percentile of the clean
 * distribution — a clean scan is only as good as the worst tenth of real pages. The
 * bad end is the 90th percentile of the destroyed distribution, except for the two
 * {@code sBg} terms, which take the 99th percentile of <em>every</em> degraded
 * population: a blur-dominated anchor is not the dirtiest paper, and sBg has no
 * bottleneck of its own, so a page past both of its endpoints collapses the whole
 * composite.
 *
 * <p>Finally the derived constants are applied back to all three populations to
 * print the score each one actually earns, so the label behaviour is visible before
 * anything is pasted into {@code MrcRefs}. Any metric whose clean and destroyed
 * distributions do not separate in the mapped direction is reported as
 * {@code SEPARATION FAILS} rather than being given a cliff-shaped band.
 *
 * <p>Run with {@code ./gradlew calibrateMrc}. The printed Java block is meant to be
 * pasted into {@code MrcRefs}; {@code notes/mrc-calibration.md} records the
 * distributions it came from.
 */
public final class MrcCalibration {

    /** Mid-scale reference: an old photocopy, not the worthless anchor. */
    static final ScannerProfile MID_PROFILE = new ScannerProfile(
            0, 0, 1, 2.0, 0, 0, 0, 0, 1.5, 0, 0, 0, 0, 0, 0, 1, 0, 0.98f);

    /**
     * The worthless anchor: one fixed {@code OCR_STRUGGLES} profile applied to every
     * page, so the bad end is a single consistent standard rather than a mix of
     * randomly chosen degradation axes. The same seed for every page is deliberate.
     */
    static ScannerProfile badProfile() {
        return ScannerTier.OCR_STRUGGLES.draw(new java.util.Random(20260926L));
    }

    /**
     * The anchor for {@code mSpeckle}, which counts sub-4px components.  The
     * destroyed anchor cannot serve it: downscaling averages single-pixel debris
     * into the paper, so {@code specklePerMpx} on a destroyed page is
     * <i>lower</i> than on a clean scan and the band comes out inverted.  Speckle
     * is an additive defect, so its worthless end is a page loaded with debris at
     * full resolution — dust, salt-and-pepper, paper texture and bleed — with no
     * downscale to erase it.
     */
    static ScannerProfile debrisProfile() {
        // Tonal range is deliberately left alone (gamma 1, no black/white crush,
        // no downscale) so the only difference from a clean scan is added debris.
        // Debris is what specklePerMpx counts; a tonal or scale change would
        // change that count for reasons unrelated to speckle.
        return new ScannerProfile(
                0, 0, 1, 0.3, 0, 0, 0.35, 0, 2.0, 0.0030, 0, 0, 500,
                0, 0, 1.0, 0, 1f);
    }

    private static final int DPI = 300;

    /**
     * Must match {@link RealTrainDump}'s default corpus seed, because the clean
     * population is meant to be the same image the corpus labels {@code good}.
     * A mismatch here silently calibrates against a different corpus.
     */
    private static final long CORPUS_SEED = 42L;

    private MrcCalibration() {
    }

    /** One page of one population: the measurements plus what is needed to re-score. */
    private record Sample(MrcReadiness.Raw raw, List<TextBlock> boxes, int w, int h) {
        MrcReadiness.Breakdown score() {
            return MrcReadiness.score(raw, boxes, w, h);
        }
    }

    // Population names.  Referenced by the reporting code instead of by argument
    // position, so a report row cannot end up describing a different population
    // than the one it was measured from.
    static final String CLEAN = "clean";
    static final String PHOTOCOPY = "photocopy";
    static final String DESTROYED = "destroyed";
    static final String DEBRIS = "debris";

    /**
     * On-disk {@link Sample} cache.  Re-deriving the percentiles or trying a
     * different anchor is pure arithmetic over the measurements, but each fresh
     * pass costs two or three Tesseract runs per page.  Caching the raw values
     * makes that iteration free, and makes the run resumable after a crash.
     */
    private static final class Cache {
        private final Path path;
        private final List<String> populations;

        Cache(Path path, List<String> populations) {
            this.path = path;
            this.populations = populations;
        }

        boolean matches(List<String> names) {
            // The per-population files carry a suffix; the bare path never
            // exists, so it must not be part of the existence test.
            if (!names.equals(populations)) {
                return false;
            }
            return names.stream().allMatch(n -> Files.exists(pageFile(n)));
        }

        private Path pageFile(String pop) {
            return Path.of(path + "." + pop + ".bin");
        }

        /**
         * Reads every page of every population that is cached, or null if there is
         * no usable cache.  A short cache is returned as-is rather than rejected:
         * the caller resumes measuring at {@code result.get(pop).size()}, so a
         * run killed at page 100 does not throw away 100 pages of Tesseract work.
         */
        Map<String, List<Sample>> read(int pages) {
            if (!matches(populations)) {
                return null;
            }
            Map<String, List<Sample>> out = new LinkedHashMap<>();
            for (String pop : populations) {
                List<Sample> xs = new ArrayList<>();
                try (var in = new DataInputStream(
                        new BufferedInputStream(Files.newInputStream(pageFile(pop))))) {
                    for (int i = 0; i < pages; i++) {
                        int w = in.readInt();
                        int h = in.readInt();
                        int n = in.readInt();
                        List<TextBlock> boxes = new ArrayList<>(n);
                        for (int b = 0; b < n; b++) {
                            boxes.add(new TextBlock(in.readUTF(),
                                new java.awt.Rectangle(in.readInt(), in.readInt(),
                                        in.readInt(), in.readInt()),
                                in.readInt()));
                        }
                        var r = new MrcReadiness.Raw(in.readDouble(), in.readDouble(),
                                in.readDouble(), in.readDouble(), in.readDouble(),
                                in.readDouble(), in.readDouble(), in.readDouble());
                        xs.add(new Sample(r, boxes, w, h));
                    }
                } catch (EOFException e) {
                    // A partial final record is possible if a run was killed
                    // mid-write; anything already parsed stays, and the caller
                    // resumes from xs.size().
                    if (xs.size() < pages) {
                        System.out.printf("  cache %s: %d of %d pages cached%n",
                                pop, xs.size(), pages);
                    }
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
                out.put(pop, xs);
            }
            return out;
        }

        /** Appends one page to each population's file, creating them on first use. */
        void append(String pop, List<Sample> all) throws IOException {
            int from = countLines(pop);
            if (from > all.size()) {
                return;
            }
            try (var out = new DataOutputStream(
                    new BufferedOutputStream(Files.newOutputStream(
                            pageFile(pop), StandardOpenOption.CREATE,
                            StandardOpenOption.APPEND)))) {
                for (int i = from; i < all.size(); i++) {
                    Sample s = all.get(i);
                    out.writeInt(s.w());
                    out.writeInt(s.h());
                    out.writeInt(s.boxes().size());
                    for (TextBlock b : s.boxes()) {
                        out.writeUTF(b.getWord());
                        var r = b.getBbox();
                        out.writeInt(r.x);
                        out.writeInt(r.y);
                        out.writeInt(r.width);
                        out.writeInt(r.height);
                        out.writeInt((int) b.getConfidence());
                    }
                    MrcReadiness.Raw r = s.raw();
                    out.writeDouble(r.blurResidual());
                    out.writeDouble(r.contrast());
                    out.writeDouble(r.strokeWidthCv());
                    out.writeDouble(r.ambiguousFraction());
                    out.writeDouble(r.compsPerChar());
                    out.writeDouble(r.specklePerMpx());
                    out.writeDouble(r.noiseSigma());
                    out.writeDouble(r.tileSigma());
                }
            }
        }

        private int countLines(String pop) {
            Path f = pageFile(pop);
            if (!Files.exists(f)) {
                return 0;
            }
            try {
                long bytes = Files.size(f);
                // Not a line count: the per-page record length varies, so probe
                // by parsing.  Cheap enough at 125 pages and always correct.
                int n = 0;
                try (var in = new DataInputStream(
                        new BufferedInputStream(Files.newInputStream(f)))) {
                    while (true) {
                        try {
                            in.readInt();
                            in.readInt();
                            int k = in.readInt();
                            for (int b = 0; b < k; b++) {
                                in.readUTF();
                                in.readInt();
                                in.readInt();
                                in.readInt();
                                in.readInt();
                                in.readInt();
                            }
                            for (int d = 0; d < 8; d++) {
                                in.readDouble();
                            }
                            n++;
                        } catch (EOFException e) {
                            return n;
                        }
                    }
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    public static void main(String[] args) throws Exception {
        Path review = args.length > 0 ? Path.of(args[0]) : Path.of("build/real-base-pages.pdf");
        int limit = args.length > 1 ? Integer.parseInt(args[1]) : Integer.MAX_VALUE;
        boolean fresh = args.length > 2 && args[2].equals("--fresh");
        int workers = 3;
        if (args.length > 3 && args[3].equals("--workers")) {
            workers = Integer.parseInt(args[4]);
        }
        ScannerProfile mid = MID_PROFILE;
        ScannerProfile bad = badProfile();
        ScannerProfile debris = debrisProfile();

        List<String> pops = List.of(CLEAN, PHOTOCOPY, DESTROYED, DEBRIS);
        Map<String, List<Sample>> cache = fresh ? null
                : new Cache(Path.of("build/mrc-calibration-cache"), pops).read(125);
        System.out.println("  cache: " + (cache == null
                ? "MISS -> measuring all pages"
                : (cache.get("clean").size() >= limit
                        ? "hit, complete"
                        : "hit, resuming at page " + cache.get("clean").size())));

        System.out.println("Calibrating " + review + " at " + DPI + " DPI");
        System.out.println("  workers: " + workers);
        System.out.println("  photocopy anchor: " + mid);
        System.out.println("  destroyed anchor: " + bad);
        System.out.println("  debris anchor:    " + debris);

        // Always go through measureAll, even on a cache hit.  A partial cache
        // must be finished, not reported: taking the cache as-is would silently
        // derive percentiles from however many pages happened to finish before
        // an earlier run was interrupted.  measureAll returns immediately when
        // the cache already covers every page, so the fast path is unaffected.
        Map<String, List<Sample>> measured =
                measureAll(review, limit, workers, mid, bad, debris, pops, cache);

        for (Map.Entry<String, List<Sample>> e : measured.entrySet()) {
            System.out.printf("%n=== %s  (n=%d) ===%n", e.getKey(), e.getValue().size());
            report(e.getValue());
        }

        Derived d = Derived.from(measured);
        d.print();
        System.out.println(SEPARATION);
        d.printSeparation(measured);
        System.out.println();
        System.out.println(EMIT);
        d.emit();
    }

    /** One page's four samples, or the reason the page was dropped. */
    private record PageSamples(int page, List<Sample> samples, String error) {}

    private static PageSamples measurePage(BufferedImage base, int page,
            ScannerProfile mid, ScannerProfile bad, ScannerProfile debris) {
        // The same seed the corpus builder uses for this base page, so the
        // "clean" population is literally the image the corpus labels
        // tier=clean rather than a different noise realization.
        long pageSeed = SyntheticCorpus.basePageSeed(CORPUS_SEED, page);
        var p = new SyntheticCorpus.Page(base, List.<TextBlock>of());
        // All three degraded anchors share one seed, as in the serial loop: the
        // difference between them is the profile, not the noise realization.
        long seed = page * 31L + 11L;
        try {
            return new PageSamples(page, List.of(
                    sample(SyntheticCorpus.cleanScan(p, pageSeed)),
                    sample(ScannerSimulator.apply(base, mid, new java.util.Random(seed))),
                    sample(ScannerSimulator.apply(base, bad, new java.util.Random(seed))),
                    sample(ScannerSimulator.apply(base, debris, new java.util.Random(seed)))),
                    null);
        } catch (IOException e) {
            // A page Tesseract cannot finish on is dropped from the distribution
            // rather than aborting the run.  The labeler keeps the strict
            // behaviour, because there a missing OCR result must not silently
            // become a score.
            return new PageSamples(page, null, e.getMessage());
        }
    }

    private static PageSamples collect(Future<PageSamples> f) {
        try {
            return f.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("calibration interrupted", e);
        } catch (ExecutionException e) {
            // An unexpected failure inside measurePage must abort loudly rather
            // than be silently counted as an OCR timeout.
            throw new IllegalStateException("calibration worker failed", e.getCause());
        }
    }

    /** @return true when the page was dropped, so the caller can count it. */
    private static boolean absorb(PageSamples ps, Map<String, List<Sample>> out) {
        if (ps.error() != null) {
            System.out.printf("  p%d skipped: %s%n", ps.page() + 1, ps.error());
            return true;
        }
        List<String> keys = List.of(CLEAN, PHOTOCOPY, DESTROYED, DEBRIS);
        for (int i = 0; i < keys.size(); i++) {
            out.get(keys.get(i)).add(ps.samples().get(i));
        }
        return false;
    }

    private static Map<String, List<Sample>> measureAll(Path review, int limit, int workers,
            ScannerProfile mid, ScannerProfile bad, ScannerProfile debris,
            List<String> pops, Map<String, List<Sample>> resume) throws Exception {
        Cache cache = new Cache(Path.of("build/mrc-calibration-cache"), pops);
        Map<String, List<Sample>> out = new LinkedHashMap<>();
        int start = 0;
        for (String p : pops) {
            List<Sample> prior = resume == null ? null : resume.get(p);
            if (prior == null) {
                out.put(p, new ArrayList<>());
            } else {
                out.put(p, new ArrayList<>(prior));
                start = Math.min(start == 0 ? Integer.MAX_VALUE : start, prior.size());
            }
        }
        if (start == Integer.MAX_VALUE) {
            start = 0;
        }
        if (start > 0) {
            System.out.printf("  resuming from cached page %d%n", start);
        }
        int skipped = 0;

        try (PDDocument doc = Loader.loadPDF(review.toFile())) {
            PDFRenderer renderer = new PDFRenderer(doc);
            int n = Math.min(limit, doc.getNumberOfPages());
            int eff = Math.max(1, workers);

            // Render on this thread only: PDFBox' renderer is not thread-safe.
            // The per-page measure work is what fans out, since Tesseract (a
            // subprocess) dominates the wall clock.  Futures are drained in
            // submission order so the cached sample lists stay page-ordered and
            // a resumed run lines up with a fresh one.
            ExecutorService pool = Executors.newFixedThreadPool(eff);
            Deque<Future<PageSamples>> queue = new ArrayDeque<>();
            int window = eff * 2;
            int done = 0;
            try {
                for (int i = start; i < n; i++) {
                    final int page = i;
                    BufferedImage base = renderer.renderImageWithDPI(i, DPI);
                    queue.add(pool.submit(() -> measurePage(base, page, mid, bad, debris)));
                    // Bound the queue so a fast renderer cannot run ahead and
                    // hold every remaining page raster in memory.
                    while (queue.size() > window) {
                        if (absorb(collect(queue.poll()), out)) {
                            skipped++;
                        }
                        if (++done % 10 == 0) {
                            System.out.printf("  %d/%d pages measured (%d skipped)%n",
                                    done, n - start, skipped);
                            // Persist as we go.  A full pass is hours of OCR, and
                            // the resume path is only worth having if something
                            // reaches the cache before the run ends.
                            for (String p : pops) {
                                cache.append(p, out.get(p));
                            }
                        }
                    }
                }
                while (!queue.isEmpty()) {
                    if (absorb(collect(queue.poll()), out)) {
                        skipped++;
                    }
                    if (++done % 10 == 0) {
                        System.out.printf("  %d/%d pages measured (%d skipped)%n",
                                done, n - start, skipped);
                        for (String p : pops) {
                            cache.append(p, out.get(p));
                        }
                    }
                }
            } finally {
                pool.shutdown();
            }

            // Flush the final partial batch.  Without this the cache is short by
            // up to FLUSH pages, and the next run's read() then rejects it and
            // remeasures the whole corpus.
            for (String p : pops) {
                cache.append(p, out.get(p));
            }
        }

        if (skipped > 0) {
            System.out.printf("%nWARNING: %d pages dropped (OCR timeout); percentiles are "
                    + "over the remaining %d.%n", skipped, out.get("clean").size());
        }
        if (out.get("clean").size() < 20) {
            throw new IllegalStateException("only " + out.get("clean").size()
                    + " pages measured; too few to calibrate against");
        }
        return out;
    }

    private static Sample sample(BufferedImage scanned) throws IOException {
        JpegCodec.FinalImage jpeg = JpegCodec.prepare(scanned, SyntheticCorpus.LABEL_JPEG_QUALITY);
        BufferedImage img = jpeg.decoded();
        int w = img.getWidth();
        int h = img.getHeight();
        int[] gray = com.mrcpdf.util.ImageOps.toGrayscale(img);
        List<TextBlock> boxes = MrcReadiness.usable(
                ReadabilityOcr.recognize(img).getBlocks(), w, h);
        var mask = com.mrcpdf.quality.model.SauvolaMask.compute(gray, w, h);
        return new Sample(MrcReadiness.measure(gray, w, h, boxes, mask), boxes, w, h);
    }

    // ---------------------------------------------------------------- reporting

    private static final String[] FIELDS = {
        "blurResidual", "contrast", "strokeWidthCv", "ambiguousFraction",
        "compsPerChar", "specklePerMpx", "noiseSigma", "tileSigma"};

    private static void report(List<Sample> xs) {
        System.out.printf("  %-18s %s%n", "field", "  min      p10      p50      p90      max");
        for (String f : FIELDS) {
            double[] v = values(xs, f);
            double[] s = stats(v);
            System.out.printf("  %-18s %7.3f  %7.3f  %7.3f  %7.3f  %7.3f%n",
                    f, s[0], s[1], s[2], s[3], s[4]);
        }
    }

    private static double[] values(List<Sample> xs, String field) {
        double[] out = new double[xs.size()];
        for (int i = 0; i < xs.size(); i++) {
            MrcReadiness.Raw r = xs.get(i).raw();
            out[i] = switch (field) {
                case "blurResidual" -> r.blurResidual();
                case "contrast" -> r.contrast();
                case "strokeWidthCv" -> r.strokeWidthCv();
                case "ambiguousFraction" -> r.ambiguousFraction();
                case "compsPerChar" -> r.compsPerChar();
                case "specklePerMpx" -> r.specklePerMpx();
                case "noiseSigma" -> r.noiseSigma();
                case "tileSigma" -> r.tileSigma();
                default -> throw new IllegalArgumentException(field);
            };
        }
        return out;
    }

    private static double[] stats(double[] v) {
        double[] s = v.clone();
        Arrays.sort(s);
        return new double[] {
            s[0], pct(s, 0.10), pct(s, 0.50), pct(s, 0.90), s[s.length - 1]};
    }

    private static double pct(double[] sorted, double p) {
        if (sorted.length == 0) {
            return Double.NaN;
        }
        double idx = p * (sorted.length - 1);
        int lo = (int) Math.floor(idx);
        int hi = (int) Math.ceil(idx);
        return sorted[lo] + (sorted[hi] - sorted[lo]) * (idx - lo);
    }

    /** Percentile over a population that has not been sorted yet. */
    private static double pctOf(List<Sample> xs, String field, double p) {
        double[] v = values(xs, field);
        Arrays.sort(v);
        return pct(v, p);
    }

    // ----------------------------------------------------------------- constants

    private static final String SEPARATION = """
            === separation check ===
            A metric only earns a band if the clean and destroyed populations
            separate in the direction the mapping assumes.  Where they do not, a
            band would be a cliff in an empty gap rather than a real gradient.""";

    private static final String EMIT = "=== paste into MrcRefs ===";

    /** Percentile endpoints derived from the measured populations. */
    private record Derived(
            double blurGood, double blurBad,
            double contrastGood, double contrastBad,
            double swcvGood, double swcvBad,
            double marginGood, double marginBad,
            double fragCenter, double fragSpread,
            double speckleGood, double speckleBad,
            double noiseGood, double noiseBad,
            double uniformGood, double uniformBad) {

        /**
         * Derives the endpoints from the measured populations, looked up by name.
         *
         * <p>This takes the population map rather than four positional
         * {@code List<Sample>} arguments on purpose.  Every one of them has the
         * same type, so a swapped pair compiles silently — and it did: the report
         * labelled the destroyed anchor's score as the photocopy's, which made
         * the tier ordering look non-monotonic when the populations were fine.
         * A map lookup cannot be transposed.
         */
        static Derived from(Map<String, List<Sample>> measured) {
            List<Sample> clean = measured.get(CLEAN);
            List<Sample> mid = measured.get(PHOTOCOPY);
            List<Sample> bad = measured.get(DESTROYED);
            List<Sample> debris = measured.get(DEBRIS);
            double[] cs = stats(values(clean, "compsPerChar"));
            double[] bs = stats(values(bad, "compsPerChar"));
            // Floor the spread at half the clean p50..p90 gap, so a bad anchor
            // that happens to sit close to the clean centre cannot crush the
            // worst tenth of clean pages to zero.
            double spread = Math.max(Math.abs(bs[3] - cs[2]), 0.5 * (cs[3] - cs[2]));
            // sBg describes how dirty a page's *paper* is, and no single anchor
            // is the dirtyest paper.  The destroyed anchor is a blur-dominated
            // draw: its noiseSigma tops out at 1.09 while the mid-scale
            // photocopy reaches 2.03, so taking the bad end from `bad` alone
            // published a "worthless background" that an ordinary photocopy
            // overshot.  Since sBg is the minimum-free dimension, a page past
            // both endpoints collapses the composite to a hard zero, and 120 of
            // the 500 pilot pages did exactly that.  So the bad end is the p99
            // over every degraded anchor, which is the population the endpoint
            // actually has to bound.
            List<Sample> degraded = degraded(measured);
            return new Derived(
                    stats(values(clean, "blurResidual"))[1],
                    stats(values(bad, "blurResidual"))[3],
                    stats(values(clean, "contrast"))[1],
                    stats(values(bad, "contrast"))[3],
                    stats(values(clean, "strokeWidthCv"))[1],
                    stats(values(bad, "strokeWidthCv"))[3],
                    stats(values(clean, "ambiguousFraction"))[1],
                    stats(values(bad, "ambiguousFraction"))[3],
                    cs[2], spread,
                    // Speckle is anchored on debris, not on the destroyed page:
                    // see debrisProfile().
                    stats(values(clean, "specklePerMpx"))[1],
                    stats(values(debris, "specklePerMpx"))[3],
                    // sBg: these two replaced hand-written NOISE_REF/UNIFORM_REF
                    // absolutes that sat an order of magnitude above anything the
                    // corpus actually produces, so the dimension scored ~0.97 for
                    // every page and 20% of the weight carried no signal.
                    stats(values(clean, "noiseSigma"))[1],
                    pctOf(degraded, "noiseSigma", 0.99),
                    stats(values(clean, "tileSigma"))[1],
                    pctOf(degraded, "tileSigma", 0.99));
        }

        /** true when the two populations separate the way the mapping assumes. */
        boolean[] separates(Map<String, List<Sample>> measured) {
            List<Sample> clean = measured.get(CLEAN);
            List<Sample> bad = measured.get(DESTROYED);
            List<Sample> debris = measured.get(DEBRIS);
            List<Sample> degraded = degraded(measured);
            return new boolean[] {
                higher(clean, bad, "blurResidual"),
                higher(clean, bad, "contrast"),
                lower(clean, bad, "strokeWidthCv"),
                lower(clean, bad, "ambiguousFraction"),
                true, // two-sided, checked below by spread vs the clean gap
                lower(clean, debris, "specklePerMpx"),
                // Checked against the union the endpoint is derived from.
                lower(clean, degraded, "noiseSigma"),
                lower(clean, degraded, "tileSigma")};
        }

        private static boolean higher(List<Sample> clean, List<Sample> bad, String f) {
            return stats(values(clean, f))[1] > stats(values(bad, f))[3];
        }

        private static boolean lower(List<Sample> clean, List<Sample> bad, String f) {
            return stats(values(bad, f))[3] > stats(values(clean, f))[1];
        }

        private static final String[] METRIC = {
            "mBlur    (higher better)", "mContrast(higher better)",
            "mSwcv    (lower better) ", "mMargin  (lower better) ",
            "mFrag    (two-sided)     ", "mSpeckle (lower better) ",
            "mNoise   (lower better) ", "mUniform (lower better) "};

        void printSeparation(Map<String, List<Sample>> measured) {
            List<Sample> clean = measured.get(CLEAN);
            boolean[] ok = separates(measured);
            double[] cs = stats(values(clean, "compsPerChar"));
            ok[4] = fragSpread > 0.5 * (cs[3] - cs[2]);
            // mFrag must also actually peak on the clean population: a spread so
            // wide the Gaussian never approaches 1 would zero the term everywhere.
            double[] cleanFrac = values(clean, "compsPerChar");
            double[] sortedFrac = cleanFrac.clone();
            Arrays.sort(sortedFrac);
            double atCleanP50 = Math.exp(-Math.pow(
                    (pct(sortedFrac, 0.50) - fragCenter) / fragSpread, 2));
            ok[4] &= atCleanP50 > 0.5;
            for (int i = 0; i < ok.length; i++) {
                System.out.printf("  %s  %s%n", METRIC[i],
                        ok[i] ? "separates" : "SEPARATION FAILS");
            }
        System.out.printf("%n  median R_mrc with these constants:  clean %.3f  "
                        + "photocopy %.3f  destroyed %.3f  debris %.3f%n",
                    medianScore(clean), medianScore(measured.get(PHOTOCOPY)),
                    medianScore(measured.get(DESTROYED)),
                    medianScore(measured.get(DEBRIS)));
        System.out.printf("  (label thresholds: good >= %.2f, bad < %.2f)%n",
                MrcReadiness.GOOD_ABOVE, MrcReadiness.BAD_BELOW);

        System.out.printf("%n%s%n", DIMENSIONS);
        // Iterate the population map so each row prints the name it was measured
        // under, rather than a label chosen by argument position.
        for (Map.Entry<String, List<Sample>> e : measured.entrySet()) {
            dimensions(e.getValue(), String.format("%-11s", e.getKey()));
        }
    }

    /** Every population except the clean baseline: what a bad endpoint must bound. */
    private static List<Sample> degraded(Map<String, List<Sample>> measured) {
        List<Sample> out = new ArrayList<>();
        for (Map.Entry<String, List<Sample>> e : measured.entrySet()) {
            if (CLEAN.equals(e.getKey())) continue;
            out.addAll(e.getValue());
        }
        return out;
    }

    private static final String DIMENSIONS = """
            === median sub-score dimensions ===
            A dimension that does not move between populations is not
            discriminating, and the one with the lowest median is the
            bottleneck that pins the composite.

            These are medians, so a heavy tail (speckle is one) cannot hide
            behind a mean.""";

    private static void dimensions(List<Sample> xs, String tag) {
        int k = 20;
        double[][] cols = new double[k][xs.size()];
        for (int j = 0; j < xs.size(); j++) {
            MrcReadiness.Breakdown d = xs.get(j).score();
            double[] v = {d.mBlur(), d.mContrast(), d.mSwcv(), d.sVisual(),
                d.mMargin(), d.mFrag(), d.mSpeckle(), d.sMask(),
                d.mConfMean(), d.mConfTail(), d.mDispersion(), d.mLex(), d.sOcr(),
                d.mNoise(), d.mUniform(), d.sBg(),
                d.rGeom(), d.kappa(), d.bottleneck(), d.score()};
            for (int i = 0; i < k; i++) {
                cols[i][j] = v[i];
            }
        }
        double[] b = new double[k];
        for (int i = 0; i < k; i++) {
            Arrays.sort(cols[i]);
            b[i] = pct(cols[i], 0.50);
        }
        String[] names = {"mBlur", "mContrast", "mSwcv", "sVisual",
            "mMargin", "mFrag", "mSpeckle", "sMask",
            "mConfMean", "mConfTail", "mDispersion", "mLex", "sOcr",
            "mNoise", "mUniform", "sBg",
            "rGeom", "kappa", "bottleneck", "R_mrc"};
        StringBuilder sb = new StringBuilder("  ").append(tag).append(' ');
        for (int i = 0; i < b.length; i++) {
            sb.append(String.format("%s=%.2f ", names[i], b[i]));
            if (i == 3 || i == 7 || i == 12 || i == 15 || i == 18) {
                sb.append('\n').append(" ".repeat(12));
            }
        }
        System.out.println(sb);
        }

        private double medianScore(List<Sample> xs) {
            double[] v = new double[xs.size()];
            for (int i = 0; i < xs.size(); i++) {
                v[i] = xs.get(i).score().score();
            }
            Arrays.sort(v);
            return pct(v, 0.50);
        }

        void print() {
            System.out.printf("%n  frag center %.3f  spread %.3f%n", fragCenter, fragSpread);
        }

        void emit() {
            System.out.println("    // measured over the pages that finished; a base page that hit");
            System.out.println("    // the OCR timeout is dropped, so this is usually < limit");
            System.out.println("    // (see notes/mrc-calibration.md)");
            f("BLUR_GOOD", blurGood);
            f("BLUR_BAD", blurBad);
            f("CONTRAST_GOOD", contrastGood);
            f("CONTRAST_BAD", contrastBad);
            f("SWCV_GOOD", swcvGood);
            f("SWCV_BAD", swcvBad);
            f("MARGIN_AMBIGUOUS_GOOD", marginGood);
            f("MARGIN_AMBIGUOUS_BAD", marginBad);
            f("FRAG_CENTER", fragCenter);
            f("FRAG_SPREAD", fragSpread);
            f("SPECKLE_GOOD", speckleGood);
            f("SPECKLE_BAD", speckleBad);
            f("NOISE_GOOD", noiseGood);
            f("NOISE_BAD", noiseBad);
            f("UNIFORM_GOOD", uniformGood);
            f("UNIFORM_BAD", uniformBad);
        }

        private static void f(String name, double v) {
            System.out.printf("    static final double %-24s = %.6f;%n", name, v);
        }
    }
}
