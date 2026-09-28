package com.mrcpdf.quality.training;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.File;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.JPEGFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.rendering.PDFRenderer;

import com.mrcpdf.model.TextBlock;
import com.mrcpdf.quality.model.FeatureExtractor;
import com.mrcpdf.quality.model.MrcReadiness;
import com.mrcpdf.quality.model.QualityModel;
import com.mrcpdf.util.ImageOps;

/**
 * Deterministic synthetic corpus for training and validating the quality-gate
 * model, engineered to live in the same feature space as the pipeline's real
 * inputs.
 *
 * <p>Production pages pass through: a Java-AWT raster of text (per-page 612×792
 * grayscale scan, JPEG q~0.95) embedded in a PDF, then re-rendered at 300 DPI
 * by PDFRenderer.  The corpus reproduces that exact chain, so clean corpus
 * pages share the anti-aliasing / upscale / JPEG statistics of real scans, and
 * the trained gate transfers without re-training.
 *
 * <p>Each document renders its own page (deterministic per seed) with a
 * realistic layout (title, headings, indented paragraphs).  A clean row plus
 * {@code degradedPerDoc} degraded rows are derived per document: every degraded
 * page draws a {@link ScannerProfile} from one {@link ScannerTier} archetype
 * and runs it through {@link ScannerSimulator}, then its quality score is
 * <em>measured</em> by OCR against the known printed text
 * ({@link ReadabilityOcr}: score = 1 - word error rate, 1 = pristine, 0 =
 * unreadable) with a binary label (score below {@link SeverityScore#LABEL_CUTOFF}
 * ⇒ 1, MRC must not touch).  Effect severities from {@link SeverityScore} only
 * drive the tier draw and variant naming — not the label.
 * Every variant of one source page shares its {@code doc} id so the train/val
 * split stays document-aware.
 *
 * <p>All pages of a document use the same RNG seed stream.  Rendering and
 * degradation are byte-reproducible for a given {@code seed}; the measured
 * OCR score and label are deterministic for a given Tesseract build + {@code eng}
 * language data (pinned, see notes/ocr-readable-score.md).  The scored CVS /
 * JSON are the replay artifacts.  The final gate labels still come from human
 * reading of the MRC output via the annotation workflow in docs/synthetic-labels.md.
 */
public final class SyntheticCorpus {

    /** Pipeline render DPI — must match production for feature transfer. */
    public static final int RENDER_DPI = 300;

    /** JPEG quality of the embedded "scan" like TestPdfGenerator.reads. */
    static final float SOURCE_JPEG_QUALITY = 0.95f;

    /**
     * Per-page scan compression quality, held constant across every variant.
     *
     * <p>This used to track the measured readability score
     * ({@code min(0.95, 0.45 + 0.50*score)}), which conflated the artifact with
     * the label.  Two consequences made that untenable:
     * <ul>
     *   <li>The label was measured on the <em>pre-embedding</em> raster while the
     *       features were read from the <em>post-JPEG</em> one, so no page's
     *       features described the pixels its label was computed from.  A label
     *       of 0.5 came from a page embedded at q=0.70 and a label of 0.8 from a
     *       page embedded at q=0.85, so the two tiers differed in blockiness as
     *       well as in legibility.</li>
     *   <li>It handed the corpus an artifact-based shortcut: a model could
     *       separate the tiers by JPEG block strength alone without reading a
     *       single glyph, and the scores were not comparable across pages.</li>
     * </ul>
     *
     * <p>Degradation must instead come from the degradation pipeline alone.
     * {@code 0.92} is near-lossless for text at 300 DPI: it costs a little
     * sharpness, which is a realistic scanner property common to all pages, and
     * keeps blockiness small enough that it cannot stand in for legibility.
     * Labels are now computed on the decoded JPEG stream
     * ({@link JpegCodec.FinalImage}), so features and label describe the same
     * bytes the page ships.
     */
    static final float LABEL_JPEG_QUALITY = 0.92f;

    /** Degraded pages per document when the caller doesn't specify a count. */
    public static final int DEFAULT_DEGRADED_PER_DOC = 19;

    /** Source raster page size in points (72 DPI units). */
    private static final int SRC_W = 612;
    private static final int SRC_H = 792;
    private static final int MARGIN = 50;
    private static final int LINE_HEIGHT = 17;

    /** Typical printed body text is 10-12pt. */
    private static final int FONT_SIZE = 11;

    private SyntheticCorpus() {
    }

    /**
     * Writes the labeled corpus CSV for the given variant with the default
     * number of degraded pages per document.  Rows: doc,label,followed by the
     * {@link FeatureExtractor} features for that variant.
     */
    public static void generate(Path out, String variant, int docCount, long seed) throws IOException {
        generate(out, variant, docCount, DEFAULT_DEGRADED_PER_DOC, seed, null, null);
    }

    /**
     * As the four-arg form, but additionally emits a viewable preview PDF (one
     * labeled sheet per doc showing every clean/degraded variant) when
     * {@code previewPdf} is non-null, streaming progress to {@code progress}.
     * The corpus CSV is byte-identical with or without a preview.
     */
    public static void generate(Path out, String variant, int docCount, long seed,
                                Path previewPdf, PrintStream progress) throws IOException {
        generate(out, variant, docCount, DEFAULT_DEGRADED_PER_DOC, seed, previewPdf, progress);
    }

    /** Full form: pinned degraded-page count plus optional preview PDF. */
    public static void generate(Path out, String variant, int docCount, int degradedPerDoc,
                                long seed, Path previewPdf, PrintStream progress) throws IOException {
        generate(out, variant, docCount, degradedPerDoc, seed, previewPdf, progress, null);
    }

    /**
     * Full form, plus steering.
     *
     * <p>The default is unsteered ({@code steer == null}), and that is deliberate
     * rather than an oversight: this method is what {@code TrainModel} calls to
     * build the corpus behind the runtime {@code quality-model.json}, whose labels
     * are measured 1-WER rather than {@code R_mrc}.  Steering those draws would
     * change the runtime gate's training data as a side effect of a change aimed
     * at the R_mrc research corpus, and the mid-band problem being fixed here is a
     * property of the R_mrc label, not of the 1-WER one.
     */
    public static void generate(Path out, String variant, int docCount, int degradedPerDoc,
                                long seed, Path previewPdf, PrintStream progress,
                                ScoreSampler.Steering steer) throws IOException {
        List<String> featureNames = new ArrayList<>(List.of(FeatureExtractor.IMAGE_FEATURE_NAMES));
        if (QualityModel.VARIANT_TEXT_LAYER.equals(variant)) {
            featureNames.addAll(List.of(FeatureExtractor.TEXT_FEATURE_NAMES));
        }

        List<Dataset.Row> rows = new ArrayList<>();
        try (PDDocument preview = previewPdf == null ? null : new PDDocument()) {
            for (int d = 0; d < docCount; d++) {
                long rngSeed = seed ^ (d * 0x9E3779B97F4A7C15L);
                Page page = renderPage(rngSeed);
                double dpi = RENDER_DPI;
                int w = page.image.getWidth();
                int h = page.image.getHeight();
                int[] gray = ImageOps.toGrayscale(page.image);
                rows.add(featureRow(gray, w, h, page.blocks, variant, "doc" + d, 0, dpi));

                List<Variant> scans = buildScannedPages(page, rngSeed, degradedPerDoc, steer);
                for (Variant v : scans) {
                    int[] g = ImageOps.toGrayscale(v.image());
                    rows.add(featureRow(g, w, h, page.blocks, variant, "doc" + d, v.label(), dpi));
                }

                if (preview != null) {
                    List<Variant> sheets = new ArrayList<>();
                    sheets.add(new Variant("clean", "clean", page.image, 1.0, 0));
                    sheets.addAll(scans);
                    int before = preview.getNumberOfPages();
                    addPreviewPages(preview, sheets, d);
                    if (progress != null) {
                        progress.println("  doc" + d + ": " + sheets.size() + " variants -> preview pages "
                                + (before + 1) + "-" + preview.getNumberOfPages());
                    }
                }
            }
            if (preview != null) {
                preview.save(previewPdf.toFile());
            }
        }
        if (out != null) {
            Dataset.writeCsv(out, rows, featureNames);
        }
    }

    /**
     * What the sampler was asked for and what it got, carried alongside the page
     * so a corpus row can always be traced back to the intent that produced it.
     *
     * @param value     the steering value actually measured, which is not the
     *                  target: the loop reports a miss rather than pretending
     * @param onTarget  whether the page converged; false rows are the evidence
     *                  for "this dimension cannot reach that far", which is
     *                  otherwise invisible in the score distribution
     */
    /**
     * @param dim  the dimension the page converged on, which is what the steering
     *             value is that dimension's sub-score for
     * @param drawn the dimension the page was *drawn* on, before any re-aim. These
     *             differ whenever the loop found the drawn dimension was not the
     *             one capping the page, and a page with degradation in both is the
     *             "a bit of everything" the single-dimension rule exists to avoid
     *             -- so the corpus has to be able to say which pages those are.
     */
    record Steer(ScoreSampler.Dim dim, String drawn, double target, double value, int attempts,
                 boolean onTarget) {

        /** Whether the loop moved the page off the dimension it was drawn on. */
        boolean reaimed() {
            return !dim.slug().equals(drawn);
        }
    }

    /**
     * @param rendered the full measurement, non-null exactly when {@code steer} is.
     *                The builder reuses it instead of measuring again, so the
     *                corpus pays one OCR pass per page rather than one per page
     *                plus one for the label
     */
    record Variant(String name, String tier, BufferedImage image, double score, int label,
                   Steer steer, ScoreSampler.Rendered rendered) {

        Variant(String name, String tier, BufferedImage image, double score, int label) {
            this(name, tier, image, score, label, null, null);
        }

        boolean steered() {
            return rendered != null;
        }
    }

    /**
     * Distinct from the sampler's: this is the per-variant draw seed, so a
     * steered page's target and dimension are fixed by the page's position in the
     * corpus and do not depend on how many workers ran or in what order pages
     * were probed.
     */
    private static final long STEER_SALT = 0x7E3D5C9A1B2F4069L;

    /** Separate from {@link #STEER_SALT} so a probe cannot perturb a page's draw. */
    private static final long CALIB_SALT = 0x0C41B2E5F7A9318DL;

    /**
     * Seed for one base page, shared by every variant drawn from that page.
     *
     * <p>Defined here so the corpus builder and {@link MrcCalibration} cannot
     * drift apart: if the two pick different seeds, calibration measures a
     * different set of clean images than the one it is calibrating for, and the
     * measured percentiles describe a corpus that is never labelled.
     *
     * @param corpusSeed the corpus-wide seed
     * @param basePage   index of the base page within the review PDF
     */
    static long basePageSeed(long corpusSeed, int basePage) {
        return corpusSeed + basePage * 1000L;
    }

    /**
     * Baseline-scan of a pristine page: the "clean" corpus sheet is not the
     * mathematically perfect raster but a fresh, light scan of it (small
     * optics blur + a whisper of sensor grain, near-lossless capture).  This
     * matters for feature realism: a pixel-perfect source reads the
     * anti-aliased text halos as "background noise" (noiseSigma), making the
     * cleanest sheets measure *noisier* than degraded photocopies.  Running
     * them through the same mild scan chain keeps every sheet on the same
     * feature scale.  Deterministic per document.
     */
    static BufferedImage cleanScan(Page page, long docSeed) {
        long seed = docSeed ^ 0x5A3C1E4B57C0DE57L;
        java.util.Random rng = new java.util.Random(seed ^ 0x51a7);
        ScannerProfile base = new ScannerProfile(
                0, 0, 1, 0.5, 0, 0, 0, 0, 0.4, 0, 0, 0, 0, 0, 0, 1, 0, 0.98f);
        return ScannerSimulator.apply(page.image(), base, rng);
    }

    /**
     * Builds {@code count} degraded variants of one page, each a deterministic
     * random draw of a {@link ScannerTier} parameter range run through the
     * {@link ScannerSimulator}.  Score and binary label come from the OCR
     * readability measurement of the rendered page against the known text.
     */
    static List<Variant> buildScannedPages(Page page, long docSeed, int count) throws IOException {
        return buildScannedPages(page, docSeed, count, null, ScoreCalibration.DEFAULT_STEPS);
    }

    /**
     * Builds {@code count} variants, steered onto a target {@code R_mrc} when
     * {@code steer} is enabled.
     *
     * <p>With steering off this is the pre-sampling behaviour, byte for byte,
     * which is the only way the A/B comparison means anything: the unsteered 500-row
     * corpus is the sole labeled baseline, and a baseline that drifts under a
     * refactor measures the refactor.
     *
     * <p>The steered path labels from the sampler's own OCR result rather than
     * re-measuring, so a page costs one OCR pass per attempt instead of one per
     * attempt plus one for the label.  It also measures on the JPEG-decoded
     * pixels, which is where the delivered page's score comes from but is not
     * where the pre-sampling path measured it.  That difference is real and is
     * noted in the docs; it is the price of having the steering value, the label
     * and the features all describe the same bytes.
     */
    static List<Variant> buildScannedPages(Page page, long docSeed, int count,
                                           ScoreSampler.Steering steer) throws IOException {
        return buildScannedPages(page, docSeed, count, steer, ScoreCalibration.DEFAULT_STEPS);
    }

    /**
     * @param calibSteps steps used to probe the reference curve when the shared
     *                   calibration is not yet built. Threaded from the caller's
     *                   flag rather than hardcoded, because a probe that silently
     *                   ignored {@code --steer-calib-steps} would make the flag
     *                   look accepted while having no effect.
     */
    static List<Variant> buildScannedPages(Page page, long docSeed, int count,
                                           ScoreSampler.Steering steer,
                                           int calibSteps) throws IOException {
        List<Variant> out = new ArrayList<>();
        int fallbacks = 0;
        for (int v = 0; v < count; v++) {
            long pageSeed = docSeed ^ (v * 0xBF58476D1CE4E5B9L);
            java.util.Random rng = new java.util.Random(pageSeed ^ 0x51a7);
            // The sampler's own stream, deliberately not `rng`: the unsteered
            // branch below draws from `rng` exactly as it always has, so a run
            // with --no-steer stays bit-reproducible no matter what the steered
            // path does with its own draws.
            java.util.Random steerRng = new java.util.Random(pageSeed ^ STEER_SALT);

            if (steer == null || !steer.enabled()) {
                out.add(unsteeredContained(page, rng, page.label(), out.size()));
                continue;
            }

            // OCR is the only fallible step left in this loop, and a timeout on one
            // page must not throw away hours of other pages: the full run is
            // hundreds of pages at tens of seconds each, and Tesseract does
            // intermittently fail on a heavily degraded raster. Fall back to the
            // unsteered path for that page, which still yields a scored row and
            // keeps the row count an invariant. The row is not steered and says so
            // in the map.
            //
            // Capped per document, because an unbounded fallback would quietly
            // turn a systemic OCR failure into an unsteered corpus that still
            // exits 0. Past the cap the cause propagates, and a run that loses a
            // handful of pages is very different from one that loses all of them.
            try {
                ScoreSampler.Render render = (profile, r) ->
                        renderSteered(page, profile, r);
                ScoreCalibration.Calibration cal = steer.calibration();
                if (cal == null) {
                    cal = steer.calibrate(render, new java.util.Random(pageSeed ^ CALIB_SALT),
                            calibSteps, page.label());
                }
                double target = steer.drawTarget(steerRng);
                ScoreSampler.Dim dim = steer.drawDim(steerRng);
                ScoreSampler.Sample s =
                        steer.sampler().sample(render, steerRng, target, dim, cal);

                ScoreSampler.Rendered r = s.rendered();
                double score = r.ocr().getAuditScore();
                Steer info = new Steer(s.aimed(), s.drawn().slug(), s.target(), s.steer(),
                        s.attempts(), s.onTarget());
                out.add(new Variant(steeredName(s), s.aimed().slug(), r.image(), score,
                        SeverityScore.label(score), info, r));
            } catch (IOException | RuntimeException e) {
                if (++fallbacks > MAX_STEER_FALLBACKS) throw e;
                System.err.printf(java.util.Locale.ROOT,
                        "warning: steering failed on page %d of %s (%s: %s); "
                                + "falling back to an unsteered row%n",
                        out.size(), page.label(), e.getClass().getSimpleName(), e.getMessage());
                out.add(unsteeredContained(page, rng, page.label(), out.size()));
            }
        }
        return out;
    }

    /**
     * Unsteered pages tolerated per document before the failure is propagated.
     * A handful over hundreds of pages is ordinary Tesseract flakiness; more than
     * that is not.
     */
    private static final int MAX_STEER_FALLBACKS = 5;

    /**
     * The unsteered draw, surviving an OCR failure by redrawing the degradation.
     *
     * <p>Needed as much as the steered path's containment: the first version of
     * that guard wrapped only the steering branch, and the 500-row run then died
     * 90 minutes in on a timeout from the <em>unsteered</em> branch, throwing away
     * every page the other four workers had finished. A failure on a page with no
     * steering to fall back to is still recoverable -- redrawing the tier gives a
     * different raster, and a page that times out is far more often a raster
     * Tesseract dislikes than a page that is unreadable.
     *
     * <p>Bounded, and the cause propagates once exhausted: past that point the
     * problem is not this page.
     */
    private static Variant unsteeredContained(Page page, java.util.Random rng, String label,
                                              int index) throws IOException {
        IOException last = null;
        for (int attempt = 1; attempt <= MAX_STEER_FALLBACKS; attempt++) {
            try {
                return unsteered(page, rng);
            } catch (IOException e) {
                last = e;
                System.err.printf(java.util.Locale.ROOT,
                        "warning: OCR failed on %s row %d, draw %d/%d (%s: %s)%n",
                        label, index, attempt, MAX_STEER_FALLBACKS,
                        e.getClass().getSimpleName(), e.getMessage());
            }
        }
        throw last;
    }

    /**
     * Probes a reference page's steering curves without installing them.
     *
     * <p>Exposed so a caller holding several pages can build a corpus-level
     * calibration from a spread of them, rather than letting whichever page
     * reaches the sampler first define the curves for all the others. Deliberately
     * does not go through {@code Steering.calibrate}, which installs on first use
     * and would make the second and third pages no-ops.
     */
    static ScoreCalibration.Calibration probeCalibration(Page page, long seed, int steps)
            throws IOException {
        return ScoreCalibration.probe((profile, r) -> renderSteered(page, profile, r),
                new java.util.Random(seed ^ CALIB_SALT), steps, page.label());
    }

    /** The unsteered tier draw, shared by the normal and fallback paths. */
    private static Variant unsteered(Page page, java.util.Random rng) throws IOException {
        ScannerTier tier = drawTier(rng);
        ScannerProfile profile = tier.draw(rng);
        BufferedImage scanned = ScannerSimulator.apply(page.image(), profile, rng);
        double score = ReadabilityOcr.measure(scanned, page.blocks());
        return new Variant(variantName(tier, profile), tier.slug(), scanned, score,
                SeverityScore.label(score));
    }

    /**
     * One steering attempt: degrade, encode, OCR, measure.
     *
     * <p>Measurement happens on {@link JpegCodec.FinalImage#decoded()} rather than
     * on the raster, so the steering value describes the pixels the page will
     * actually present after delivery compression.
     */
    private static ScoreSampler.Rendered renderSteered(SyntheticCorpus.Page page,
                                                        ScannerProfile profile,
                                                        java.util.Random rng) throws IOException {
        BufferedImage scanned = ScannerSimulator.apply(page.image(), profile, rng);
        JpegCodec.FinalImage jpeg = JpegCodec.prepare(scanned, LABEL_JPEG_QUALITY);
        BufferedImage finalImg = jpeg.decoded();
        int w = finalImg.getWidth(), h = finalImg.getHeight();
        int[] gray = ImageOps.toGrayscale(finalImg);
        ReadabilityOcr.Result ocr = ReadabilityOcr.measureWithWords(finalImg, page.blocks());
        List<TextBlock> usable = MrcReadiness.usable(ocr.getBlocks(), w, h);
        MrcReadiness.Raw raw = MrcReadiness.measure(gray, w, h, usable,
                com.mrcpdf.quality.model.SauvolaMask.compute(gray, w, h));
        return new ScoreSampler.Rendered(scanned, jpeg, ocr, raw, MrcReadiness.score(raw, usable, w, h));
    }

    /**
     * Variant name for a steered page: what was aimed, where it landed, and
     * whether it got there.
     *
     * <p>The measured value is in the name rather than the target because the
     * target is only the intent -- two pages with identical targets can differ by
     * half a point, and a name that asserts the intent would make that invisible.
     */
    private static String steeredName(ScoreSampler.Sample s) {
        return String.format(java.util.Locale.ROOT, "%s->%.2f@%d%s",
                s.aimed().slug(), s.steer(), s.attempts(), s.onTarget() ? "" : "miss");
    }

    /** Weighted tier draw so the corpus spans all readability levels. */
    private static ScannerTier drawTier(java.util.Random rng) {
        double[] w = ScannerTier.DRAW_WEIGHTS;
        double total = 0;
        for (double v : w) total += v;
        double r = rng.nextDouble() * total;
        for (int i = 0; i < ScannerTier.values().length; i++) {
            r -= w[i];
            if (r < 0) return ScannerTier.values()[i];
        }
        return ScannerTier.values()[ScannerTier.values().length - 1];
    }

    /** Human-readable variant name: tier slug + dominant effects, e.g. {@code fx40+blur1.9+jpeg57}. */
    static String variantName(ScannerTier tier, ScannerProfile p) {
        SeverityScore.Severities s = SeverityScore.severities(p);
        StringBuilder name = new StringBuilder(tier.slug());
        if (s.blur() > 0.3) name.append("+blur").append(fmt(p.blurSigma(), 1));
        if (s.motion() > 0.3) name.append("+motion").append(fmt(p.motionBlurLen(), 0));
        if (s.scale() > 0.2) name.append("+scale").append(fmt(p.downscale(), 2));
        if (s.ink() > 0.25) name.append(p.inkSpread() > 0 ? "+bold" : "+faint");
        if (s.contrast() > 0.3) name.append("+ctr");
        if (s.noise() > 0.3) name.append("+noise").append(fmt(p.noiseSigma(), 1));
        if (s.jpeg() > 0.3) name.append("+jpeg").append(fmt(p.jpegQuality() * 100, 0));
        if (s.illum() > 0.3) name.append("+shade");
        if (s.paper() > 0.3) name.append("+paper");
        if (s.bleed() > 0.3) name.append("+bleed");
        if (s.streaks() > 0.3) name.append("+streak");
        if (s.skew() > 0.3) name.append("+skew").append(fmt(p.skewDeg(), 1));
        if (s.curl() > 0.3) name.append("+curl");
        return name.toString();
    }

    private static String fmt(double v, int digits) {
        return String.format(java.util.Locale.ROOT, "%." + digits + "f", v);
    }

    // ──────────────────────────────────────────────────────────────────────
    // Preview
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Emits the preview pages for one doc: one legible full page per variant
     * (clean + every degraded draw).  Variant pages are rasterized at 2x
     * density so body text reads at roughly its true point size when the sheet
     * displays at 612x792 pt.
     */
    private static void addPreviewPages(PDDocument preview, List<Variant> variants, int docIdx)
            throws IOException {
        for (int v = 0; v < variants.size(); v++) {
            addVariantPage(preview, variants.get(v), docIdx, v + 1);
        }
    }

    /**
     * One variant at legible scale: the full page fills the sheet (2x raster,
     * 612x792 pt) with the label rendered as an overlay band in the page's
     * bottom margin, where no glyphs are drawn.
     */
    private static void addVariantPage(PDDocument preview, Variant var, int docIdx, int pageNo)
            throws IOException {
        int cw = 2 * 612;
        int ch = 2 * 792;
        double scale = Math.min((double) cw / var.image().getWidth(), (double) ch / var.image().getHeight());
        int dw = (int) Math.round(var.image().getWidth() * scale);
        int dh = (int) Math.round(var.image().getHeight() * scale);
        int dx = (cw - dw) / 2;
        int dy = (ch - dh) / 2;

        BufferedImage sheet = new BufferedImage(cw, ch, BufferedImage.TYPE_INT_RGB);
        Graphics2D g2 = sheet.createGraphics();
        try {
            g2.setColor(Color.WHITE);
            g2.fillRect(0, 0, cw, ch);
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g2.drawImage(var.image(), dx, dy, dw, dh, null);

            int bandH = 40;
            int bandY = dy + dh - bandH;
            if (bandY < ch) {
                g2.setColor(var.label() == 0 ? new Color(0, 120, 0) : new Color(180, 20, 20));
                g2.fillRect(dx, bandY, dw, bandH);
                g2.setColor(Color.WHITE);
                g2.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 22));
                g2.drawString("doc" + docIdx + "  p" + pageNo + "  " + var.name()
                        + "  (score " + fmt(var.score(), 3) + "  label " + var.label() + ")", dx + 12, bandY + 28);
            }
        } finally {
            g2.dispose();
        }
        embedSheet(preview, sheet);
    }

    private static void embedSheet(PDDocument preview, BufferedImage sheet) throws IOException {
        PDPage pdPage = new PDPage(new PDRectangle(612, 792));
        preview.addPage(pdPage);
        try (PDPageContentStream cs = new PDPageContentStream(preview, pdPage)) {
            JpegCodec.embed(preview, cs, sheet, 0.85f, 0, 0, 612, 792);
        }
    }

    private static Dataset.Row featureRow(int[] gray, int w, int h, List<TextBlock> blocks,
                                          String variant, String doc, int label, double dpi) {
        double[] imageFeatures = FeatureExtractor.extractImage(gray, w, h, dpi);
        if (!QualityModel.VARIANT_TEXT_LAYER.equals(variant)) {
            return new Dataset.Row(doc, label, imageFeatures);
        }
        float[] mag = FeatureExtractor.gradientMagnitudes(gray, w, h);
        double[] textFeatures = FeatureExtractor.extractText(gray, mag, w, h, blocks);
        if (textFeatures == null) {
            return new Dataset.Row(doc, label, imageFeatures);
        }
        double[] features = new double[imageFeatures.length + textFeatures.length];
        System.arraycopy(imageFeatures, 0, features, 0, imageFeatures.length);
        System.arraycopy(textFeatures, 0, features, imageFeatures.length, textFeatures.length);
        return new Dataset.Row(doc, label, features);
    }

    // ──────────────────────────────────────────────────────────────────────
    // Page synthesis: replicate the production raster -> JPEG -> PDF -> render
    // chain so feature distributions match pipeline inputs.
    // ──────────────────────────────────────────────────────────────────────

    /**
     * One base page to draw variants from.
     *
     * @param label a human-readable identifier, used only to name the calibration
     *              reference in reports.  Steering probes the response curves
     *              against whichever page reaches it first, and a report that says
     *              "unknown" cannot be reproduced or sanity-checked.
     */
    record Page(BufferedImage image, List<TextBlock> blocks, String label) {
        Page(BufferedImage image, List<TextBlock> blocks) {
            this(image, blocks, "base page");
        }
    }

    private record WordBox(String word, Rectangle box) {
    }

    // Richer than filler text: title + subtitle, rule, body paragraphs with
    // first-line indents and occasional bold section headings.
    private static final String[] TITLES = {
            "Annual Report", "Quality Assurance", "Field Notes",
            "Technical Memo", "Operations Manual", "Student Handbook",
            "Inspection Summary", "Procedures Draft", "Quarterly Digest",
            "Project Proposal", "Regulatory Notice", "Resource Guide"
    };

    private static final String[] SECTIONS = {
            "Introduction", "Methodology", "Findings", "Discussion",
            "Recommendations", "Appendix", "References", "Summary"
    };

    private static String pick(String[] bank, long seed) {
        return bank[(int) Math.floorMod(seed, bank.length)];
    }

    static Page renderPage(long seed) throws IOException {
        // 1. Source "scan": AWT text on a 612x792 grayscale raster, JPEG'd.
        BufferedImage src = new BufferedImage(SRC_W, SRC_H, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D g = src.createGraphics();
        java.util.Random rng = new java.util.Random(seed ^ 0xbeef);
        java.util.Random confRng = new java.util.Random(seed ^ 0xc0ffee);
        List<WordBox> wordBoxes = new ArrayList<>();
        try {
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, SRC_W, SRC_H);
            g.setColor(Color.BLACK);

            // Title (bold 18, centered)
            Font titleFont = new Font(Font.SANS_SERIF, Font.BOLD, 18);
            g.setFont(titleFont);
            FontMetrics tmf = g.getFontMetrics();
            String title = pick(TITLES, seed ^ 0xcea1);
            int ty = MARGIN + tmf.getAscent();
            g.drawString(title, (SRC_W - tmf.stringWidth(title)) / 2, ty);
            wordBoxes.addAll(fillBoxes(title, tmf, (SRC_W - tmf.stringWidth(title)) / 2, ty));

            // Subtitle (11, plain)
            Font bodyFont = new Font(Font.SANS_SERIF, Font.PLAIN, FONT_SIZE);
            g.setFont(bodyFont);
            FontMetrics fm = g.getFontMetrics();
            String subtitle = wordLine(fm, SRC_W - 2 * MARGIN, SUBTITLE_BANK, rng);
            int sy = ty + 26;
            g.drawString(subtitle, (SRC_W - fm.stringWidth(subtitle)) / 2, sy);
            wordBoxes.addAll(fillBoxes(subtitle, fm, (SRC_W - fm.stringWidth(subtitle)) / 2, sy));

            // Rule under the subtitle
            int ry = sy + 10;
            g.drawLine(MARGIN, ry, SRC_W - MARGIN, ry);

            // Body: paragraphs with first-line indents, occasional headings.
            int y = ry + 26;
            while (y < SRC_H - MARGIN) {
                if (y + LINE_HEIGHT < SRC_H - MARGIN && rng.nextInt(9) == 0) {
                    Font headFont = new Font(Font.SANS_SERIF, Font.BOLD, 12);
                    g.setFont(headFont);
                    FontMetrics hfm = g.getFontMetrics();
                    String head = pick(SECTIONS, seed ^ rng.nextLong());
                    g.drawString(head, MARGIN, y + hfm.getAscent());
                    wordBoxes.addAll(fillBoxes(head, hfm, MARGIN, y + hfm.getAscent()));
                    y += 24;
                    continue;
                }
                g.setFont(bodyFont);
                FontMetrics bfm = g.getFontMetrics();
                int indent = rng.nextInt(7) == 0 ? 24 : 0;
                int lines = 2 + rng.nextInt(4);
                for (int ln = 0; ln < lines && y < SRC_H - MARGIN; ln++) {
                    y = drawTextLine(g, bfm, rng, wordBoxes, y, ln == 0 ? indent : 0);
                }
                y += LINE_HEIGHT; // paragraph gap
            }
        } finally {
            g.dispose();
        }

        // 2. Embed the raster as a JPEG page (like the real scanned fixtures).
        File pdf = File.createTempFile("mrcpdf-corpus-", ".pdf");
        pdf.deleteOnExit();
        try (PDDocument doc = new PDDocument()) {
            PDPage pdPage = new PDPage(new PDRectangle(SRC_W, SRC_H));
            doc.addPage(pdPage);
            try (PDPageContentStream cs = new PDPageContentStream(doc, pdPage)) {
                JpegCodec.embed(doc, cs, src, SOURCE_JPEG_QUALITY, 0, 0, SRC_W, SRC_H);
            }
            doc.save(pdf);
        }

        // 3. Render the page at pipeline DPI (upscale + JPEG decode = the same
        //    soft statistics the production renderer sees).
        try (PDDocument pdfDoc = Loader.loadPDF(pdf)) {
            PDFRenderer renderer = new PDFRenderer(pdfDoc);
            BufferedImage page = renderer.renderImageWithDPI(0, RENDER_DPI);

            // Scale source boxes to the rendered image for the text features.
            double sx = page.getWidth() / (double) SRC_W;
            double sy = page.getHeight() / (double) SRC_H;
            List<TextBlock> blocks = new ArrayList<>();
            for (WordBox wb : wordBoxes) {
                Rectangle b = wb.box();
                int bw = Math.max(1, (int) Math.round(b.width * sx));
                int bh = Math.max(1, (int) Math.round(b.height * sy));
                Rectangle scaled = new Rectangle((int) Math.round(b.x * sx),
                        (int) Math.round(b.y * sy), bw, bh);
                double conf = 55 + confRng.nextInt(45); // 55..99, informative layer
                blocks.add(new TextBlock(wb.word(), scaled, conf));
            }
            return new Page(page, blocks);
        } finally {
            pdf.delete();
        }
    }

    /** Draws one wrapped line of body words and records its word boxes. */
    private static int drawTextLine(Graphics2D g, FontMetrics fm, java.util.Random rng,
                                    List<WordBox> wordBoxes, int baselineY, int indent) {
        int x = MARGIN + indent;
        int y = baselineY + fm.getAscent();
        while (x < SRC_W - MARGIN) {
            String word = WORD_BANK[rng.nextInt(WORD_BANK.length)];
            int wordWidth = fm.stringWidth(word);
            if (x + wordWidth > SRC_W - MARGIN) break;
            g.drawString(word, x, y);
            wordBoxes.add(new WordBox(word,
                    new Rectangle(x, y - fm.getAscent(), wordWidth, fm.getAscent() + fm.getDescent())));
            x += wordWidth + fm.stringWidth(" ");
        }
        return baselineY + LINE_HEIGHT;
    }

    /** Builds word boxes for a fully drawn string (title/heading). */
    private static List<WordBox> fillBoxes(String s, FontMetrics fm, int x, int baselineY) {
        List<WordBox> boxes = new ArrayList<>();
        String[] words = s.split(" ");
        int xx = x;
        int spaceW = fm.stringWidth(" ");
        for (String w : words) {
            int ww = fm.stringWidth(w);
            boxes.add(new WordBox(w, new Rectangle(xx, baselineY - fm.getAscent(), ww,
                    fm.getAscent() + fm.getDescent())));
            xx += ww + spaceW;
        }
        return boxes;
    }

    /** A subtitle-ish line of filler words up to {@code maxWidth} px. */
    private static String wordLine(FontMetrics fm, int maxWidth, String[] bank, java.util.Random rng) {
        StringBuilder sb = new StringBuilder();
        while (fm.stringWidth(sb.toString()) < maxWidth) {
            String w = bank[rng.nextInt(bank.length)];
            String candidate = sb.length() == 0 ? w : sb + " " + w;
            if (fm.stringWidth(candidate) > maxWidth) break;
            sb = new StringBuilder(candidate);
        }
        return sb.toString();
    }

    private static final String[] SUBTITLE_BANK = {
            "prepared by", "field office", "revision", "confidential",
            "draft for review", "internal use", "distribution unlimited",
            "quarter", "edition", "version", "approved", "final copy",
            "working document", "attachment", "enclosure", "notice"
    };

    private static final String[] WORD_BANK = {
            "quality", "model", "gate", "degradation", "threshold", "fidelity",
            "compression", "artifact", "blur", "noise", "jpeg", "downscale",
            "broken", "stroke", "page", "render", "background", "foreground",
            "contrast", "uniformity", "separation", "energy", "laplacian",
            "coverage", "sharpness", "informed", "sampling", "undersampling",
            "registration", "halo", "edge", "width", "fragment", "glyph",
            "scanner", "illumination", "paper", "toner", "focus", "exposure"
    };
}