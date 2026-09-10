package com.mrcpdf;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
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
import com.mrcpdf.pipeline.PDFAssembler;
import com.mrcpdf.pipeline.TextExtractor;
import com.mrcpdf.quality.TextQualityScore;
import com.mrcpdf.quality.TextQualityScorer;
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

    @Option(names = {"--fg-color"}, description = "Enable the true MRC foreground color plane (text keeps its original color via a soft mask); not compatible with --pdfa")
    private Boolean fgColor;

    @Option(names = {"--pdfa"}, description = "Enable PDF/A-2b output (XMP metadata, sRGB OutputIntent)")
    private Boolean pdfa;

    @Option(names = {"--threads"}, description = "Worker threads for page preparation (default: available processors)")
    private Integer threads;

    @Option(names = {"--mrc"}, description = "Enable/disable MRC compression (default: on)", negatable = true)
    private Boolean mrcEnabled;

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

            // True MRC foreground color plane: soft masks are not allowed in
            // PDF/A-2b, so force the setting off and warn when both are requested.
            boolean fgColorEnabled = fgColor != null ? fgColor
                    : settings.getBoolean("pipeline.mrc.foregroundColor", false);
            if (usePdfa && fgColorEnabled) {
                System.out.println("  Note: foreground color layer disabled under PDF/A (soft masks not allowed)");
                fgColorEnabled = false;
            }

            // Font for the invisible text layer (supports CJK when a TTF/OTF is set)
            String resolvedFontPath = settings.getString("pdf.fontPath", "");
            if (resolvedFontPath.isEmpty() && usePdfa) {
                resolvedFontPath = settings.getString("pdf.pdfa.fontPath", "");
            }

            System.out.println("  Output: " + resolvedOutput);
            System.out.println("  DPI:    " + resolvedDpi);
            System.out.println("  MRC:    " + (useMrc ? "on" : "off"));

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
            assembler.setFidelityJpegQuality((float) settings.getDouble("pipeline.jpegQuality", 0.85));

            // Per-page text-quality gate: pages scoring below the threshold keep
            // their full-res fidelity path instead of MRC compression.
            boolean qualityGateEnabled = settings.getBoolean("pipeline.quality.gateEnabled", true);
            double qualityGateThreshold = settings.getDouble("pipeline.quality.threshold",
                    TextQualityScorer.DEFAULT_GATE_THRESHOLD);

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
            System.out.println("  Quality gate: " + (useMrc && qualityGateEnabled
                    ? String.format("on (threshold %.2f)", qualityGateThreshold) : "off"));

            runPipeline(inputFile, resolvedOutput, segmenter, compressor, assembler,
                    useMrc, usePdfa, resolvedDpi, workerThreads, resolvedFontPath, fgColorEnabled,
                    resolvedBgScale, resolvedJpegQuality, qualityGateEnabled, qualityGateThreshold);
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
                             boolean useMrc, boolean usePdfa, float dpi,
                             int workerThreads, String fontPath, boolean fgColorEnabled,
                             double resolvedBgScale, float resolvedJpegQuality,
                             boolean qualityGateEnabled, double qualityGateThreshold) throws IOException {
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
            }

            long totalStart = System.nanoTime();
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
            }
            PageData[] pageData = new PageData[pageCount];
            AtomicInteger threadCounter = new AtomicInteger(1);
            ExecutorService executor = Executors.newFixedThreadPool(workerThreads,
                    r -> new Thread(r, "prep-" + threadCounter.getAndIncrement()));
            try {
                List<Future<Void>> futures = new ArrayList<>(pageCount);
                long pipelineStart = System.nanoTime();
                String perPageFmt = "    [prep-%d] page %d: %4.1fs%n";

                // Thread-safe (stateless) page-quality scorer for the gate.
                TextQualityScorer scorer = new TextQualityScorer(dpi);

                Semaphore semaphore = new Semaphore(workerThreads + 2);

                for (int i = 0; i < pageCount; i++) {
                    try {
                        semaphore.acquire();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IOException("Pipeline interrupted", e);
                    }

                    BufferedImage page = renderer.renderImageWithDPI(i, dpi);

                    final int pageIdx = i;
                    futures.add(executor.submit(() -> {
                        long localStart = System.nanoTime();
                        try {
                            PageData pd = new PageData();
                            pageData[pageIdx] = pd;
                            pd.imgW = page.getWidth();
                            pd.imgH = page.getHeight();

// Per-page text-quality gate: score the rendered page
                            // and, when quality is low, keep the fidelity path
                            // (full-res page, no MRC segmentation).  Scoring uses
                            // whole-page metrics: the PDFBox-derived text layer
                            // pins confidence to exactly 100.0, so text-layer
                            // glyph scoring is not engaged by this pipeline.
                            if (useMrc && qualityGateEnabled) {
                                TextQualityScore score = scorer.compute(page, null);
                                pd.gateSkip = score.getComposite() < qualityGateThreshold;
                                pd.qualityScore = score;
                                if (pd.gateSkip) {
                                    System.out.printf("    [quality] page %d: %.2f — MRC skipped (fidelity path)%n",
                                            pageIdx + 1, score.getComposite());
                                }
                            }

                            if (useMrc && !pd.gateSkip) {
                                SegmentedImage seg = segmenter.segment(page);
                                pd.mask = seg.getForegroundMask();
                                // Write mask as PNG for jbig2enc (lossless, compact)
                                ImageIO.write(seg.getForegroundMask(), "png",
                                        new File(tempDir.toFile(), "mask-" + pageIdx + ".png"));
                                pd.bgJpeg = ImageEncoder.encodeBackground(seg.getCleanedBackground(),
                                        assembler.getBgJpegQuality(), true,
                                        assembler.getBackgroundScale(), assembler.getBgSmoothSigma());
                                if (fgColorEnabled) {
                                    pd.fgColorJpeg = ImageEncoder.encodeForegroundColor(page,
                                            assembler.getFgJpegQuality(), assembler.getFgScale());
                                }
                            } else if (useMrc) {
                                // Gate-skipped: no segmentation; write an empty
                                // white mask so the JBIG2 batch stays index-aligned.
                                BufferedImage emptyMask = new BufferedImage(
                                        page.getWidth(), page.getHeight(),
                                        BufferedImage.TYPE_BYTE_BINARY);
                                emptyMask.getGraphics().setColor(java.awt.Color.WHITE);
                                emptyMask.getGraphics().fillRect(0, 0,
                                        page.getWidth(), page.getHeight());
                                emptyMask.getGraphics().dispose();
                                ImageIO.write(emptyMask, "png",
                                        new File(tempDir.toFile(), "mask-" + pageIdx + ".png"));
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
                    TextExtractor.extractTextBlocks(source, i, dpi, pd.imgW, pd.imgH, textBlocksOut);
                    results.add(new PageResult(i, pd.imgW, pd.imgH, textBlocksOut[0],
                            pd.bgJpeg, pd.fgColorJpeg, pd.mask,
                            pd.qualityScore, pd.gateSkip));
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
                    int fidelityPages = 0;

                    for (int i = 0; i < pageCount; i++) {
                        PageResult pr = results.get(i);
                        PDPage outPage;
                        if (pr.isMrcSkip()) {
                            // Quality gate: original page as the only visual layer.
                            BufferedImage bg = renderer.renderImageWithDPI(i, dpi);
                            outPage = assembler.addPageFidelity(output, source, i, bg, pr);
                            fidelityPages++;
                        } else if (jbig2Batch != null && jbig2Batch.getGlobalSym().length > 0) {
                            JBIG2Compressor.CompressionResult jbig2Page = jbig2Batch.getPages().get(i);
                            outPage = assembler.addPageJbig2(output, source, i,
                                    pr.getBgJpeg(), pr.getFgColorJpeg(),
                                    jbig2Page.getData(), jbig2Batch.getGlobalSym(),
                                    jbig2Page.getWidth(), jbig2Page.getHeight(),
                                    pr);
                        } else if (useMrc) {
                            outPage = assembler.addPage(output, source, i,
                                    pr.getBgJpeg(), pr.getFgColorJpeg(),
                                    pr.getMask(), pr);
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
                    var preserved = assembler.finishAssembly(output, source, outPages, usePdfa);
                    if (preserved.outlines() > 0)
                        System.out.println("  Bookmarks:  " + preserved.outlines() + " (preserved)");
                    if (preserved.annotations() > 0)
                        System.out.println("  Links:      " + preserved.annotations() + " (preserved)");
                    if (preserved.embeddedFiles() > 0)
                        System.out.println("  Attachments: " + preserved.embeddedFiles() + " (preserved)");
                    File tempOutput = File.createTempFile("mrcpdf-", ".pdf", outputFile.getParentFile());
                    try {
                        output.save(tempOutput);
                        Files.move(tempOutput.toPath(), outputFile.toPath(),
                                StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                    } catch (Exception e) {
                        tempOutput.delete();
                        throw e;
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

    private static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.0f KB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
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
