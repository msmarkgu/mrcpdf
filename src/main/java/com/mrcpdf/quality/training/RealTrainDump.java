package com.mrcpdf.quality.training;

import com.mrcpdf.model.TextBlock;
import com.mrcpdf.quality.model.FeatureExtractor;
import com.mrcpdf.quality.model.MrcReadiness;
import com.mrcpdf.util.ImageOps;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.rendering.PDFRenderer;

import java.awt.Color;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Turns the 125-page clean review corpus into a 1250-page labeled training set:
 * 1 clean + N-1 stochastic scan variants per base page.
 *
 * <p>The label is a word-multiset F1 against the source page
 * ({@link ReadabilityOcr#measureWithWords}) — Tesseract reading the rendered
 * page, compared word-by-word to the text the generator actually printed.
 * 1.0 means every reference word was read and nothing was invented.
 *
 * <p><b>The label was {@code R_mrc} until the steering work, and that was
 * wrong.</b>  {@code R_mrc} ({@link MrcReadiness#compute}) is a reference-free
 * composite of the page's own pixels, and it was a good label only because it
 * happened to agree with the audit: correlation +0.90 on the corpus built
 * before steering.  Steering exposed the disagreement.  Driving
 * {@code S_visual} to 0.2 makes a soft, blurry, perfectly legible page, so
 * over the steered corpus the two fell to +0.47, {@code S_visual}'s own
 * correlation with the audit collapsed to +0.10, and 57 of the 93 rows labelled
 * unreadable were readable.  A composite that can report "bad" about a page
 * anyone can read is not a label.  It is still recorded, as
 * {@code rMrcProxy}, so the proxy can be studied against the truth rather than
 * quietly assumed.
 *
 * <p>Using the audit as the label is not circular: the reference text is
 * available here, at corpus-build time, because the corpus is built from
 * sources whose text is known.  A model trained on it still has to predict
 * readability from image features alone.  The clean page is measured too, so
 * no score in the output is fabricated.
 *
 * <p>Ground truth comes from re-reading the <em>source</em> PDF page named in the
 * review index via {@link RealSrcDump#extractBlocks}, so reference words are
 * exact rather than OCR'd.  Word boxes live in source-raster pixels; they are
 * mapped through the fit-scale affine that {@code embedVariant} applied, so the
 * 3 conditional text features see the same coordinate frame as the raster.
 *
 * <p>Note the caveat carried into the evaluation: {@link ScannerSimulator}
 * geometrically warps a page when {@code curl > 0 || skewDeg > 0}, which
 * invalidates pristine-anchored glyph boxes on those variants.  That is why the
 * 11-feature image-only baseline is reported alongside the 14-feature model.
 */
public final class RealTrainDump {

    private static final double PAGE_W = 612;
    private static final double PAGE_H = 792;
    private static final int DPI = SyntheticCorpus.RENDER_DPI;

    /** good / middle / bad, index-ordered. */
    static final String[] CLASS_NAMES = {"good", "middle", "bad"};


    private RealTrainDump() {
    }

    /** One row of the review index. */
    private record IndexRow(int outPage, String baseFile, int basePage) {
    }

    /**
     * One emitted corpus page.
     *
     * <p>{@code score} is the label, the ground-truth word F1; {@code rMrcProxy}
     * is the reference-free composite, carried alongside as a diagnostic rather
     * than used as the label.  {@code auditOk} is false where the page had too
     * few reference words for the audit to mean anything, and such a page is
     * labelled 0 rather than being trusted.
     * {@code mrcBreakdown} keeps the sub-score summary so a label can always be
     * explained after the fact.  {@code steer} is null for the clean page and for
     * every page of a {@code --no-steer} run.
     */
    private static final class Row14 implements ScoreSampler.SteeringRow {
        final int basePage;
        final int variant;
        final String variantName;
        final String tier;
        final double score;
        final int cls;
        final int binaryLabel;
        final double[] features;
        final double rMrcProxy;
        final boolean auditOk;
        final String mrcBreakdown;
        final SyntheticCorpus.Steer steer;
        boolean textOk;

        Row14(int basePage, int variant, String variantName, String tier, double score,
              int cls, int binaryLabel, double[] features, boolean textOk,
              double rMrcProxy, boolean auditOk, String mrcBreakdown,
              SyntheticCorpus.Steer steer) {
            this.basePage = basePage;
            this.variant = variant;
            this.variantName = variantName;
            this.tier = tier;
            this.score = score;
            this.cls = cls;
            this.binaryLabel = binaryLabel;
            this.features = features;
            this.textOk = textOk;
            this.rMrcProxy = rMrcProxy;
            this.auditOk = auditOk;
            this.mrcBreakdown = mrcBreakdown;
            this.steer = steer;
        }

        @Override
        public double steer() {
            return steer == null ? Double.NaN : steer.value();
        }

        @Override
        public double target() {
            return steer == null ? Double.NaN : steer.target();
        }

        @Override
        public boolean onTarget() {
            return steer != null && steer.onTarget();
        }

        /** Satisfies {@link ScoreSampler.SteeringRow}; the field is already indexed. */
        @Override
        public int cls() {
            return cls;
        }
    }

    /**
     * One-line sub-score summary, for the map CSV's {@code mrc_dims} column.
     *
     * <p>Logs the raw measurements as well as the mapped sub-scores.  The
     * sub-scores are a lossy function of the raw values through
     * {@code MrcRefs}' bands, so a map holding only the sub-scores cannot be
     * re-scored when a band changes -- and a band did change, which is how the
     * corrected sBg endpoints had to be cross-checked against the calibration
     * cache rather than against the 500 pilot pages.  With the raw values
     * present, any future re-reference is exact arithmetic over the map.
     */
    private static String formatBreakdown(MrcReadiness.Breakdown b, MrcReadiness.Raw raw) {
        return String.format(Locale.ROOT,
                "vis=%.3f mask=%.3f ocr=%.3f bg=%.3f kappa=%.3f words=%d "
                        + "blur=%.4f contrast=%.5f swcv=%.5f margin=%.5f "
                        + "comps=%.4f speckle=%.3f noise=%.5f tile=%.5f",
                b.sVisual(), b.sMask(), b.sOcr(), b.sBg(), b.kappa(), b.glyphBoxes(),
                raw.blurResidual(), raw.contrast(), raw.strokeWidthCv(),
                raw.ambiguousFraction(), raw.compsPerChar(), raw.specklePerMpx(),
                raw.noiseSigma(), raw.tileSigma());
    }

    /** 14 features, flagging whether the conditional 3 were actually extractable. */
    private record Feat14(double[] v, boolean textOk) {
    }

    public static void main(String[] args) throws Exception {
        Path review = Path.of("build/real-base-pages.pdf");
        Path index = Path.of("build/real-base-pages.csv");
        Path bases = Path.of("build/real-bases");
        Path out = Path.of("build/real-train-src.pdf");
        Path featuresCsv = Path.of("build/real-train-features.csv");
        Path mapCsv = Path.of("build/real-train-map.csv");
        Path shardDir = Path.of("build/real-train-shards");
        int pages = 10;
        // Kept in step with MrcCalibration.CORPUS_SEED so the calibration's clean
        // population is the same image this builder labels tier=clean.
        long seed = 42;
        int workers = 3;
        // The label is the audited word F1, and the class cuts decide what
        // "good" means.  Left unset they default to the terciles of the steering
        // marginal, which is what this corpus used to do and is retained only as
        // the fallback.  That made the classes balanced by construction -- the
        // sampler fills [lo,hi] uniformly, so cutting at the terciles splits the
        // steered rows into thirds, against a pre-sampling baseline mix of
        // 23/14/63 that was not a useful thing to train on.
        //
        // But a tercile is a rank, not a verdict, and reading `good` off the page
        // invited exactly the wrong comparison.  Under the terciles good spanned
        // 0.669..1.000, reached down onto pages that had visibly failed their
        // steering target, and 127 of its 301 rows sat below 0.85.  The
        // consequence was a page with audit 0.827 -- 17% of its words unreadable
        // -- printing `class=good` beside `R_mrc=0.265`.  Both numbers were
        // correct; the name was not.
        //
        // Absolute cuts make the class mean something on its own.  `bad` sits at
        // SeverityScore.LABEL_CUTOFF so that class=bad and binaryLabel=1 name the
        // same pages: a page the map calls unreadable must not also be told to
        // keep MRC.  Middle is consequently the widest class, and that is the
        // trade.
        double goodAbove = Double.NaN, badBelow = Double.NaN;
        boolean resume = true;
        boolean cleanShards = false;
        boolean steer = true;
        double steerLo = ScoreSampler.DEFAULT_LO;
        double steerHi = ScoreSampler.DEFAULT_HI;
        double steerTolerance = ScoreSampler.DEFAULT_TOLERANCE;
        int steerMaxAttempts = ScoreSampler.DEFAULT_MAX_ATTEMPTS;
        int steerCalibSteps = ScoreCalibration.DEFAULT_STEPS;
        int steerCalibPages = DEFAULT_CALIB_PAGES;
        String steerDims = "visual,mask,bg";

        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            switch (a) {
                case "--review" -> review = Path.of(args[++i]);
                case "--index" -> index = Path.of(args[++i]);
                case "--bases" -> bases = Path.of(args[++i]);
                case "--out" -> out = Path.of(args[++i]);
                case "--features" -> featuresCsv = Path.of(args[++i]);
                case "--map" -> mapCsv = Path.of(args[++i]);
                case "--shard-dir" -> shardDir = Path.of(args[++i]);
                case "--pages" -> pages = Integer.parseInt(args[++i]);
                case "--seed" -> seed = Long.parseLong(args[++i]);
                case "--workers" -> workers = Integer.parseInt(args[++i]);
                case "--good-above" -> goodAbove = Double.parseDouble(args[++i]);
                case "--bad-below" -> badBelow = Double.parseDouble(args[++i]);
                case "--no-resume" -> resume = false;
                case "--clean-shards" -> cleanShards = true;
                case "--steer" -> steer = true;
                case "--no-steer" -> steer = false;
                case "--steer-lo" -> steerLo = Double.parseDouble(args[++i]);
                case "--steer-hi" -> steerHi = Double.parseDouble(args[++i]);
                case "--steer-tolerance" -> steerTolerance = Double.parseDouble(args[++i]);
                case "--steer-max-attempts" -> steerMaxAttempts = Integer.parseInt(args[++i]);
                case "--steer-calib-steps" -> steerCalibSteps = Integer.parseInt(args[++i]);
                case "--steer-calib-pages" -> steerCalibPages = Integer.parseInt(args[++i]);
                case "--steer-dims" -> steerDims = args[++i];
                default -> throw new IllegalArgumentException("Unknown flag: " + a);
            }
        }
        if (pages < 2) throw new IllegalArgumentException("--pages must be >= 2 (1 clean + degraded)");
        if (goodAbove <= badBelow) {
            throw new IllegalArgumentException("--good-above must exceed --bad-below");
        }
        if (steerLo < 0 || steerHi > 1 || steerLo >= steerHi) {
            throw new IllegalArgumentException(
                    "--steer-lo/--steer-hi must be an increasing range inside [0,1], got "
                            + steerLo + ".." + steerHi);
        }
        ScoreSampler.Dim[] steerDimList = parseDims(steerDims);
        // Both or neither. Passing only --good-above used to leave badBelow NaN,
        // and `goodAbove <= NaN` is false, so the check above did not catch it and
        // classOf fell through to `middle` for every row -- a silently balanced-
        // looking corpus that had no bad pages at all.
        boolean haveGood = !Double.isNaN(goodAbove);
        boolean haveBad = !Double.isNaN(badBelow);
        if (haveGood != haveBad) {
            throw new IllegalArgumentException(
                    "--good-above and --bad-below must be given together");
        }
        boolean absoluteCuts = haveGood;
        if (!absoluteCuts) {
            double third = (steerHi - steerLo) / 3.0;
            badBelow = steerLo + third;
            goodAbove = steerLo + 2.0 * third;
        }

        ScoreSampler.Steering steering = new ScoreSampler.Steering(steer, steerLo, steerHi,
                steerTolerance, steerMaxAttempts, steerDimList);

        List<IndexRow> indexRows = readIndex(index);
        int nBase = indexRows.size();
        if (nBase == 0) throw new IllegalArgumentException("Empty index: " + index);
        requireFile(review);
        if (!Files.isDirectory(bases)) throw new IOException("Missing base dir: " + bases);

        int effWorkers = Math.max(1, Math.min(workers, nBase));
        System.out.printf("RealTrainDump: %d base pages x %d variants = %d pages, workers=%d%n",
                nBase, pages, nBase * pages, effWorkers);
        System.out.printf("  label = audited word F1; class cuts good>=%.4f / bad<%.4f (%s)%n",
                goodAbove, badBelow,
                absoluteCuts ? "absolute" : "terciles of the steering marginal");
        System.out.printf(steer
                        ? "  steering ON: target uniform [%.2f,%.2f], dims %s, tolerance %.2f, "
                                + "max %d attempts, %d calibration steps on %d reference pages%n"
                        : "  steering OFF: tier-centre draws, corpus is the pre-sampling baseline%n",
                steerLo, steerHi, steerDims, steerTolerance, steerMaxAttempts, steerCalibSteps,
                steerCalibPages);

        Files.createDirectories(shardDir);
        Files.createDirectories(out.toAbsolutePath().getParent());
        Files.createDirectories(featuresCsv.toAbsolutePath().getParent());

        List<List<IndexRow>> shards = partition(indexRows, effWorkers);
        long t0 = System.nanoTime();
        final int nPages = pages;
        final long runSeed = seed;
        final double goodCut = goodAbove;
        final double badCut = badBelow;
        final Path reviewIn = review;
        final Path basesIn = bases;
        final Path shardIn = shardDir;
        final boolean resumeIn = resume;
        // One Steering shared by reference across every shard, so the response
        // curves are probed once per run instead of once per shard. A probe costs
        // 3 * steps OCR passes, which is the same order as a shard's whole page
        // budget; probing per shard would cost a third of the build for a
        // slightly better first guess.
        final ScoreSampler.Steering steeringIn = steering;
        final int calibSteps = steerCalibSteps;
        // Before the workers start: every worker shares one calibration, and it is
        // cheaper to spend 3 * probes * steps renders once here than to let each
        // worker discover a different reference page.
        primeCalibration(allIndexRows(shards), reviewIn, basesIn, seed, steering, calibSteps,
                steerCalibPages);

        ExecutorService pool = Executors.newFixedThreadPool(effWorkers);
        List<Future<List<Row14>>> futures = new ArrayList<>();
        for (int s = 0; s < effWorkers; s++) {
            List<IndexRow> shard = shards.get(s);
            final int shardId = s;
            futures.add(pool.submit(() -> {
                if (resumeIn) {
                    List<Row14> cached = loadCompletedShard(shardIn, shardId, shard, nPages);
                    if (cached != null) {
                        System.out.printf("  shard %02d: reusing %d checkpointed rows%n",
                                shardId, cached.size());
                        return cached;
                    }
                }
                return processShard(shard, shardId, reviewIn, basesIn, shardIn,
                        nPages, runSeed, goodCut, badCut, steeringIn, calibSteps);
            }));
        }
        pool.shutdown();

        List<Row14> all = new ArrayList<>();
        for (Future<List<Row14>> f : futures) {
            try {
                all.addAll(f.get());
            } catch (Exception e) {
                pool.shutdownNow();
                throw (e instanceof RuntimeException re) ? re : new RuntimeException(e);
            }
        }
        all.sort(Comparator.comparingInt((Row14 r) -> r.basePage).thenComparingInt(r -> r.variant));

        int imputed = imputeMissingText(all);
        writeFeatureCsv(featuresCsv, all);
        writeMapCsv(mapCsv, all);
        mergeShards(shardDir, effWorkers, out, cleanShards);

        if (steering.enabled()) {
            // Filter rather than map to Sample: a Sample holds its rendered page,
            // so keeping the list alive to the end of the run would pin every
            // raster the build had already written to disk.
            List<Row14> steeredRows = new ArrayList<>();
            for (Row14 r : all) {
                if (r.steer != null) steeredRows.add(r);
            }
            // The dimension ranges first, because they explain the histogram. A
            // dimension that tops out below the upper end of the marginal cannot
            // be asked for the top bins, and without this line that is invisible.
            ScoreCalibration.Calibration cal = steering.calibration();
            if (cal != null) {
                cal.report(steerLo, steerHi);
            }
            long attempts = 0;
            int misses = 0;
            for (Row14 r : steeredRows) {
                attempts += r.steer.attempts();
                if (!r.steer.onTarget()) misses++;
            }
            System.out.printf(Locale.ROOT, "  %d steered pages, %.1f renders each (%d total)%n",
                    steeredRows.size(),
                    steeredRows.isEmpty() ? 0.0 : (double) attempts / steeredRows.size(),
                    attempts);
            // Only require the bins the generator can actually fill. The realism
            // texture caps how clean a degraded page measures, so the top of the
            // marginal is out of reach on all three dimensions at once; demanding
            // it would make this check unsatisfiable rather than informative.
            // Health diagnostic for the demoted R_mrc proxy: how well it still
            // tracks the ground-truth label, over the whole corpus. Measured on
            // all rows, not the steered ones, because the sampler pins a steered
            // row's label to its target by construction -- within that subset the
            // number says nothing about the proxy. This is reported, not gated:
            // the label no longer depends on the proxy, so the proxy disagreeing
            // with the audit is no longer a defect in the corpus. It was when
            // R_mrc WAS the label (drifting to +0.47 and taking the whole corpus
            // with it); now it is just a fact about a reference-free score that
            // is no longer trusted. Only a genuinely broken value fails.
            double proxyAgreement = agreement(all);
            System.out.printf(Locale.ROOT,
                    "  corr(label, rMrcProxy) over all rows: %+.3f "
                            + "(diagnostic; label is the audit, proxy is not load-bearing)%n",
                    proxyAgreement);
            if (!Double.isFinite(proxyAgreement)) {
                throw new IllegalStateException(
                        "corr(label, rMrcProxy) is not finite: the proxy column is missing, "
                                + "constant, or malformed. see " + mapCsv);
            }

            double[] fill = cal != null ? cal.fillableRange() : new double[]{steerLo, steerHi};
            ScoreCalibration.Histogram hist =
                    ScoreCalibration.histogram(steeredRows, 10, steerLo, steerHi, fill[0], fill[1]);
            hist.print();
            if (!hist.ok()) {
                // A corpus whose steering marginal is wrong is not a corpus anyone
                // should train on, and the map CSV's per-row detail is right there.
                // Warning and exiting 0 would let a bad run be regenerated over.
                throw new IllegalStateException("steered corpus failed histogram QA:\n  "
                        + String.join("\n  ", hist.problems())
                        + "\n  see " + mapCsv + " for the per-row detail");
            }
        }

        printSummary(all, imputed, out, featuresCsv, mapCsv,
                (System.nanoTime() - t0) / 1e9);
    }

    /** Parses {@code visual,mask,bg} into the dimensions a run may steer by. */
    static ScoreSampler.Dim[] parseDims(String spec) {
        List<ScoreSampler.Dim> dims = new ArrayList<>();
        for (String part : spec.split(",")) {
            String s = part.trim().toLowerCase(Locale.ROOT);
            if (s.isEmpty()) continue;
            switch (s) {
                case "visual" -> dims.add(ScoreSampler.Dim.VISUAL);
                case "mask" -> dims.add(ScoreSampler.Dim.MASK);
                case "bg" -> dims.add(ScoreSampler.Dim.BG);
                default -> throw new IllegalArgumentException(
                        "Unknown --steer-dims entry: " + part + " (want visual, mask, bg)");
            }
        }
        if (dims.isEmpty()) throw new IllegalArgumentException("--steer-dims named no dimensions");
        return dims.toArray(new ScoreSampler.Dim[0]);
    }

    /** Every indexed row, shard order, for sampling references across the corpus. */
    private static List<IndexRow> allIndexRows(List<List<IndexRow>> shards) {
        List<IndexRow> all = new ArrayList<>();
        for (List<IndexRow> shard : shards) all.addAll(shard);
        return all;
    }

    /**
     * Measures a variant, retrying a transient OCR failure.
     *
     * <p>This is the site the 500-row run actually died on, 90 minutes in, which
     * discarded four workers' completed pages along with the one that failed.
     * Measuring the same pixels again is idempotent, so unlike the steered path
     * there is nothing to redraw -- a timeout here is Tesseract declining a
     * particular raster, and it frequently accepts the identical one on a retry
     * once the machine is less loaded. Bounded, and the cause propagates after.
     */
    private static ReadabilityOcr.Result measureWithRetry(BufferedImage img, List<TextBlock> blocks,
                                                          String label, int variant)
            throws IOException {
        IOException last = null;
        for (int attempt = 1; attempt <= OCR_ATTEMPTS; attempt++) {
            try {
                return ReadabilityOcr.measureWithWords(img, blocks);
            } catch (IOException e) {
                last = e;
                System.err.printf(Locale.ROOT,
                        "warning: OCR failed on %s variant %d, attempt %d/%d (%s: %s)%n",
                        label, variant, attempt, OCR_ATTEMPTS,
                        e.getClass().getSimpleName(), e.getMessage());
            }
        }
        throw last;
    }

    /**
     * Renders one indexed page and pairs it with its ground-truth words.
     *
     * <p>Shared by the shard worker and the calibration priming pass so both agree
     * on what a page is; a second copy of this would be a second definition of the
     * page the probes measured.
     */
    private static SyntheticCorpus.Page loadPage(IndexRow ir, PDDocument src, PDDocument reviewDoc,
                                                  long seed) throws IOException {
        int spIndex = ir.basePage() - 1;
        if (spIndex < 0 || spIndex >= src.getNumberOfPages()) {
            throw new IOException("basePage " + ir.basePage() + " out of range for "
                    + ir.baseFile() + " (" + src.getNumberOfPages() + " pages)");
        }

        PDRectangle mb = src.getPage(spIndex).getMediaBox();
        List<TextBlock> truth = RealSrcDump.extractBlocks(
                src, spIndex, mb.getWidth(), mb.getHeight(), seed);

        BufferedImage img = new PDFRenderer(reviewDoc).renderImageWithDPI(ir.outPage() - 1, DPI);
        List<TextBlock> blocks = mapBlocksToReview(truth, mb, img.getWidth(), img.getHeight());
        if (blocks.isEmpty()) {
            System.out.printf("  !! p%d: no usable ground-truth words; page will be "
                    + "imputed in the text columns%n", ir.outPage());
        }
        return new SyntheticCorpus.Page(img, blocks, "review page " + ir.outPage());
    }

    /**
     * Builds the run's steering calibration from pages spread across the corpus.
     *
     * <p>Probing a single page makes the curves that page's response, and the rest
     * of the corpus is then steered by a curve measured on a page that is not like
     * it -- which costs attempts and, where a target sits near that page's own
     * ceiling, leaves the marginal unreachable. A handful of references merged is
     * the same cost order as the one probe it replaces.
     *
     * <p>The probes run concurrently. Each is a full OCR pass per severity step, so
     * serially this is minutes of the run spent before any page is built, and the
     * references are independent.
     */
    private static void primeCalibration(List<IndexRow> all, Path review, Path bases, long seed,
                                         ScoreSampler.Steering steering, int steps, int probes)
            throws IOException {
        if (!steering.enabled() || probes <= 0) return;
        if (all.size() <= probes) probes = all.size();

        // Evenly spaced, so the sample spans the corpus rather than clustering in
        // whichever files sort first.
        List<IndexRow> refs = new ArrayList<>();
        for (int k = 0; k < probes; k++) {
            refs.add(all.get((int) Math.round((double) k * (all.size() - 1) / (probes - 1))));
        }

        ExecutorService pool = Executors.newFixedThreadPool(refs.size());
        List<Future<ScoreCalibration.Calibration>> futures = new ArrayList<>();
        for (IndexRow ir : refs) {
            futures.add(pool.submit(() -> {
                try (PDDocument reviewDoc = Loader.loadPDF(review.toFile());
                     PDDocument src = Loader.loadPDF(bases.resolve(ir.baseFile()).toFile())) {
                    SyntheticCorpus.Page page = loadPage(ir, src, reviewDoc, seed);
                    return SyntheticCorpus.probeCalibration(page,
                            SyntheticCorpus.basePageSeed(seed, ir.outPage()), steps);
                }
            }));
        }
        pool.shutdown();

        List<ScoreCalibration.Calibration> parts = new ArrayList<>();
        for (int k = 0; k < futures.size(); k++) {
            try {
                parts.add(futures.get(k).get());
                System.out.printf("  calibration probe %d/%d: review page %d%n",
                        k + 1, futures.size(), refs.get(k).outPage());
            } catch (Exception e) {
                pool.shutdownNow();
                // Better to refuse to start than to run 4 hours on curves from a
                // partial sample that may be missing half the corpus's range.
                throw new IOException("steering calibration probe failed for review page "
                        + refs.get(k).outPage(), e);
            }
        }
        steering.calibrateFrom(parts);
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Shard worker
    // ──────────────────────────────────────────────────────────────────────────

    private static List<Row14> processShard(List<IndexRow> shard, int shardId, Path review,
                                            Path bases, Path shardDir, int pages, long seed,
                                            double goodAbove, double badBelow,
                                            ScoreSampler.Steering steering, int calibSteps)
            throws IOException {
        List<Row14> rows = new ArrayList<>();
        Path shardPdf = shardDir.resolve(String.format(Locale.ROOT, "shard-%02d.pdf", shardId));
        System.out.printf("  shard %02d: %d base pages -> %s%n", shardId, shard.size(), shardPdf);

        try (PDDocument reviewDoc = Loader.loadPDF(review.toFile());
             PDDocument shardDoc = new PDDocument()) {
            PDFRenderer renderer = new PDFRenderer(reviewDoc);
            PDDocument src = null;
            String openFile = null;
            int done = 0;
            long t0 = System.nanoTime();
            try {
                for (IndexRow ir : shard) {
                    if (!ir.baseFile().equals(openFile)) {
                        if (src != null) {
                            src.close();
                            src = null;
                        }
                        Path sp = bases.resolve(ir.baseFile());
                        if (!Files.isRegularFile(sp)) {
                            throw new IOException("Missing source PDF for " + ir.baseFile() + ": " + sp);
                        }
                        src = Loader.loadPDF(sp.toFile());
                        openFile = ir.baseFile();
                    }
                    SyntheticCorpus.Page page = loadPage(ir, src, reviewDoc, seed);
                    long pageSeed = SyntheticCorpus.basePageSeed(seed, ir.outPage());

                    List<SyntheticCorpus.Variant> sheets = new ArrayList<>();
                    // Measure the clean page like every other variant instead of
                    // asserting 1.0 -- an unmeasured score would not be a label.
                    sheets.add(new SyntheticCorpus.Variant("clean", "clean",
                            SyntheticCorpus.cleanScan(page, pageSeed), -1.0, 0));
                    sheets.addAll(SyntheticCorpus.buildScannedPages(page, pageSeed, pages - 1,
                            steering, calibSteps));
                    if (sheets.size() != pages) {
                        throw new IOException("expected " + pages + " variants for base page "
                                + ir.outPage() + ", got " + sheets.size());
                    }

                    for (int v = 0; v < sheets.size(); v++) {
                        SyntheticCorpus.Variant var = sheets.get(v);
                        // Measure and extract on the pixels the page will actually
                        // present.  Encoding once and keeping the stream alongside
                        // its decoded form means the label, the features and the
                        // embedded image are the same bytes -- round-tripping here
                        // and re-encoding in embedPage() would apply JPEG twice.
                        //
                        // A steered variant arrives already measured, because the
                        // sampler had to OCR it to steer on it; re-measuring here
                        // would double the cost of the most expensive step in the
                        // build, and could disagree with the steer value the corpus
                        // was built around.
                        JpegCodec.FinalImage jpeg;
                        BufferedImage finalImg;
                        ReadabilityOcr.Result ocr;
                        MrcReadiness.Raw mrcRaw;
                        MrcReadiness.Breakdown mrc;
                        if (var.steered()) {
                            ScoreSampler.Rendered r = var.rendered();
                            jpeg = r.jpeg();
                            finalImg = jpeg.decoded();
                            ocr = r.ocr();
                            mrcRaw = r.raw();
                            mrc = r.mrc();
                        } else {
                            jpeg = JpegCodec.prepare(
                                    var.image(), SyntheticCorpus.LABEL_JPEG_QUALITY);
                            finalImg = jpeg.decoded();
                            ocr = measureWithRetry(finalImg, page.blocks(), page.label(), v);
                            // measure() then score() rather than compute(), so the
                            // raw measurements survive for the map; the pair is
                            // exactly what compute() does internally.
                            int pw = finalImg.getWidth(), ph = finalImg.getHeight();
                            int[] pg = ImageOps.toGrayscale(finalImg);
                            List<TextBlock> usable = MrcReadiness.usable(ocr.getBlocks(), pw, ph);
                            mrcRaw = MrcReadiness.measure(pg, pw, ph, usable,
                                    com.mrcpdf.quality.model.SauvolaMask.compute(pg, pw, ph));
                            mrc = MrcReadiness.score(mrcRaw, usable, pw, ph);
                        }

                        // The label is the audit. R_mrc is still recorded, as the
                        // proxy column it is, but it is no longer what the classes
                        // are cut from: over the steered corpus its agreement with
                        // the audit fell to +0.47, from +0.90 before steering, and
                        // 57 of 93 rows it called unreadable were readable.
                        double auditScore = ocr.getAuditScore();
                        boolean auditOk = ReferenceAnchor.isUsable(page.blocks())
                                && Double.isFinite(auditScore);
                        double score = auditOk ? auditScore : 0.0;
                        int cls = classOf(score, goodAbove, badBelow);
                        double mrcProxy = mrc.score();
                        Feat14 f = features14(finalImg, ocr.getBlocks());
                        embedPage(shardDoc, jpeg.jpeg(), score, mrc, cls,
                                ir.outPage(), var.tier());
                        rows.add(new Row14(ir.outPage(), v, var.name(), var.tier(), score, cls,
                                // The binary form of the *label*, not of the
                                // composite. Deriving it from rMrcProxy left a
                                // column named binaryLabel disagreeing with the
                                // label column on the same row, which is the kind
                                // of thing that gets trusted by whoever reads the
                                // CSV next.
                                SeverityScore.label(score), f.v(), f.textOk(),
                                mrcProxy, auditOk, formatBreakdown(mrc, mrcRaw), var.steer()));
                    }
                    done++;
                    // Every base page, not every 10th: a shard holds only 8-9
                    // base pages, so a "done % 10" gate never fired and a
                    // 500-page build sat silent for its whole duration.
                    System.out.printf("  shard %02d: %d/%d base pages (%.1f min)%n",
                            shardId, done, shard.size(), (System.nanoTime() - t0) / 6e10);
                }
                shardDoc.save(shardPdf.toFile());
                // Checkpoint the rows next to the shard PDF.  A corpus build takes
                // hours, so a later shard must not be able to discard completed work.
                writeShardRows(shardDir.resolve(
                        String.format(Locale.ROOT, "shard-%02d.csv", shardId)), rows);
            } finally {
                if (src != null) src.close();
            }
        }
        return rows;
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Geometry
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Maps word boxes from source-raster pixels into review-raster pixels.
     *
     * <p>{@code embedVariant} fit-scales the 300-DPI source raster into a
     * 612x792 container at {@code scale}, centred at {@code (dx, dy)}.  The
     * container is then re-rendered at {@link #DPI}, so the net mapping is a
     * uniform scale plus a translate:
     * {@code x_r = x_s*scale + dx*k} and
     * {@code y_r = y_s*scale + (792 - dy - drawH)*k}, with {@code k = DPI/72}.
     */
    private static List<TextBlock> mapBlocksToReview(List<TextBlock> truth, PDRectangle mb,
                                                     int dstW, int dstH) {
        double srcWpt = mb.getWidth();
        double srcHpt = mb.getHeight();
        double scale = Math.min(PAGE_W / srcWpt, PAGE_H / srcHpt);
        double drawW = srcWpt * scale;
        double drawH = srcHpt * scale;
        double dx = (PAGE_W - drawW) / 2.0;
        double dy = (PAGE_H - drawH) / 2.0;
        double k = DPI / 72.0;
        double ox = dx * k;
        double oy = (PAGE_H - dy - drawH) * k;

        List<TextBlock> out = new ArrayList<>(truth.size());
        for (TextBlock b : truth) {
            Rectangle r = b.getBbox();
            if (r == null) {
                out.add(b);
                continue;
            }
            int nx = (int) Math.round(r.x * scale + ox);
            int ny = (int) Math.round(r.y * scale + oy);
            int nw = Math.max(1, (int) Math.round(r.width * scale));
            int nh = Math.max(1, (int) Math.round(r.height * scale));
            nx = Math.max(0, Math.min(nx, dstW - 1));
            ny = Math.max(0, Math.min(ny, dstH - 1));
            nw = Math.max(1, Math.min(nw, dstW - nx));
            nh = Math.max(1, Math.min(nh, dstH - ny));
            out.add(new TextBlock(b.getWord(), new Rectangle(nx, ny, nw, nh),
                    b.getConfidence(), b.getCharPositions()));
        }
        return out;
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Features
    // ──────────────────────────────────────────────────────────────────────────

    private static Feat14 features14(BufferedImage img, List<TextBlock> blocks) {
        int[] gray = ImageOps.toGrayscale(img);
        int w = img.getWidth();
        int h = img.getHeight();
        double[] image = FeatureExtractor.extractImage(gray, w, h, DPI);
        double[] text = null;
        if (!blocks.isEmpty()) {
            float[] mag = FeatureExtractor.gradientMagnitudes(gray, w, h);
            text = FeatureExtractor.extractText(gray, mag, w, h, blocks);
        }
        if (text == null) {
            double[] v = new double[image.length + FeatureExtractor.TEXT_FEATURE_NAMES.length];
            System.arraycopy(image, 0, v, 0, image.length);
            return new Feat14(v, false);
        }
        double[] v = new double[image.length + text.length];
        System.arraycopy(image, 0, v, 0, image.length);
        System.arraycopy(text, 0, v, image.length, text.length);
        return new Feat14(v, true);
    }

    /**
     * Fills the 3 conditional text columns of any row where extraction failed,
     * using the per-column median of the rows that succeeded, so the 14-feature
     * matrix stays rectangular.  Returns the number of rows touched.
     */
    private static int imputeMissingText(List<Row14> rows) {
        int width = rows.get(0).features.length;
        for (int c = 0; c < width; c++) {
            List<Double> vals = new ArrayList<>();
            for (Row14 r : rows) {
                if (r.textOk && Double.isFinite(r.features[c])) vals.add(r.features[c]);
            }
            if (vals.isEmpty()) continue;
            vals.sort(Comparator.naturalOrder());
            double med = vals.get(vals.size() / 2);
            for (Row14 r : rows) {
                if (!r.textOk || !Double.isFinite(r.features[c])) r.features[c] = med;
            }
        }
        int n = 0;
        for (Row14 r : rows) {
            if (!r.textOk) n++;
        }
        return n;
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Output
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Emits one labeled page. {@code jpeg} is the exact stream the label and
     * features were measured on, so the rendered page and the labeled raster
     * agree bit for bit.
     *
     * <p>The banner takes {@code mrc} rather than loose doubles so the composite
     * and the sub-scores it was computed from cannot be drawn from different
     * breakdowns.
     */
    private static void embedPage(PDDocument doc, byte[] jpeg, double score,
                                  MrcReadiness.Breakdown mrc, int cls,
                                  int basePage, String tier) throws IOException {
        PDPage pd = new PDPage(new PDRectangle((float) PAGE_W, (float) PAGE_H));
        doc.addPage(pd);
        try (PDPageContentStream cs = new PDPageContentStream(doc, pd)) {
            JpegCodec.embedBytes(doc, cs, jpeg, 0, 0, PAGE_W, PAGE_H);
            drawLabelBanner(cs, score, mrc, cls, basePage, tier);
        }
    }

    /**
     * The banner text: every quantity the label decision rests on.
     *
     * <p>This used to print a single value under the key {@code R_MRC}.  That
     * was correct when the label was the R_mrc composite, and silently wrong
     * after the corpus was relabelled onto the audited word F1 (47cc4b5): the
     * value changed, the format string did not.  The banner therefore names all
     * four metrics it prints, and prints both the composite and the sub-scores
     * behind it, so a reader cannot mistake one for another.
     *
     * <p>{@code kappa} is printed because it is the whole point of the composite:
     * {@code score = rGeom * bottleneck(kappa)}, where {@code kappa} is the
     * minimum of the four sub-scores.  A page can be perfectly legible
     * (high {@code audit}, high {@code S_ocr}) and still score low on R_mrc
     * because {@code S_visual} collapsed -- MRC's 1-bit foreground mask breaks
     * thin strokes on soft, low-contrast scans.  Both failure modes matter and
     * they call for opposite responses, so the banner shows all of them.
     */
    static String bannerText(double audit, MrcReadiness.Breakdown mrc, int cls) {
        return String.format(Locale.ROOT,
                "audit=%.3f  R_mrc=%.3f  k=%.3f  S_vis=%.3f  S_mask=%.3f  "
                        + "S_ocr=%.3f  S_bg=%.3f  class=%s",
                audit, mrc.score(), mrc.kappa(), mrc.sVisual(), mrc.sMask(),
                mrc.sOcr(), mrc.sBg(), CLASS_NAMES[cls]);
    }

    /** Top band: the measured metrics and their good/middle/bad class, plus provenance. */
    private static void drawLabelBanner(PDPageContentStream cs, double score,
                                        MrcReadiness.Breakdown mrc, int cls,
                                        int basePage, String tier) throws IOException {
        String text = bannerText(score, mrc, cls);
        PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
        float fontSize = 8;
        float tw = font.getStringWidth(text) / 1000f * fontSize;
        float x = (float) ((PAGE_W - tw) / 2.0);
        float baseline = (float) (PAGE_H - 9);
        cs.setNonStrokingColor(Color.WHITE);
        cs.addRect(x - 4, baseline - 3, tw + 8, 11.5f);
        cs.fill();
        cs.setNonStrokingColor(Color.BLACK);
        cs.beginText();
        cs.setFont(font, fontSize);
        cs.newLineAtOffset(x, baseline);
        cs.showText(text);
        cs.endText();

        String meta = "p" + basePage + "  " + tier;
        PDType1Font mf = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
        float ms = 6.5f;
        float mw = mf.getStringWidth(meta) / 1000f * ms;
        float mx = (float) (PAGE_W - mw - 4);
        float mb = (float) (PAGE_H - 20);
        cs.setNonStrokingColor(Color.WHITE);
        cs.addRect(mx - 2, mb - 2.5f, mw + 4, 9);
        cs.fill();
        cs.setNonStrokingColor(new Color(70, 70, 70));
        cs.beginText();
        cs.setFont(mf, ms);
        cs.newLineAtOffset(mx, mb);
        cs.showText(meta);
        cs.endText();
    }

    private static void writeFeatureCsv(Path path, List<Row14> rows) throws IOException {
        List<String> names = featureNames();
        StringBuilder sb = new StringBuilder();
        sb.append("doc,label");
        for (String n : names) sb.append(',').append(n);
        sb.append('\n');
        for (Row14 r : rows) {
            sb.append("p").append(r.basePage).append(',').append(r.cls);
            for (double v : r.features) {
                if (!Double.isFinite(v)) sb.append(",0.000000");
                else sb.append(String.format(Locale.ROOT, ",%.6f", v));
            }
            sb.append('\n');
        }
        Files.writeString(path, sb.toString());
    }

    /**
     * {@code labelWordF1} is the label: a word-level F1 against the assembled
     * text, not a raw error rate, and 1.0 means Tesseract read every reference
     * word and invented nothing.  {@code rMrcProxy} is the reference-free
     * composite, kept next to it so the two can be compared on every corpus
     * instead of one being assumed on the strength of a single build.
     */
    private static void writeMapCsv(Path path, List<Row14> rows) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("page,basePage,variant,variantName,tier,labelWordF1,class,binaryLabel,textOk")
                .append(",rMrcProxy,auditOk,mrc_dims")
                .append(",steerDim,steerDrawn,steerTarget,steerValue,steerAttempts,onTarget\n");
        int page = 0;
        for (Row14 r : rows) {
            sb.append(++page).append(',').append(r.basePage).append(',').append(r.variant)
                    .append(',').append(r.variantName).append(',').append(r.tier)
                    .append(',').append(String.format(Locale.ROOT, "%.6f", r.score))
                    .append(',').append(CLASS_NAMES[r.cls])
                    .append(',').append(r.binaryLabel)
                    .append(',').append(r.textOk).append(',');
            if (Double.isFinite(r.rMrcProxy)) {
                sb.append(String.format(Locale.ROOT, "%.6f", r.rMrcProxy));
            }
            // An empty field, not a 0: a page with too few reference words has no
            // readback at all, and 0.0 would read as "completely unreadable".
            sb.append(',').append(r.auditOk)
                    .append(',').append(r.mrcBreakdown);
            appendSteer(sb, r.steer);
            sb.append('\n');
        }
        Files.writeString(path, sb.toString());
    }

    /**
     * The five steering columns.
     *
     * <p>Empty for the clean page and for every row of a {@code --no-steer} run,
     * for the same reason the audit columns are: absent is not the same as zero,
     * and a steer value of 0.0 is a real measurement (a page destroyed past
     * recognition) that must not be confused with "not steered".
     */
    private static void appendSteer(StringBuilder sb, SyntheticCorpus.Steer s) {
        if (s == null) {
            sb.append(",,,,,,");
            return;
        }
        sb.append(',').append(s.dim().slug())
                .append(',').append(s.drawn())
                .append(',').append(String.format(Locale.ROOT, "%.6f", s.target()))
                .append(',').append(String.format(Locale.ROOT, "%.6f", s.value()))
                .append(',').append(s.attempts())
                .append(',').append(s.onTarget());
    }

    /**
     * Checkpoint header, including a schema tag.  Labels changed from OCR
     * {@code 1-WER} to reference-free {@code R_mrc}, so a checkpoint written by
     * an older build is not resumable: its scores are a different measurement and
     * would be silently mixed with new ones.  The tag is checked on load.
     */
    private static final String SHARD_HEADER =
            "schema,variant-set,basePage,variant,variantName,tier,score,class,"
                    + "binaryLabel,textOk,rMrcProxy,auditOk,mrc_dims,"
                    + "steerDim,steerDrawn,steerTarget,steerValue,steerAttempts,onTarget";

    /**
     * Must match {@link #SHARD_HEADER} for a checkpoint to be reusable.
     *
     * <p>Bumped to {@code rmrc-2} when the steering columns were added, and to
     * {@code rmrc-4} for the audit-as-label columns, and
     * {@code rmrc-3} for {@code steerDrawn}, which records the dimension a page
     * was <em>drawn</em> on as distinct from the one it converged on.
     *
     * <p>The rows
     * are read positionally below, so an old checkpoint would be decoded with the
     * new columns shifted one place left -- {@code steerDim} landing in the feature
     * vector, and every label after it garbage.  Worse, it would not *look* wrong:
     * the reader validates the row width, and the old rows have the old width, so
     * a schema check that compared only the feature count would let them through.
     * Hence an explicit tag that the reader compares before touching anything.
     */
    private static final String SHARD_SCHEMA = "rmrc-4";

    /** Pearson correlation between the label and the R_mrc proxy. */
    static double agreement(List<Row14> rows) {
        double[] label = new double[rows.size()];
        double[] proxy = new double[rows.size()];
        for (int i = 0; i < rows.size(); i++) {
            label[i] = rows.get(i).score;
            proxy[i] = rows.get(i).rMrcProxy;
        }
        return correlation(label, proxy);
    }

    static double correlation(double[] a, double[] b) {
        if (a.length != b.length || a.length < 3) {
            return Double.NaN;
        }
        int n = a.length;
        double sx = 0, sy = 0;
        for (int i = 0; i < n; i++) {
            sx += a[i];
            sy += b[i];
        }
        double mx = sx / n, my = sy / n;
        double num = 0, dx = 0, dy = 0;
        for (int i = 0; i < n; i++) {
            double u = a[i] - mx, v = b[i] - my;
            num += u * v;
            dx += u * u;
            dy += v * v;
        }
        return (dx <= 0 || dy <= 0) ? Double.NaN : num / Math.sqrt(dx * dy);
    }

    /**
     * Reference pages probed to build the run's steering calibration.
     *
     * <p>Three is enough to stop one page defining the curves for the corpus and
     * costs 3x a single probe on a run that renders hundreds of pages.
     */
    private static final int DEFAULT_CALIB_PAGES = 3;

    /** Retries for a transient OCR failure on a single variant. */
    private static final int OCR_ATTEMPTS = 3;

    private static void writeShardRows(Path path, List<Row14> rows) throws IOException {
        StringBuilder sb = new StringBuilder();
        // The audit columns, the sub-score breakdown and the steering record are
        // all part of the checkpoint: a resumed run must not silently relabel pages
        // using audit values it no longer has, nor lose the evidence for whether a
        // page met the target it was built to meet.
        sb.append(SHARD_HEADER);
        for (String n : featureNames()) sb.append(',').append(n);
        sb.append('\n');
        for (Row14 r : rows) {
            sb.append(SHARD_SCHEMA).append(',').append(r.variantName.indexOf('+') < 0
                    ? r.variantName : r.variantName.substring(0, r.variantName.indexOf('+')))
                    .append(',').append(r.basePage).append(',').append(r.variant).append(',')
                    .append(r.variantName)
                    .append(',').append(r.tier).append(',')
                    .append(String.format(Locale.ROOT, "%.6f", r.score)).append(',')
                    .append(CLASS_NAMES[r.cls]).append(',').append(r.binaryLabel).append(',')
                    .append(r.textOk).append(',');
            if (Double.isFinite(r.rMrcProxy)) {
                sb.append(String.format(Locale.ROOT, "%.6f", r.rMrcProxy));
            }
            sb.append(',').append(r.auditOk).append(',').append(r.mrcBreakdown);
            appendSteer(sb, r.steer);
            for (double d : r.features) {
                if (!Double.isFinite(d)) sb.append(",0.000000");
                else sb.append(String.format(Locale.ROOT, ",%.6f", d));
            }
            sb.append('\n');
        }
        Files.writeString(path, sb.toString());
    }

    private static List<Row14> readShardRows(Path csv) throws IOException {
        List<String> lines = Files.readAllLines(csv);
        if (lines.isEmpty()) throw new IOException("Empty checkpoint " + csv);
        if (!SHARD_SCHEMA.equals(lines.get(0).split(",", -1)[0])) {
            throw new IOException("Checkpoint " + csv + " has schema "
                    + lines.get(0).split(",", -1)[0] + ", expected " + SHARD_SCHEMA
                    + ". Its scores came from a different label definition and cannot be"
                    + " resumed; delete the shard directory or pass --no-resume.");
        }
        int nFeat = featureNames().size();
        int meta = SHARD_HEADER.split(",", -1).length;
        List<String> classes = Arrays.asList(CLASS_NAMES);
        List<Row14> rows = new ArrayList<>();
        for (int i = 1; i < lines.size(); i++) {
            if (lines.get(i).isBlank()) continue;
            String[] c = lines.get(i).split(",", -1);
            if (c.length < meta + nFeat) {
                throw new IOException("Malformed checkpoint row in " + csv + ": " + lines.get(i));
            }
            double[] v = new double[nFeat];
            for (int k = 0; k < nFeat; k++) v[k] = Double.parseDouble(c[meta + k]);
            String audit = c[10].isEmpty() ? "" : c[10];
            rows.add(new Row14(Integer.parseInt(c[2]), Integer.parseInt(c[3]), c[4], c[5],
                    Double.parseDouble(c[6]), classes.indexOf(c[7]), Integer.parseInt(c[8]),
                    v, Boolean.parseBoolean(c[9]),
                    audit.isEmpty() ? Double.NaN : Double.parseDouble(audit),
                    Boolean.parseBoolean(c[11]), c[12], readSteer(c)));
        }
        return rows;
    }

    /**
     * Rebuilds the steering record from the checkpoint's trailing columns.
     *
     * <p>All five are empty for a clean page and for a {@code --no-steer} run, and
     * the whole group has to be present together: a half-populated record would
     * mean a corrupted checkpoint, and reading the target as 0.0 would put the
     * page in the histogram as a page that missed a target of zero.
     */
    private static SyntheticCorpus.Steer readSteer(String[] c) {
        boolean empty = true;
        for (int i = 13; i <= 18; i++) if (!c[i].isEmpty()) empty = false;
        if (empty) return null;
        if (c[13].isEmpty()) {
            throw new IllegalArgumentException(
                    "checkpoint has steering values but no steerDim: " + String.join(",", c[13], c[14], c[15]));
        }
        ScoreSampler.Dim d = switch (c[13]) {
            case "visual" -> ScoreSampler.Dim.VISUAL;
            case "mask" -> ScoreSampler.Dim.MASK;
            case "bg" -> ScoreSampler.Dim.BG;
            default -> throw new IllegalArgumentException("Unknown steerDim in checkpoint: " + c[13]);
        };
        return new SyntheticCorpus.Steer(d, c[14], Double.parseDouble(c[15]),
                Double.parseDouble(c[16]), Integer.parseInt(c[17]), Boolean.parseBoolean(c[18]));
    }

    /**
     * Returns the checkpointed rows for a shard, or {@code null} when the shard
     * has to be rebuilt.  A shard counts as done only when both its PDF and its
     * row CSV exist, the row count matches, and every base page is present.
     */
    private static List<Row14> loadCompletedShard(Path shardDir, int shardId,
                                                  List<IndexRow> shard, int pages) {
        Path pdf = shardDir.resolve(String.format(Locale.ROOT, "shard-%02d.pdf", shardId));
        Path csv = shardDir.resolve(String.format(Locale.ROOT, "shard-%02d.csv", shardId));
        if (!Files.isRegularFile(pdf) || !Files.isRegularFile(csv)) return null;
        List<Row14> rows;
        try {
            rows = readShardRows(csv);
        } catch (IOException | RuntimeException e) {
            System.out.printf("  shard %02d: unreadable checkpoint (%s); rebuilding%n",
                    shardId, e.getMessage());
            return null;
        }
        if (rows.size() != shard.size() * pages) {
            System.out.printf("  shard %02d: checkpoint has %d rows, expected %d; rebuilding%n",
                    shardId, rows.size(), shard.size() * pages);
            return null;
        }
        for (IndexRow ir : shard) {
            boolean found = false;
            for (Row14 r : rows) if (r.basePage == ir.outPage()) { found = true; break; }
            if (!found) {
                System.out.printf("  shard %02d: checkpoint missing base page p%d; rebuilding%n",
                        shardId, ir.outPage());
                return null;
            }
        }
        return rows;
    }

    private static void mergeShards(Path shardDir, int workers, Path out, boolean clean)
            throws IOException {
        List<Path> present = new ArrayList<>();
        for (int s = 0; s < workers; s++) {
            Path p = shardDir.resolve(String.format(Locale.ROOT, "shard-%02d.pdf", s));
            if (Files.isRegularFile(p)) present.add(p);
        }
        if (present.size() != workers) {
            // Never emit a silently short corpus: a missing shard means the merged
            // PDF would have fewer pages than the feature CSV claims.
            throw new IOException("Expected " + workers + " shard PDFs in " + shardDir
                    + " but found " + present.size() + " " + present);
        }
        List<PDDocument> open = new ArrayList<>();
        try (PDDocument dest = new PDDocument()) {
            try {
                for (Path p : present) {
                    PDDocument shardDoc = Loader.loadPDF(p.toFile());
                    open.add(shardDoc);
                    // PDFBox 3 exposes only importPage, a *shallow* copy that shares
                    // the source's image COS objects.  So the shard documents must
                    // stay open until dest.save() has walked and written them.
                    for (PDPage page : shardDoc.getPages()) {
                        dest.importPage(page);
                    }
                }
                dest.save(out.toFile());
            } finally {
                for (PDDocument d : open) d.close();
            }
        }
        if (clean) {
            for (Path p : present) Files.deleteIfExists(p);
        }
    }

    private static void printSummary(List<Row14> rows, int imputed, Path out, Path featuresCsv,
                                     Path mapCsv, double secs) {
        int[] counts = new int[3];
        for (Row14 r : rows) counts[r.cls]++;
        MapStats.byTier(rows);
        System.out.println();
        System.out.printf("pages            %d%n", rows.size());
        System.out.printf("class good       %d (%.1f%%)%n", counts[0], pct(counts[0], rows.size()));
        System.out.printf("class middle     %d (%.1f%%)%n", counts[1], pct(counts[1], rows.size()));
        System.out.printf("class bad        %d (%.1f%%)%n", counts[2], pct(counts[2], rows.size()));
        System.out.printf("text imputed     %d rows (%.1f%%)%n", imputed, pct(imputed, rows.size()));
        System.out.printf("out              %s (%.1f MB)%n", out, sizeMb(out));
        System.out.printf("features         %s%n", featuresCsv);
        System.out.printf("map              %s%n", mapCsv);
        System.out.printf("elapsed          %.1f min%n", secs / 60.0);
    }

    private static double pct(int n, int total) {
        return total == 0 ? 0.0 : 100.0 * n / total;
    }

    private static double sizeMb(Path p) {
        try {
            return Files.size(p) / 1e6;
        } catch (IOException e) {
            return 0.0;
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Helpers
    // ──────────────────────────────────────────────────────────────────────────

    static List<String> featureNames() {
        List<String> names = new ArrayList<>();
        for (String n : FeatureExtractor.IMAGE_FEATURE_NAMES) names.add(n);
        for (String n : FeatureExtractor.TEXT_FEATURE_NAMES) names.add(n);
        return names;
    }

    static int classOf(double score, double goodAbove, double badBelow) {
        if (score >= goodAbove) return 0;
        if (score >= badBelow) return 1;
        return 2;
    }

    private static void requireFile(Path p) throws IOException {
        if (!Files.isRegularFile(p)) throw new IOException("Missing input: " + p);
    }

    private static List<List<IndexRow>> partition(List<IndexRow> rows, int parts) {
        List<List<IndexRow>> out = new ArrayList<>();
        for (int i = 0; i < parts; i++) out.add(new ArrayList<>());
        for (int i = 0; i < rows.size(); i++) out.get(i % parts).add(rows.get(i));
        return out;
    }

    private static List<IndexRow> readIndex(Path path) throws IOException {
        List<String> lines = Files.readAllLines(path);
        if (lines.size() < 2) throw new IOException("Index has no data rows: " + path);
        String[] head = lines.get(0).split(",");
        int outIdx = col(head, "outPage");
        int fileIdx = col(head, "baseFile");
        int pageIdx = col(head, "basePage");
        List<IndexRow> out = new ArrayList<>();
        for (int i = 1; i < lines.size(); i++) {
            String line = lines.get(i).trim();
            if (line.isEmpty()) continue;
            String[] c = line.split(",");
            out.add(new IndexRow(Integer.parseInt(c[outIdx].trim()),
                    c[fileIdx].trim(),
                    Integer.parseInt(c[pageIdx].trim())));
        }
        return out;
    }

    private static int col(String[] head, String name) {
        for (int i = 0; i < head.length; i++) {
            if (head[i].trim().equals(name)) return i;
        }
        throw new IllegalArgumentException("Index is missing column '" + name + "'");
    }

    /** Per-tier mean readability, printed for eyeballing coverage. */
    static final class MapStats {
        static void byTier(List<Row14> rows) {
            java.util.Map<String, double[]> acc = new java.util.TreeMap<>();
            for (Row14 r : rows) {
                double[] a = acc.computeIfAbsent(r.tier, k -> new double[2]);
                a[0] += r.score;
                a[1]++;
            }
            System.out.println("tier means");
            for (var e : acc.entrySet()) {
                System.out.printf("  %-6s n=%-5d readability=%.3f%n",
                        e.getKey(), (int) e.getValue()[1], e.getValue()[0] / e.getValue()[1]);
            }
        }
    }
}
