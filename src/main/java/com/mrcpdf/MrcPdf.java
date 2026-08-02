package com.mrcpdf;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
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
import org.apache.pdfbox.text.TextPosition;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import com.mrcpdf.model.PageResult;
import com.mrcpdf.model.SegmentedImage;
import com.mrcpdf.model.TextBlock;
import com.mrcpdf.pipeline.ImageSegmenter;
import com.mrcpdf.pipeline.JBIG2Compressor;
import com.mrcpdf.pipeline.PDFAssembler;
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

    @Option(names = {"--pdfa"}, description = "Enable PDF/A-2b output (XMP metadata, sRGB OutputIntent)")
    private Boolean pdfa;

    @Option(names = {"--threads"}, description = "Worker threads for page preparation (default: available processors)")
    private Integer threads;

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

            float resolvedDpi = dpi != null ? dpi
                    : (float) settings.getDouble("rendering.dpi", 300);
            double resolvedBgScale = bgScale != null ? bgScale
                    : settings.getDouble("pipeline.mrc.backgroundScale", 0.33);
            float resolvedJpegQuality = jpegQuality != null ? jpegQuality
                    : (float) settings.getDouble("pipeline.mrc.jpegQuality", 0.50);

            boolean useMrc = settings.getBoolean("pipeline.mrc.enabled", true);
            boolean usePdfa = pdfa != null ? pdfa : settings.getBoolean("pdf.pdfa.enabled", false);

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
                    settings.getInt("segmenter.inpaintRadius", 3)
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

            runPipeline(inputFile, resolvedOutput, segmenter, compressor, assembler,
                    useMrc, usePdfa, resolvedDpi, workerThreads, resolvedFontPath);
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
                             int workerThreads, String fontPath) throws IOException {
        String inputName = inputFile.getName().replaceAll("\\.[^.]+$", "");
        File tempDir = new File("temp/" + inputName + "-" + System.nanoTime());
        tempDir.mkdirs();

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
            List<PageResult> results = new ArrayList<>(pageCount);
            int[] imgWidths = new int[pageCount];
            int[] imgHeights = new int[pageCount];
            AtomicInteger threadCounter = new AtomicInteger(1);
            ExecutorService executor = Executors.newFixedThreadPool(workerThreads,
                    r -> new Thread(r, "prep-" + threadCounter.getAndIncrement()));
            try {
                List<Future<PageResult>> futures = new ArrayList<>(pageCount);
                long pipelineStart = System.nanoTime();
                String perPageFmt = "    [prep-%d] page %d: %4.1fs%n";

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
                            imgWidths[pageIdx] = page.getWidth();
                            imgHeights[pageIdx] = page.getHeight();

                            // Segment (background + foreground mask) or use raw page
                            BufferedImage background;
                            if (useMrc) {
                                SegmentedImage seg = segmenter.segment(page);
                                background = seg.getCleanedBackground();
                                ImageIO.write(seg.getForegroundMask(), "bmp",
                                        new File(tempDir, "mask-" + pageIdx + ".bmp"));
                            } else {
                                background = page;
                            }
                            ImageIO.write(background, "bmp",
                                    new File(tempDir, "bg-" + pageIdx + ".bmp"));

                            // Extract existing text (no OCR — source must be searchable)
                            PageResult r = TextPositionCollector.extractPage(source, pageIdx, dpi,
                                    page.getWidth(), page.getHeight());
                            double elapsed = (System.nanoTime() - localStart) / 1e9;
                            double cumulative = (System.nanoTime() - pipelineStart) / 1e9;
                            System.out.printf(perPageFmt, pageIdx + 1, pageIdx + 1,
                                    elapsed, cumulative);
                            return r;
                        } finally {
                            semaphore.release();
                        }
                    }));
                }

                // Shutdown executor (no more submissions)
                executor.shutdown();

                // Collect results in page order
                try {
                    for (Future<PageResult> f : futures) {
                        results.add(f.get());
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

                // ── Font resolution for the invisible text layer ──
                resolveTextFont(assembler, fontPath, results);

                // ── JBIG2 batch compression (shared dictionary across all pages) ──
                JBIG2Compressor.BatchResult jbig2Batch = null;
                if (useMrc) {
                    long t0 = System.nanoTime();
                    System.out.println("  Batch JBIG2 compression...");
                    jbig2Batch = compressor.compressAllFromDir(tempDir, pageCount, imgWidths, imgHeights);
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

                    for (int i = 0; i < pageCount; i++) {
                        BufferedImage bg = ImageIO.read(new File(tempDir, "bg-" + i + ".bmp"));

                        PDPage outPage;
                        if (jbig2Batch != null && jbig2Batch.getGlobalSym().length > 0) {
                            JBIG2Compressor.CompressionResult pageData = jbig2Batch.getPages().get(i);
                            outPage = assembler.addPageJbig2(output, source, i, bg,
                                    pageData.getData(), jbig2Batch.getGlobalSym(),
                                    pageData.getWidth(), pageData.getHeight(),
                                    results.get(i));
                        } else if (useMrc) {
                            // CCITT G4 foreground (no shared JBIG2 available)
                            BufferedImage mask = ImageIO.read(new File(tempDir, "mask-" + i + ".bmp"));
                            outPage = assembler.addPage(output, source, i, bg, mask, results.get(i));
                        } else {
                            outPage = assembler.addPage(output, source, i, bg, null, results.get(i));
                        }

                        outPages.add(outPage);
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
     * Custom PDFTextStripper that captures per-character positions from the
     * source PDF's existing text layer, then assembles word-level TextBlock
     * entries.  PDF coordinates are converted to page-image pixel coordinates
     * (top-left origin, y increasing downward).
     */
    private static class TextPositionCollector extends PDFTextStripper {
        private final List<CharPos> chars;

        TextPositionCollector() throws IOException {
            super();
            setSortByPosition(true);
            chars = new ArrayList<>();
        }

        static PageResult extractPage(PDDocument doc, int pageIdx, float dpi,
                                      int imgW, int imgH) throws IOException {
            TextPositionCollector c = new TextPositionCollector();
            c.setStartPage(pageIdx + 1);
            c.setEndPage(pageIdx + 1);
            c.writeText(doc, new StringWriter());
            // imgW/imgH are the dimensions of the rendered background image
            // (which already includes any page rotation swap). Using the actual
            // rendered dimensions keeps the text-layer scale factors consistent
            // with the background regardless of rotation or rounding.
            return new PageResult(pageIdx, imgW, imgH, c.buildWordBlocks(dpi));
        }

        @Override
        protected void processTextPosition(TextPosition text) {
            String ch = text.getUnicode();
            if (ch == null || ch.isEmpty()) return;
            if (ch.chars().allMatch(Character::isWhitespace)) return;
            float charH = text.getHeight() > 0 ? text.getHeight() : text.getFontSizeInPt();
            // Keep the full TextPosition as one atomic entry — use the bounding
            // box PDFBox provides rather than splitting into individual characters
            // with guessed uniform widths, which causes horizontal misalignment.
            chars.add(new CharPos(ch, text.getX(), text.getY(), text.getWidth(), charH));
        }

        private List<TextBlock> buildWordBlocks(float dpi) {
            List<TextBlock> blocks = new ArrayList<>();
            if (chars.isEmpty()) return blocks;

            // Step 1: Cluster characters into visual lines.
            // Different runs may assign slightly different y values to characters
            // on the same visual line, so sort by y first and merge nearby y values.
            chars.sort((a, b) -> Float.compare(a.y, b.y));

            List<List<CharPos>> lines = new ArrayList<>();
            List<CharPos> currentLine = new ArrayList<>();
            float[] heights = new float[chars.size()];
            for (int i = 0; i < chars.size(); i++) heights[i] = chars.get(i).height;
            java.util.Arrays.sort(heights);
            float pageMedianHeight = heights[heights.length / 2];

            // Anchor each line to the RUNNING MEAN baseline of its characters,
            // not a fixed first-char anchor: a slightly raised first glyph (e.g. a
            // superscript) no longer forces the rest of the line to split off, and
            // slowly drifting baselines (curved scans) stay one line. The threshold
            // is adaptive — the larger of the page-wide median and the current
            // line's mean height — so smaller sub/superscript glyphs merge into
            // their line while genuinely distant lines still split.
            float lineMeanY = 0f;
            float lineMeanHeight = 0f;
            int lineCount = 1;
            for (CharPos cp : chars) {
                if (currentLine.isEmpty()) {
                    // First char of a line: establishes its baseline anchor.
                    lineMeanY = cp.y;
                    lineMeanHeight = cp.height;
                    lineCount = 1;
                } else {
                    float threshold = 0.5f * Math.max(pageMedianHeight, lineMeanHeight);
                    if (Math.abs(cp.y - lineMeanY) > threshold) {
                        lines.add(currentLine);
                        currentLine = new ArrayList<>();
                        lineMeanY = cp.y;
                        lineMeanHeight = cp.height;
                        lineCount = 1;
                    } else {
                        lineMeanY = (lineMeanY * lineCount + cp.y) / (lineCount + 1);
                        lineMeanHeight = (lineMeanHeight * lineCount + cp.height) / (lineCount + 1);
                        lineCount++;
                    }
                }
                currentLine.add(cp);
            }
            if (!currentLine.isEmpty()) {
                lines.add(currentLine);
            }

            // Step 2: Within each line, sort by x then split into words by gap.
            for (List<CharPos> line : lines) {
                line.sort((a, b) -> Float.compare(a.x, b.x));

                List<CharPos> word = new ArrayList<>();
                word.add(line.get(0));
                for (int i = 1; i < line.size(); i++) {
                    CharPos prev = word.get(word.size() - 1);
                    CharPos cur = line.get(i);
                    float gap = cur.x - (prev.x + prev.width);
                    float gapThreshold = Math.max(prev.height * 0.3f, 2.0f);
                    if (gap > gapThreshold) {
                        blocks.add(toBlock(word, dpi));
                        word = new ArrayList<>();
                    }
                    word.add(cur);
                }
                if (!word.isEmpty()) {
                    blocks.add(toBlock(word, dpi));
                }
            }
            return blocks;
        }

        private TextBlock toBlock(List<CharPos> word, float dpi) {
            StringBuilder sb = new StringBuilder();
            float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE;
            float maxX = Float.MIN_VALUE, maxY = Float.MIN_VALUE;
            float scale = dpi / 72f;
            List<float[]> charPosList = new ArrayList<>();
            for (CharPos cp : word) {
                sb.append(cp.text);
                minX = Math.min(minX, cp.x);
                minY = Math.min(minY, cp.y);
                maxX = Math.max(maxX, cp.x + cp.width);
                maxY = Math.max(maxY, cp.y + cp.height);
                charPosList.add(new float[]{cp.x * scale, cp.y * scale,
                        cp.width * scale, cp.height * scale});
            }
            int px = Math.round(minX * scale);
            // bbox.y = topmost baseline in image pixels (top-left origin, y increases
            // downward); charPositions are stored in the same pixel units so that
            // baselineRatio() compares like with like.
            int py = Math.round(minY * scale);
            int pw = Math.round((maxX - minX) * scale);
            int ph = Math.round((maxY - minY) * scale);
            return new TextBlock(sb.toString(),
                    new java.awt.Rectangle(px, py, Math.max(1, pw), Math.max(1, ph)), 100.0,
                    charPosList);
        }

        private static class CharPos {
            final String text;
            final float x, y, width, height;
            CharPos(String text, float x, float y, float width, float height) {
                this.text = text; this.x = x; this.y = y; this.width = width; this.height = height;
            }
        }
    }

    private static void deleteDir(File dir) {
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isDirectory()) {
                    deleteDir(f);
                }
                f.delete();
            }
        }
        dir.delete();
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
