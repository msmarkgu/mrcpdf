package com.mrcpdf;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

import javax.imageio.ImageIO;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import com.mrcpdf.model.PageResult;
import com.mrcpdf.model.SegmentedImage;
import com.mrcpdf.model.TextBlock;
import com.mrcpdf.pipeline.ImageEncoder;
import com.mrcpdf.pipeline.ImageSegmenter;
import com.mrcpdf.pipeline.JBIG2Compressor;
import com.mrcpdf.pipeline.PageOrigin;
import com.mrcpdf.pipeline.PDFAssembler;
import com.mrcpdf.pipeline.TextExtractor;
import com.mrcpdf.quality.TextQualityScore;
import com.mrcpdf.quality.TextQualityScorer;
import com.mrcpdf.quality.model.QualityModel;
import com.mrcpdf.util.Settings;

/**
 * MRC-based PDF compression CLI.
 *
 * Takes any PDF and produces a visually lossless compressed PDF that
 * preserves the original text (as an invisible, searchable layer),
 * bookmarks, annotations and embedded attachments.  Pure MRC — no OCR:
 * if the source PDF has no extractable text, pages are compressed
 * without a text layer.
 */
@Command(
    name = "mrcpdf",
    version = "1.0.0",
    description = "MRC-based PDF compression: visually lossless output that "
        + "preserves text, bookmarks, annotations and attachments",
    mixinStandardHelpOptions = true
)
public class MrcPdf implements Callable<Integer> {

    @Parameters(index = "0", description = "Input PDF file")
    private File inputFile;

    @Option(names = {"-o", "--output"}, description = "Output PDF file")
    private File outputFile;

    @Option(names = {"--settings"}, description = "Path to settings.jsonc file")
    private File settingsFile;

    @Option(names = {"--dpi"}, description = "Rendering DPI for PDF page images")
    private Float dpi;

    @Option(names = {"--bg-scale"}, description = "Background downscale factor for MRC (0.1 - 1.0, default 0.33)")
    private Double bgScale;

    @Option(names = {"--jpeg-quality"}, description = "Background JPEG quality when a foreground mask is present (0.1 - 1.0, default 0.50)")
    private Float jpegQuality;

    @Option(names = {"--fg-color"}, description = "Enable the true MRC foreground color plane (text keeps its original color via a soft mask); allowed under --pdfa (PDF/A-2b permits soft masks, though conformance is not verified)")
    private Boolean fgColor;

    @Option(names = {"--pdfa"}, description = "Enable PDF/A-2b output (XMP metadata, sRGB OutputIntent)")
    private Boolean pdfa;

    @Option(names = {"--threads"}, description = "Worker threads for page preparation (default: available processors)")
    private Integer threads;

    @Option(names = {"--mrc"}, description = "Enable/disable MRC compression (default: on)", negatable = true)
    private Boolean mrcEnabled;

    @Option(names = {"--pass-through"}, description = "Pass digital-native pages through unchanged (default: on)", negatable = true)
    private Boolean passThroughEnabled;

    @Option(names = {"--keep-smaller-only"}, description = "If MRC would make the output larger than the input (e.g. an already-optimized bilevel scan), write the original bytes instead of a larger file (default: off)")
    private Boolean keepSmallerOnly;

    @Override
    public Integer call() {
        Settings settings = Settings.load();
        if (settingsFile != null) {
            System.setProperty("mrcpdf.settings", settingsFile.getAbsolutePath());
            settings = Settings.load();
        }

        System.out.println();
        System.out.println("MrcPdf v1.0.0");
        System.out.println("  Input:  " + inputFile + " (" + formatSize(inputFile.length()) + ")");

        try {
            File resolvedOutput = outputFile != null ? outputFile
                    : new File(settings.getString("output.file", "output.pdf"));
            String resolvedNative = settings.getString("native.dir", "./deps/jbig2enc");

            boolean useMrc = mrcEnabled != null ? mrcEnabled : settings.getBoolean("pipeline.mrc.enabled", true);
            boolean usePdfa = pdfa != null ? pdfa : settings.getBoolean("pdf.pdfa.enabled", false);
            // Digital-native pass-through runs regardless of --mrc and of the
            // quality gate: a page already made of vectors gains nothing from
            // being rendered and re-assembled, so it is carried over as it is.
            boolean passThrough = passThroughEnabled != null ? passThroughEnabled
                    : settings.getBoolean("pipeline.origin.passThrough", true);
            boolean keepSmaller = keepSmallerOnly != null ? keepSmallerOnly
                    : settings.getBoolean("pipeline.keepSmallerOnly", false);

            float resolvedDpi = dpi != null ? dpi
                    : (float) settings.getDouble("rendering.dpi", 300);
            double resolvedBgScale = bgScale != null ? bgScale
                    : settings.getDouble("pipeline.mrc.backgroundScale", 0.33);
            // Background JPEG quality. Applies to both modes: when a foreground
            // mask is present (MRC on) the default is pipeline.mrc.jpegQuality
            // (0.50); without a mask (MRC off) the background is the only visual
            // layer, so the default is the higher pipeline.jpegQuality (0.85).
            // --jpeg-quality overrides the active mode's default.
            float resolvedJpegQuality;
            if (jpegQuality != null) {
                resolvedJpegQuality = jpegQuality;
            } else if (useMrc) {
                resolvedJpegQuality = (float) settings.getDouble("pipeline.mrc.jpegQuality", 0.50);
            } else {
                resolvedJpegQuality = (float) settings.getDouble("pipeline.jpegQuality", 0.85);
            }

            // True MRC foreground color plane.  PDF/A-2b permits a soft mask
            // (SMask) over an image, so the color layer is left enabled under
            // --pdfa; conformance is not verified, only shaping is attempted —
            // see the PDF/A notes in the README.
            boolean fgColorEnabled = fgColor != null ? fgColor
                    : settings.getBoolean("pipeline.mrc.foregroundColor", false);

            // Font for the invisible text layer (supports CJK when a TTF/OTF is set)
            String resolvedFontPath = settings.getString("pdf.fontPath", "");
            if (resolvedFontPath.isEmpty() && usePdfa) {
                resolvedFontPath = settings.getString("pdf.pdfa.fontPath", "");
            }
            if (usePdfa && resolvedFontPath.isEmpty()) {
                System.out.println("  Note: PDF/A requested but no font path set — the invisible"
                        + " text layer falls back to the base font and no font embedding is done;"
                        + " the output is PDF/A-shaped but not verified conformant.");
            }

            System.out.println("  Output: " + resolvedOutput);
            System.out.println("  DPI:    " + resolvedDpi);
            System.out.println("  MRC:    " + (useMrc ? "on" : "off"));
            System.out.println("  Pass-through: " + (passThrough ? "on" : "off"));

            ImageSegmenter segmenter = new ImageSegmenter(
                    settings.getInt("segmenter.tileSize", 64),
                    settings.getDouble("segmenter.percentile", 0.95),
                    settings.getInt("segmenter.inpaintRadius", 0),
                    settings.getString("segmenter.thresholdMode", "sauvola"),
                    settings.getInt("segmenter.sauvolaWindow", 15),
                    settings.getDouble("segmenter.sauvolaK", 0.20),
                    settings.getInt("segmenter.sauvolaR", 128),
                    settings.getDouble("segmenter.maxComponentArea", 0.005),
                    settings.getDouble("segmenter.maxComponentDim", 0.10)
            );
            JBIG2Compressor compressor = new JBIG2Compressor(resolvedNative);
            PDFAssembler assembler = new PDFAssembler(
                    settings.getString("pdf.font", "HELVETICA"),
                    (float) settings.getDouble("pdf.minFontSize", 1.0)
            );
            assembler.setCompressor(compressor);
            assembler.setBackgroundScale(resolvedBgScale);
            assembler.setBgSmoothSigma((float) settings.getDouble("pipeline.mrc.bgSmoothSigma", 0.8));
            assembler.setBgJpegQuality(resolvedJpegQuality);
            assembler.setProducer(settings.getString("pdf.producer", "MrcPdf"));
            assembler.setForegroundColorEnabled(fgColorEnabled);
            assembler.setFgScale(settings.getDouble("pipeline.mrc.foregroundScale", 0.5));
            assembler.setFgJpegQuality((float) settings.getDouble("pipeline.mrc.foregroundJpegQuality", 0.7));
            // Fidelity-path background JPEG quality (no mask -> background is the
            // only visual layer), defaults to the non-MRC pipeline.jpegQuality.
            // --jpeg-quality overrides this path as well: an explicit CLI quality
            // is a statement about the page, not about the mode it passes through.
            float resolvedFidelityQuality = jpegQuality != null
                    ? resolvedJpegQuality
                    : (float) settings.getDouble("pipeline.jpegQuality", 0.85);
            assembler.setFidelityJpegQuality(resolvedFidelityQuality);

            // Per-page text-quality gate: pages scoring below the threshold keep
            // their full-res fidelity path instead of MRC compression.
            boolean qualityGateEnabled = settings.getBoolean("pipeline.quality.gateEnabled", true);
            double qualityGateThreshold = settings.getDouble("pipeline.quality.threshold",
                    TextQualityScorer.DEFAULT_GATE_THRESHOLD);

            // Per-page gate model: the 11-feature digital-native-adjacent image
            // screen trained on the real corpus.  When loaded it decides which
            // non-native pages are worth compressing; absent, the geometric
            // gate runs unchanged.
            boolean modelEnabled = settings.getBoolean("pipeline.quality.model.enabled", true);
            QualityModel qualityModel = null;
            if (modelEnabled) {
                qualityModel = QualityModel.loadScreenDefault();
                if (qualityModel != null) {
                    // predict() is p(not good) -- the artifact's polarity is
                    // flipped on load so the gate's meaning is unchanged.
                    System.out.printf("  Model:  %s gate, %d features, skip when p(not good)>=%.3f (holdout AUC %.3f)%n",
                            qualityModel.variant(), qualityModel.featureNames().size(),
                            qualityModel.threshold(), qualityModel.auc());
                }
            }

            // Resolve worker thread count: CLI arg > settings cap > available processors
            int workerThreads;
            if (threads != null) {
                workerThreads = threads;
            } else {
                int maxThreads = settings.getInt("pipeline.maxThreads", 0);
                int avail = Runtime.getRuntime().availableProcessors();
                workerThreads = maxThreads > 0 ? Math.min(avail, maxThreads) : avail;
            }
            System.out.println("  Workers: " + workerThreads + " thread(s)");
            System.out.println("  Foreground: " + (useMrc && fgColorEnabled ? "color layer" : "black stencil"));
            if (!useMrc) {
                System.out.println("  Quality gate: off (MRC disabled)");
            } else if (!qualityGateEnabled) {
                System.out.println("  Quality gate: off (disabled in settings)");
            } else if (qualityModel != null) {
                System.out.printf("  Quality gate: model (%s, %d features, threshold %.3f)%n",
                        qualityModel.variant(), qualityModel.featureNames().size(),
                        qualityModel.threshold());
            } else {
                System.out.printf("  Quality gate: geometric (threshold %.2f)%n", qualityGateThreshold);
            }

            runPipeline(inputFile, resolvedOutput, segmenter, compressor, assembler,
                    useMrc, usePdfa, passThrough, resolvedDpi, workerThreads, resolvedFontPath,
                    fgColorEnabled, resolvedBgScale, resolvedJpegQuality, qualityGateEnabled,
                    qualityGateThreshold, qualityModel, keepSmaller);
            return 0;

        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
            return 1;
        }
    }

    private void runPipeline(File inputFile, File outputFile,
                             ImageSegmenter segmenter,
                             JBIG2Compressor compressor, PDFAssembler assembler,
                             boolean useMrc, boolean usePdfa, boolean passThrough, float dpi,
                             int workerThreads, String fontPath, boolean fgColorEnabled,
                             double resolvedBgScale, float resolvedJpegQuality,
                             boolean qualityGateEnabled, double qualityGateThreshold,
                             QualityModel qualityModel, boolean keepSmallerOnly) throws IOException {
        Path tempDir = createTempDir(inputFile.getName().replaceAll("\\.[^.]+$", ""));

        try (PDDocument source = Loader.loadPDF(inputFile)) {
            PDFRenderer renderer = new PDFRenderer(source);
            int pageCount = source.getNumberOfPages();
            int srcWords = countWords(source);
            System.out.println("  Pages:  " + pageCount);
            if (srcWords > 0) {
                System.out.println("  Words:  " + srcWords + " (source text)");
            } else {
                System.out.println("  Words:  0 — no text in source; pages will have no text layer");
                String advisory = textLayerAdvisory(pageCount);
                if (advisory != null) System.err.println(advisory);
            }

            long totalStart = System.nanoTime();

            // ── Origin pass (main thread): which pages are digital native? ──
            //   Decided before the render loop so native pages are never
            //   rasterised at all -- the whole point of passing them through.
            //   The same pass measures each page's source resolution so a
            //   low-resolution scan is not re-rendered at the configured DPI.
            boolean[] nativePage = new boolean[pageCount];
            float[] pageDpi = new float[pageCount];
            int nativeCount = 0;
            int nativeDpiCount = 0;
            for (int i = 0; i < pageCount; i++) {
                PageOrigin.Probe p = PageOrigin.probe(source.getPage(i));
                nativePage[i] = passThrough
                        && p.getCoverage() < PageOrigin.PAGE_FILL_COVERAGE;
                pageDpi[i] = effectiveRenderDpi(dpi, p.getNativeDpi());
                if (nativePage[i]) nativeCount++;
                if (pageDpi[i] < dpi) nativeDpiCount++;
            }
            if (passThrough) {
                System.out.println("  Pass-through pages: " + nativeCount + " (digital native)");
            }
            if (nativeDpiCount > 0) {
                System.out.println("  Native-resolution pages: " + nativeDpiCount
                        + " (rendered below " + (int) dpi + " dpi)");
            }

            System.out.println("  Processing " + pageCount + " pages...");

            // ── Pass 1: render + prep ──
            //   Rendering is sequential on the main thread (PDFRenderer is not
            //   thread-safe).  Segmentation + text extraction + image writes run
            //   in the worker pool, bounded by workerThreads.  A Semaphore
            //   throttles the main thread to workerThreads + 2 queued pages.
            // Per-page holders for worker-produced data (filled on worker threads,
            // consumed on the main thread after all workers complete).
            class PageData {
                int imgW, imgH;
                byte[] bgJpeg;
                byte[] fgColorJpeg;
                BufferedImage mask;
                TextQualityScore qualityScore;
                boolean gateSkip;
                boolean passThrough;
            }
            PageData[] pageData = new PageData[pageCount];
            AtomicInteger threadCounter = new AtomicInteger(1);
            ExecutorService executor = Executors.newFixedThreadPool(workerThreads,
                    r -> new Thread(r, "prep-" + threadCounter.getAndIncrement()));
            try {
                List<Future<Void>> futures = new ArrayList<>(pageCount);
                long pipelineStart = System.nanoTime();
                String perPageFmt = "    [prep-%d] page %d: %4.1fs (cum %5.1fs)%n";

                Semaphore semaphore = new Semaphore(workerThreads + 2);

                for (int i = 0; i < pageCount; i++) {
                    try {
                        semaphore.acquire();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IOException("Pipeline interrupted", e);
                    }

                    final int pageIdx = i;
                    // Digital-native pages are never rasterised at all.
                    final BufferedImage page = nativePage[i] ? null
                            : renderer.renderImageWithDPI(i, pageDpi[i]);

                    futures.add(executor.submit(() -> {
                        long localStart = System.nanoTime();
                        try {
                            PageData pd = new PageData();
                            pageData[pageIdx] = pd;
                            pd.passThrough = nativePage[pageIdx];
                            if (pd.passThrough) {
                                pd.imgW = rasterWidth(source.getPage(pageIdx), pageDpi[pageIdx]);
                                pd.imgH = rasterHeight(source.getPage(pageIdx), pageDpi[pageIdx]);
                            } else {
                                pd.imgW = page.getWidth();
                                pd.imgH = page.getHeight();
                            }

                            boolean screenGood = true;
                            // Per-page text-quality gate: score the rendered page
                            // and, when quality is low, keep the fidelity path
                            // (full-res page, no MRC segmentation).  Scoring uses
                            // whole-page metrics: the PDFBox-derived text layer
                            // pins confidence to exactly 100.0, so text-layer
                            // glyph scoring is not engaged by this pipeline.
                            if (!pd.passThrough && useMrc && qualityGateEnabled) {
                                // The scorer normalises pixel-length features by
                                // DPI, so it must see the DPI this page was
                                // actually rendered at, not the configured target.
                                TextQualityScorer scorer =
                                        new TextQualityScorer(pageDpi[pageIdx], qualityModel);
                                TextQualityScore score = scorer.compute(page, null);
                                // Model gate when a decision is available; the
                                // geometric threshold is the fallback (no model
                                // or text-layer variant with uninformative layer).
                                pd.gateSkip = score.isModelSkip()
                                        || (score.getModelProbability() < 0
                                            && score.isBelow(qualityGateThreshold));
                                pd.qualityScore = score;
                                screenGood = !pd.gateSkip;
                                if (pd.gateSkip) {
                                    System.out.printf("    [quality] page %d: composite %.2f, p(not good)=%s — MRC skipped (fidelity path)%n",
                                            pageIdx + 1, score.getComposite(),
                                            score.getModelProbability() >= 0
                                                    ? String.format("%.2f", score.getModelProbability())
                                                    : "n/a");
                                }
                            }

                            if (pd.passThrough) {
                                // Nothing to segment or encode.  The JBIG2 batch
                                // runs over the whole document, so this page still
                                // needs a mask slot to keep the indices aligned.
                                if (useMrc) {
                                    writeWhiteMask(tempDir, pageIdx, pd.imgW, pd.imgH);
                                }
                            } else if (runMrc(pd.passThrough, useMrc, qualityGateEnabled,
                                    screenGood)) {
                                SegmentedImage seg = segmenter.segment(page);
                                // Write the mask as PNG for jbig2enc (lossless,
                                // compact), then drop the in-memory copy: it is a
                                // full-resolution binary per page and the only
                                // later consumer is the CCITT fallback, which
                                // re-reads the PNG from disk at assembly time.
                                // Retaining it here pinned every page's mask in
                                // the heap for the whole pipeline run.
                                ImageIO.write(seg.getForegroundMask(), "png",
                                        new File(tempDir.toFile(), "mask-" + pageIdx + ".png"));
                                pd.mask = null;
                                pd.bgJpeg = ImageEncoder.encodeBackground(seg.getCleanedBackground(),
                                        assembler.getBgJpegQuality(), true,
                                        assembler.getBackgroundScale(), assembler.getBgSmoothSigma());
                                if (fgColorEnabled) {
                                    pd.fgColorJpeg = ImageEncoder.encodeForegroundColor(page,
                                            assembler.getFgJpegQuality(), assembler.getFgScale());
                                }
                            } else if (useMrc) {
                                // Gate-skipped: no segmentation; an empty white
                                // mask keeps the JBIG2 batch index-aligned.  The
                                // full-resolution page is encoded here in the
                                // worker (once) so assembly never re-renders it.
                                pd.bgJpeg = ImageEncoder.encodeBackground(page,
                                        assembler.getFidelityJpegQuality(), false, 1.0, 0f);
                                writeWhiteMask(tempDir, pageIdx, pd.imgW, pd.imgH);
                            } else {
                                pd.bgJpeg = ImageEncoder.encodeBackground(page,
                                        resolvedJpegQuality, false, 1.0, 0f);
                            }

                            double elapsed = (System.nanoTime() - localStart) / 1e9;
                            double cumulative = (System.nanoTime() - pipelineStart) / 1e9;
                            System.out.printf(perPageFmt, pageIdx + 1, pageIdx + 1,
                                    elapsed, cumulative);
                            return null;
                        } finally {
                            semaphore.release();
                        }
                    }));
                }

                // Shutdown executor (no more submissions)
                executor.shutdown();

                // Wait for all workers to finish
                try {
                    for (Future<Void> f : futures) {
                        f.get();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Pipeline was interrupted", e);
                } catch (ExecutionException e) {
                    throw new IOException("Page prep task failed", e.getCause());
                }

                double processingDone = System.nanoTime();
                double processingWall = (processingDone - pipelineStart) / 1e9;
                System.out.printf("    Processing done: %d pages in %.1fs (%d threads)%n",
                        pageCount, processingWall, workerThreads);

                // ── Text extraction (main thread — PDFTextStripper is not thread-safe) ──
                List<TextBlock>[] textBlocksOut = new List[1];
                List<PageResult> results = new ArrayList<>(pageCount);
                for (int i = 0; i < pageCount; i++) {
                    PageData pd = pageData[i];
                    // Extracted for every page, pass-through included: the
                    // blocks are what the final word count and the CJK font
                    // check read. A pass-through page never has a layer
                    // written for it -- it keeps its own -- but its words are
                    // still in the output and are still counted.
                    TextExtractor.extractTextBlocks(source, i, pageDpi[i], pd.imgW, pd.imgH, textBlocksOut);
                    results.add(new PageResult(i, pd.imgW, pd.imgH, textBlocksOut[0],
                            pd.bgJpeg, pd.fgColorJpeg, pd.mask,
                            pd.qualityScore, pd.gateSkip, pd.passThrough));
                }

                // ── Font resolution for the invisible text layer ──
                resolveTextFont(assembler, fontPath, results);

                // ── JBIG2 batch compression (shared dictionary across all pages) ──
                JBIG2Compressor.BatchResult jbig2Batch = null;
                if (useMrc) {
                    long t0 = System.nanoTime();
                    System.out.println("  Batch JBIG2 compression...");
                    int[] imgWidths = new int[pageCount];
                    int[] imgHeights = new int[pageCount];
                    for (int i = 0; i < pageCount; i++) {
                        imgWidths[i] = pageData[i].imgW;
                        imgHeights[i] = pageData[i].imgH;
                    }
                    jbig2Batch = compressor.compressAllFromDir(tempDir.toFile(), pageCount, imgWidths, imgHeights);
                    double elapsed = (System.nanoTime() - t0) / 1e9;
                    if (jbig2Batch != null) {
                        System.out.printf("    JBIG2 batch done in %.1fs (sym: %d bytes)%n",
                                elapsed, jbig2Batch.getGlobalSym().length);
                    } else {
                        System.out.println("    JBIG2 unavailable — using CCITT G4 fallback");
                    }
                }

                // ── Pass 2: assemble (streaming) ──
                if (useMrc && jbig2Batch == null) {
                    // jbig2enc is unavailable, so disable the assembler's per-page
                    // compressor: without this, addPage() would spawn a failing
                    // jbig2enc subprocess for every page before falling back to
                    // CCITT G4. The single-page compress() API stays available
                    // for embedders/tests.
                    assembler.setCompressor(null);
                }
                System.out.println("  Assembling PDF...");
                try (PDDocument output = new PDDocument()) {
                    List<PDPage> outPages = new ArrayList<>(pageCount);
                    Set<PDPage> passThroughPages = new HashSet<>();
                    int fidelityPages = 0;

                    for (int i = 0; i < pageCount; i++) {
                        PageResult pr = results.get(i);
                        PDPage outPage;
                        if (pr.isPassThrough()) {
                            // Digital native: the source page, untouched.
                            outPage = assembler.importOriginalPage(output, source, i);
                            passThroughPages.add(outPage);
                        } else if (pr.isMrcSkip()) {
                            // Quality gate: original page as the only visual
                            // layer.  The worker already rendered it and encoded
                            // the full-res JPEG, so there is no second render.
                            outPage = assembler.addPageFidelity(output, source, i,
                                    pr.getBgJpeg(), pr);
                            fidelityPages++;
                        } else if (jbig2Batch != null && jbig2Batch.getGlobalSym().length > 0) {
                            JBIG2Compressor.CompressionResult jbig2Page = jbig2Batch.getPages().get(i);
                            outPage = assembler.addPageJbig2(output, source, i,
                                    pr.getBgJpeg(), pr.getFgColorJpeg(),
                                    jbig2Page.getData(), jbig2Batch.getGlobalSym(),
                                    jbig2Page.getWidth(), jbig2Page.getHeight(),
                                    pr);
                        } else if (useMrc) {
                            // jbig2enc unavailable: CCITT fallback.  The worker's
                            // mask was spilled to disk to keep the heap flat, so
                            // re-read it here (a page masked white for the
                            // gate keeps its file, so the image is non-null).
                            BufferedImage mask = null;
                            File maskFile = new File(tempDir.toFile(), "mask-" + i + ".png");
                            if (maskFile.isFile()) {
                                mask = ImageIO.read(maskFile);
                            }
                            outPage = assembler.addPage(output, source, i,
                                    pr.getBgJpeg(), pr.getFgColorJpeg(),
                                    mask, pr);
                        } else {
                            outPage = assembler.addPage(output, source, i,
                                    pr.getBgJpeg(), null, null, pr);
                        }

                        outPages.add(outPage);
                    }
                    if (fidelityPages > 0) {
                        System.out.println("  Fidelity pages: " + fidelityPages
                                + " kept at full resolution (quality gate)");
                    }

                    // Finalize: copy metadata, add PDF/A if needed
                    System.out.println("  Finalizing document...");
                    var preserved = assembler.finishAssembly(output, source, outPages, usePdfa, passThroughPages);
                    if (preserved.outlines() > 0)
                        System.out.println("  Bookmarks:  " + preserved.outlines() + " (preserved)");
                    if (preserved.annotations() > 0)
                        System.out.println("  Links:      " + preserved.annotations() + " (preserved)");
                    if (preserved.embeddedFiles() > 0)
                        System.out.println("  Attachments: " + preserved.embeddedFiles() + " (preserved)");
                    // Resolve the parent to an absolute path: a bare "out.pdf" has a null
                    // parent, which would silently redirect the temp file to the
                    // system temp dir and defeat the atomic move below.
                    File outParent = outputFile.getAbsoluteFile().getParentFile();
                    File tempOutput = File.createTempFile("mrcpdf-", ".pdf", outParent);
                    try {
                        output.save(tempOutput);
                        try {
                            Files.move(tempOutput.toPath(), outputFile.toPath(),
                                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                        } catch (AtomicMoveNotSupportedException e) {
                            // The filesystem/volume does not support atomic renames.
                            // A finished document should not be thrown away over a
                            // rename nicety: fall back to a plain replace.
                            Files.move(tempOutput.toPath(), outputFile.toPath(),
                                    StandardCopyOption.REPLACE_EXISTING);
                        }
                    } catch (Exception e) {
                        tempOutput.delete();
                        throw e;
                    }

                    // ── Expansion guard ──────────────────────────────────────
                    // MRC adds a background layer per page, so on an input that
                    // is already tightly optimized (a bilevel CCITT scan, say)
                    // the "compressed" output can be larger than what it
                    // replaced.  Default is to keep the MRC result and say so;
                    // silently discarding it would be worse, and silently
                    // handing back a larger file is what we are fixing.
                    long inSize = inputFile.length();
                    long outSize = outputFile.length();
                    String expansion = expansionNotice(inSize, outSize, keepSmallerOnly);
                    if (expansion != null) {
                        System.err.println(expansion);
                        if (keepSmallerOnly) {
                            Files.copy(inputFile.toPath(), outputFile.toPath(),
                                    StandardCopyOption.REPLACE_EXISTING);
                            System.out.println("  Output: " + outputFile.getName()
                                    + " (" + formatSize(outputFile.length())
                                    + ", original kept -- smaller than the MRC result)");
                        }
                    }

                    long totalElapsed = (System.nanoTime() - totalStart) / 1_000_000_000L;
                    System.out.printf("  Total: %d pages in %d:%02d%n", pageCount,
                            totalElapsed / 60, totalElapsed % 60);

                    int outWords = countExtractedWords(results);
                    System.out.println("  Output: " + outputFile.getName() + " (" + formatSize(outputFile.length()) + ")");
                    System.out.println("  Words:  " + outWords + " (extracted text)");
                    System.out.println();
                }
            } finally {
                executor.shutdownNow();
            }
        } finally {
            deleteDir(tempDir);
        }
    }

    private static Path createTempDir(String prefix) throws IOException {
        String base = System.getProperty("mrcpdf.temp.dir");
        if (base != null && !base.isEmpty()) {
            return Files.createTempDirectory(Path.of(base), prefix + "-");
        }
        return Files.createTempDirectory(prefix + "-");
    }

    private static void deleteDir(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        Files.walkFileTree(dir, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }
            @Override
            public FileVisitResult postVisitDirectory(Path d, IOException exc) throws IOException {
                Files.delete(d);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /**
     * Resolves the font for the invisible text layer.
     * Priority: explicit font path > auto-detected CJK font (when the
     * extracted text contains CJK characters) > built-in Standard 14 font.
     */
    private static void resolveTextFont(PDFAssembler assembler, String fontPath,
                                        List<PageResult> results) {
        if (fontPath != null && !fontPath.isEmpty()) {
            assembler.setFont(new File(fontPath));
            return;
        }
        if (containsCjkText(results)) {
            File cjkFont = PDFAssembler.findCjkFont();
            if (cjkFont != null) {
                System.out.println("  CJK text detected — using font: " + cjkFont.getName());
                assembler.setFont(cjkFont);
            }
        }
    }

    private static boolean containsCjkText(List<PageResult> results) {
        for (PageResult page : results) {
            for (TextBlock tb : page.getTextBlocks()) {
                for (int i = 0; i < tb.getWord().length(); i++) {
                    char cp = tb.getWord().charAt(i);
                    if ((cp >= 0x4E00 && cp <= 0x9FFF)
                            || (cp >= 0x3040 && cp <= 0x309F)
                            || (cp >= 0x30A0 && cp <= 0x30FF)
                            || (cp >= 0xAC00 && cp <= 0xD7AF)
                            || (cp >= 0x3400 && cp <= 0x4DBF)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * The conjunction this pipeline is built on: a page is MRC-compressed only
     * when it is not digital native and the screen calls it worth compressing.
     * A native page passes through whatever the screen says; with MRC off every
     * page takes the plain raster path; with the gate disabled every
     * non-native page is compressed.
     *
     * <p>Package-private so all four combinations of the first and last
     * arguments are directly testable rather than inferred through the
     * worker's branch structure.
     */
    static boolean runMrc(boolean digitalNative, boolean useMrc, boolean qualityGateEnabled,
                          boolean screenGood) {
        if (digitalNative || !useMrc) return false;
        return !qualityGateEnabled || screenGood;
    }

    /**
     * Floor for the per-page render DPI derived from a source raster. A measured
     * resolution is a compression hint, not a contract: below this it stops
     * looking like a real page resolution (a tiny logo drawn large) and
     * re-rendering there would cost fidelity for no meaningful size win.
     */
    static final float MIN_RENDER_DPI = 36f;

    /**
     * The DPI to render one page at: the configured DPI, lowered to the source
     * raster's native resolution when that is lower. Never raised -- a source
     * richer than the target is still rendered at the target -- and never below
     * {@link #MIN_RENDER_DPI}.
     *
     * @param configured the DPI from settings / the CLI
     * @param nativeDpi  the source raster's resolution, or 0 when unknown
     */
    static float effectiveRenderDpi(float configured, double nativeDpi) {
        if (nativeDpi <= 0) return configured;
        return Math.max(MIN_RENDER_DPI, (float) Math.min(configured, nativeDpi));
    }

    /** Rendered pixel width of a source page at {@code dpi}, rotation applied. */
    private static int rasterWidth(PDPage page, float dpi) {
        PDRectangle box = page.getCropBox();
        return rasterDimension(page.getRotation() % 180 != 0 ? box.getHeight() : box.getWidth(),
                dpi);
    }

    /** Rendered pixel height of a source page at {@code dpi}, rotation applied. */
    private static int rasterHeight(PDPage page, float dpi) {
        PDRectangle box = page.getCropBox();
        return rasterDimension(page.getRotation() % 180 != 0 ? box.getWidth() : box.getHeight(),
                dpi);
    }

    private static int rasterDimension(float points, float dpi) {
        // PDFBox renders a rotated page to exactly floor(points * dpi / 72)
        // pixels; rounding up here produced a size the renderer never emitted
        // and the mask/geometry were written at a dimension the render never
        // matched (A8).
        return Math.max(1, (int) Math.floor(points * dpi / 72.0));
    }

    /**
     * Writes an all-white mask for a page that produced no foreground. The
     * JBIG2 batch compresses masks by document index, so every page needs a
     * file even when nothing was segmented.
     */
    private static void writeWhiteMask(Path tempDir, int pageIndex, int width, int height)
            throws IOException {
        BufferedImage mask = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_BINARY);
        Graphics2D g = mask.createGraphics();
        g.setColor(java.awt.Color.WHITE);
        g.fillRect(0, 0, width, height);
        g.dispose();
        ImageIO.write(mask, "png", new File(tempDir.toFile(), "mask-" + pageIndex + ".png"));
    }

    private static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.0f KB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
    }

    /**
     * Advisory for a source whose text layer is empty.
     *
     * <p>This tool never runs OCR by design, so an image-only scan keeps no
     * searchable text: the output is a smaller picture, not a searchable
     * document. That is a fine outcome, but it is silent unless we say so, and
     * it is the single most common way a user is surprised -- "no recognition
     * errors" reads as "no missing text". So say it plainly, once per document,
     * with the remedy.
     *
     * @return the warning to print to stderr, or {@code null} if there is
     *         nothing to warn about.
     */
    static String textLayerAdvisory(int pageCount) {
        if (pageCount <= 0) return null;
        return "  Warning: no text layer found in any of the " + pageCount
            + " source page" + (pageCount == 1 ? "" : "s")
            + ". mrcpdf does not run OCR, so the output will not be searchable."
            + "\n           To get a searchable PDF, add a text layer first"
            + " (e.g. ocrmypdf input.pdf output.pdf) and then run mrcpdf on the"
            + " result; mrcpdf will re-use that layer and keep it sharp.";
    }

    /**
     * Decides whether the MRC result is a net loss for this document, and words
     * the warning if so.
     *
     * <p>MRC is not a guaranteed win. A page that is already a well-compressed
     * 1-bit mask gets a background JPEG bolted on top, and the background is
     * roughly 84% of the output — so against an already-tight input the
     * "compressed" file can come out larger.
     *
     * <p>Default behaviour keeps the MRC result and warns: the tool was asked to
     * compress, and quietly handing back the input instead would hide what
     * happened. {@code --keep-smaller-only} opts into never enlarging a file.
     *
     * @return the warning to print to stderr, or {@code null} when the output
     *         did not expand
     */
    static String expansionNotice(long inBytes, long outBytes, boolean keepSmallerOnly) {
        if (outBytes <= inBytes) return null;
        String pct = inBytes <= 0 ? "unknown"
                : String.format("%.1f%%", (outBytes - inBytes) * 100.0 / inBytes);
        return "  Warning: MRC made the output larger -- input "
            + formatSize(inBytes) + ", output " + formatSize(outBytes) + " (+" + pct + ")."
            + (keepSmallerOnly
                ? " --keep-smaller-only is set, so the original bytes were kept instead."
                : " The source is probably already well compressed (e.g. a bilevel"
                  + " CCITT scan). Pass --keep-smaller-only to never enlarge a file.");
    }

    private static int countWords(PDDocument doc) throws IOException {
        PDFTextStripper stripper = new PDFTextStripper();
        int totalPages = doc.getNumberOfPages();
        int samplePages = Math.min(10, totalPages);
        stripper.setStartPage(1);
        stripper.setEndPage(samplePages);
        String text = stripper.getText(doc).trim();
        if (text.isEmpty()) return 0;
        int sampleCount = text.split("\\s+").length;
        if (samplePages >= totalPages) return sampleCount;
        return (int) ((double) sampleCount / samplePages * totalPages);
    }

    private static int countExtractedWords(List<PageResult> results) {
        int count = 0;
        for (PageResult page : results) {
            for (TextBlock tb : page.getTextBlocks()) {
                String w = tb.getWord().trim();
                if (!w.isEmpty()) count++;
            }
        }
        return count;
    }

    public static void main(String[] args) {
        int exitCode = new CommandLine(new MrcPdf()).execute(args);
        System.exit(exitCode);
    }
}
