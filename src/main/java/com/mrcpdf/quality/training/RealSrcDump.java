package com.mrcpdf.quality.training;

import java.awt.Color;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;

import com.mrcpdf.model.TextBlock;

/**
 * Source-dump companion to {@link SynthSrcDump} that uses a <em>real base
 * PDF</em> instead of the AWT placeholder pages: every page of the base
 * document is rendered at 300 DPI, degraded through the same scanner tier
 * pipeline, and embedded into a plain PDF (one page per variant) plus a
 * page->variant map CSV with the same schema as the synthetic dump.
 *
 * <p>Ground truth for the measured-OCR label path
 * ({@link ReadabilityOcr#measure}) is extracted from the PDF's own text layer:
 * per-word bounding boxes from {@link PDFTextStripper} (PDF user space, scaled
 * to raster pixels, top-left origin) with confidences 55-99 drawn from the
 * per-page seed RNG, mirroring {@link SyntheticCorpus#renderPage}.
 *
 * <p>Variants are produced with the unchanged scanner pipeline
 * ({@link SyntheticCorpus#cleanScan}, {@link SyntheticCorpus#buildScannedPages})
 * so the measured score and label semantics match the synthetic corpus.  Each
 * variant is embedded into a 612x792 pt container with the base page fit-scaled
 * and centered (A4 -> letter, scale ~0.94), JPEG at the per-score quality; the
 * score banner is optional (off by default, real pages fill the top margin).
 *
 * <p>Usage: RealSrcDump (--base &lt;pdf&gt; | --base-list &lt;txt&gt;)
 *                        [--pages N] [--pages-per-doc N] [--min-words N]
 *                        [--max-words N] [--page-limit N] [--img-format jpeg|png]
 *                        [--seed S] [--out build/real-src.pdf]
 *                        [--map build/real-variant-map.csv] [--banner]
 * <p>Page count = selected base pages x (1 clean + (pages-1) degraded variants).
 *
 * <p>{@code --base-list} sweeps many base documents (see
 * {@code fetchRealBases}, which writes {@code build/real-bases/available.txt})
 * into one dump.  Every variant derived from the same base document shares one
 * {@code doc} id ({@code real0}, {@code real1}, ...) so
 * {@link Dataset#splitByDoc} holds out a whole document rather than leaking
 * font/layout/author across the split.  Note the pre-corpus dump used
 * {@code real<page>} as the id, which made every page its own document.
 *
 * <p>{@code --pages-per-doc N} turns on prose-dense page selection: for each
 * base document, pages whose text layer holds {@code --min-words}..{@code
 * --max-words} words (default 150..3000) are ranked by word count and the top
 * N are kept, in reading order.  This drops cover pages, tables of contents,
 * plates and table-only pages whose OCR labels would be vacuous.  Without
 * {@code --pages-per-doc} every page is dumped, which is the legacy
 * single-base behaviour.
 *
 * <p>{@code --img-format png} is a control for the label pipeline: PNG removes
 * the JPEG recompression step, so any readability loss it shows is attributable
 * to the scanner tier rather than the codec.
 */
public final class RealSrcDump {

    private static final float PAGE_W = 612;
    private static final float PAGE_H = 792;

    public static void main(String[] args) throws Exception {
        Path base = null;
        Path baseList = null;
        int pages = 20;
        int pagesPerDoc = 0;
        int minWords = 150;
        int maxWords = 3000;
        int pageLimit = 0;
        String imgFormat = "jpeg";
        long seed = 42L;
        Path out = Path.of("build/real-src.pdf");
        Path map = Path.of("build/real-variant-map.csv");
        boolean banner = false;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--base" -> base = Path.of(args[++i]);
                case "--base-list" -> baseList = Path.of(args[++i]);
                case "--pages" -> pages = Integer.parseInt(args[++i]);
                case "--pages-per-doc" -> pagesPerDoc = Integer.parseInt(args[++i]);
                case "--min-words" -> minWords = Integer.parseInt(args[++i]);
                case "--max-words" -> maxWords = Integer.parseInt(args[++i]);
                case "--page-limit" -> pageLimit = Integer.parseInt(args[++i]);
                case "--img-format" -> imgFormat = args[++i].toLowerCase(Locale.ROOT);
                case "--seed" -> seed = Long.parseLong(args[++i]);
                case "--out" -> out = Path.of(args[++i]);
                case "--map" -> map = Path.of(args[++i]);
                case "--banner" -> banner = true;
                default -> {
                    System.err.println("Unknown arg: " + args[i]);
                    System.err.println("Usage: RealSrcDump (--base <pdf> | --base-list <txt>) "
                            + "[--pages N] [--pages-per-doc N] [--min-words N] [--max-words N] "
                            + "[--page-limit N] [--img-format jpeg|png] [--seed S] "
                            + "[--out <pdf>] [--map <csv>] [--banner]");
                    return;
                }
            }
        }
        if (base == null && baseList == null) {
            System.err.println("Missing required --base <pdf> or --base-list <txt>");
            return;
        }
        if (base != null && baseList != null) {
            System.err.println("--base and --base-list are mutually exclusive");
            return;
        }
        if (pages < 2) {
            System.err.println("--pages must be >= 2 (clean + at least one degraded variant)");
            return;
        }
        if (minWords > maxWords) {
            System.err.println("--min-words must be <= --max-words");
            return;
        }
        if (!imgFormat.equals("jpeg") && !imgFormat.equals("png")) {
            System.err.println("--img-format must be jpeg or png");
            return;
        }
        List<Path> bases = baseList != null ? readBaseList(baseList) : List.of(base);
        if (bases.isEmpty()) {
            System.err.println("No base PDFs to dump");
            return;
        }

        StringBuilder csv = new StringBuilder("page,doc,variant,label,tier,score\n");
        int pageCount = 0;
        int basePageCount = 0;
        try (PDDocument src = new PDDocument()) {
            for (int d = 0; d < bases.size() && (pageLimit == 0 || basePageCount < pageLimit); d++) {
                Path path = bases.get(d);
                String docId = "real" + d;
                if (!Files.isReadable(path)) {
                    System.err.println("  WARN: unreadable base, skipping: " + path);
                    continue;
                }
                try (PDDocument baseDoc = Loader.loadPDF(path.toFile())) {
                    PDFRenderer renderer = new PDFRenderer(baseDoc);
                    List<int[]> selected = pagesPerDoc > 0
                            ? selectPages(baseDoc, pagesPerDoc, minWords, maxWords)
                            : allPages(baseDoc);
                    if (selected.isEmpty()) {
                        System.err.println("  WARN: " + docId + " (" + path.getFileName()
                                + ") has no page with " + minWords + ".." + maxWords
                                + " words; skipping");
                        continue;
                    }
                    System.out.println("  " + docId + " <- " + path.getFileName()
                            + " (" + baseDoc.getNumberOfPages() + " pages, "
                            + selected.size() + " selected)");
                    for (int[] sel : selected) {
                        if (pageLimit > 0 && basePageCount >= pageLimit) {
                            System.out.println("  --page-limit " + pageLimit
                                    + " reached, stopping after " + d + " docs");
                            break;
                        }
                        int p = sel[0];
                        basePageCount++;
                        long rngSeed = seed
                                ^ ((long) d * 0x9E3779B97F4A7C15L)
                                ^ ((long) p * 0xBF58476D1CE4E5B9L);
                        PDPage pd = baseDoc.getPage(p);
                        double pageW = pd.getMediaBox().getWidth();
                        double pageH = pd.getMediaBox().getHeight();

                        BufferedImage img = renderer.renderImageWithDPI(p, SyntheticCorpus.RENDER_DPI);
                        List<TextBlock> blocks = extractBlocks(baseDoc, p, pageW, pageH, rngSeed);
                        System.out.println("    " + docId + " p" + (p + 1) + ": render "
                                + img.getWidth() + "x" + img.getHeight() + " px, "
                                + blocks.size() + " ground-truth words");
                        if (blocks.isEmpty()) {
                            System.err.println("    WARN: " + docId + " p" + (p + 1)
                                    + " has no extractable text; readability labels will be vacuous");
                        }
                        SyntheticCorpus.Page page = new SyntheticCorpus.Page(img, blocks);

                        List<SyntheticCorpus.Variant> sheets = new ArrayList<>();
                        sheets.add(new SyntheticCorpus.Variant("clean", "clean",
                                SyntheticCorpus.cleanScan(page, rngSeed), 1.0, 0));
                        sheets.addAll(SyntheticCorpus.buildScannedPages(page, rngSeed, pages - 1));

                        for (SyntheticCorpus.Variant v : sheets) {
                            pageCount++;
                            embedVariant(src, v.image(), v.score(), banner, imgFormat);
                            csv.append(pageCount).append(',').append(docId).append(',')
                                    .append(v.name()).append(',').append(v.label()).append(',')
                                    .append(v.tier()).append(',')
                                    .append(String.format(Locale.ROOT, "%.3f", v.score())).append('\n');
                        }
                    }
                }
            }
            src.save(out.toFile());
        }
        Files.writeString(map, csv.toString(), StandardCharsets.US_ASCII);

        long[] counts = {0, 0};
        csv.toString().lines().skip(1).forEach(l -> {
            String[] c = l.split(",", 6);
            if (c.length >= 6) counts[Integer.parseInt(c[3])]++;
        });
        System.out.println("Wrote " + out.toAbsolutePath() + " ("
                + basePageCount + " base pages -> " + pageCount + " variant pages)");
        System.out.println("Wrote " + map.toAbsolutePath());
        System.out.printf("Label 0: %d, label 1 (skip MRC): %d (%d pages total)%n",
                counts[0], counts[1], pageCount);
    }

    private static List<Path> readBaseList(Path list) throws IOException {
        List<Path> out = new ArrayList<>();
        for (String line : Files.readAllLines(list, StandardCharsets.UTF_8)) {
            String t = line.trim();
            if (!t.isEmpty() && !t.startsWith("#")) {
                out.add(Path.of(t));
            }
        }
        return out;
    }

    /** Every page, as {@code {page, wordCount}} in reading order. */
    private static List<int[]> allPages(PDDocument doc) throws IOException {
        List<int[]> out = new ArrayList<>(doc.getNumberOfPages());
        for (int p = 0; p < doc.getNumberOfPages(); p++) {
            out.add(new int[] {p, countWords(doc, p)});
        }
        return out;
    }

    /**
     * Prose-dense page selection: keeps pages whose text layer holds
     * {@code minWords}..{@code maxWords} words, ranks them by word count, and
     * takes the top {@code perDoc} in reading order.  Word counts come from the
     * text layer only, so this pass never rasterizes a page it will discard.
     */
    private static List<int[]> selectPages(PDDocument doc, int perDoc, int minWords, int maxWords)
            throws IOException {
        List<int[]> band = new ArrayList<>();
        for (int p = 0; p < doc.getNumberOfPages(); p++) {
            int w = countWords(doc, p);
            if (w >= minWords && w <= maxWords) {
                band.add(new int[] {p, w});
            }
        }
        band.sort((a, b) -> b[1] - a[1]);
        List<int[]> chosen = new ArrayList<>(band.subList(0, Math.min(perDoc, band.size())));
        chosen.sort((a, b) -> a[0] - b[0]);
        return chosen;
    }

    private static int countWords(PDDocument doc, int pageIndex) throws IOException {
        PDFTextStripper s = new PDFTextStripper();
        s.setStartPage(pageIndex + 1);
        s.setEndPage(pageIndex + 1);
        StringWriter sw = new StringWriter();
        s.writeText(doc, sw);
        String t = sw.toString().trim();
        return t.isEmpty() ? 0 : t.split("\\s+").length;
    }

    /**
     * Extracts per-word ground-truth blocks for one base page.
     *
     * <p>The reference words come from {@link PDFTextStripper}'s assembled text
     * (which inserts word boundaries even for Chromium/Skia PDFs whose glyph
     * runs do not carry the space char as a separate {@link TextPosition} —
     * regrouping raw positions concatenates words).  Per-word boxes are taken
     * from {@link WordStripper} and aligned by reading position when the two
     * word counts agree; otherwise bbox is null (the measured-OCR label path
     * only reads {@link TextBlock#getWord}).
     *
     * <p>Package-private so {@link RealTrainDump} can recover the same reference
     * words (and boxes) for a base page instead of re-deriving them.
     */
    static List<TextBlock> extractBlocks(PDDocument doc, int pageIndex,
                                                 double pageW, double pageH, long seed)
            throws IOException {
        WordBoxStripper stripper = new WordBoxStripper();
        stripper.setStartPage(pageIndex + 1);
        stripper.setEndPage(pageIndex + 1);
        stripper.setSortByPosition(true);
        stripper.writeText(doc, new StringWriter());
        List<WordInfo> words = stripper.getResults();

        double sf = SyntheticCorpus.RENDER_DPI / 72.0;
        java.util.Random confRng = new java.util.Random(seed ^ 0xc0ffee);
        List<TextBlock> boxBlocks = new ArrayList<>(words.size());
        for (WordInfo w : words) {
            int x = (int) Math.round(w.x * sf);
            int y = (int) Math.round(w.y * sf);
            Rectangle rect = new Rectangle(x, y, Math.max(1, (int) Math.round(w.w * sf)),
                    Math.max(1, (int) Math.round(w.h * sf)));
            double conf = 55 + confRng.nextInt(45);
            boxBlocks.add(new TextBlock(w.text, rect, conf));
        }

        // The box stripper's gap-based segmentation shatters letter-spaced text
        // ("PRELIMINARY" -> 14 single characters), so the assembled text is
        // authoritative for the word sequence and the boxes are aligned onto it.
        List<String> assembled = assembledWords(doc, pageIndex);
        List<TextBlock> reference = ReferenceAnchor.align(assembled, boxBlocks);
        if (!ReferenceAnchor.isUsable(reference)) {
            System.err.printf("  !! page %d: only %d reference words (< %d); WER on this "
                    + "page is not meaningful and will be reported as NaN%n",
                    pageIndex + 1, reference.size(), ReferenceAnchor.MIN_REFERENCE_WORDS);
        }
        return reference;
    }

    /**
     * The assembled stripper words for one page, in reading order.  This is the
     * reference's word segmentation: it comes from the producer's own word
     * boundaries rather than from inter-glyph gaps.
     */
    static List<String> assembledWords(PDDocument doc, int pageIndex) throws IOException {
        PDFTextStripper s = new PDFTextStripper();
        s.setStartPage(pageIndex + 1);
        s.setEndPage(pageIndex + 1);
        s.setSortByPosition(true);
        StringWriter sw = new StringWriter();
        s.writeText(doc, sw);
        List<String> out = new ArrayList<>();
        for (String w : sw.toString().trim().split("\\s+")) {
            if (!w.isEmpty()) out.add(w);
        }
        return out;
    }

    /** Embeds one variant into a 612x792 pt container, fit-scaled and centered. */
    private static void embedVariant(PDDocument doc, BufferedImage image, double score,
                                     boolean banner, String imgFormat) throws IOException {
        PDPage pdPage = new PDPage(new PDRectangle(PAGE_W, PAGE_H));
        doc.addPage(pdPage);
        try (PDPageContentStream cs = new PDPageContentStream(doc, pdPage)) {
            float imgWpt = image.getWidth() * 72f / SyntheticCorpus.RENDER_DPI;
            float imgHpt = image.getHeight() * 72f / SyntheticCorpus.RENDER_DPI;
            float scale = Math.min(PAGE_W / imgWpt, PAGE_H / imgHpt);
            float drawW = imgWpt * scale;
            float drawH = imgHpt * scale;
            float dx = (PAGE_W - drawW) / 2f;
            float dy = (PAGE_H - drawH) / 2f;
            if (imgFormat.equals("png")) {
                PDImageXObject img = PDImageXObject.createFromByteArray(doc, encodePng(image), "synthetic");
                cs.drawImage(img, dx, dy, drawW, drawH);
            } else {
                JpegCodec.embed(doc, cs, image, SyntheticCorpus.LABEL_JPEG_QUALITY,
                        dx, dy, drawW, drawH);
            }
            if (banner) {
                drawScoreOverlay(cs, score);
            }
        }
    }

    private static byte[] encodePng(BufferedImage image) throws IOException {
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream(1 << 16);
        javax.imageio.ImageIO.write(image, "png", baos);
        return baos.toByteArray();
    }

    /** Draws {@code score = X.XXX} in a small white band at the top margin. */
    private static void drawScoreOverlay(PDPageContentStream cs, double score) throws IOException {
        String text = "score = " + String.format(Locale.ROOT, "%.3f", score);
        PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
        float fontSize = 7;
        float textWidth = font.getStringWidth(text) / 1000f * fontSize;
        float x = (PAGE_W - textWidth) / 2f;
        float baseline = PAGE_H - 8;
        cs.setNonStrokingColor(Color.WHITE);
        cs.addRect(x - 3, baseline - 2.5f, textWidth + 7, 10);
        cs.fill();
        cs.setNonStrokingColor(Color.BLACK);
        cs.beginText();
        cs.setFont(font, fontSize);
        cs.newLineAtOffset(x, baseline);
        cs.showText(text);
        cs.endText();
    }

    // ── Per-word extraction (machinery from BboxComparator) ──

    /**
     * Derives reference words <em>and</em> their boxes from a single
     * {@link PDFTextStripper#writeString} stream, so the two can never disagree.
     *
     * <p>The original two-pass approach read reference text from the stripper's
     * assembled output and boxes from a separate pass, keeping boxes only when
     * the two word counts happened to be equal.  They rarely were, and when they
     * were the order was wrong as well: the box pass sorted characters by
     * ascending {@code y} in bottom-up user space, which is the reverse of
     * reading order, and it had no notion of columns, so a two-column page
     * merged glyphs from both columns into single 1300px-wide "words".  Pages
     * that lost the count match then silently fell back to image-only features.
     *
     * <p>Hooking {@code writeString} avoids all of that: the base class calls it
     * once per text chunk <em>after</em> {@code setSortByPosition} has ordered
     * the page, and hands over exactly the glyphs belonging to that chunk.  Words
     * and boxes therefore come from the same characters, in the base class's
     * reading order.  Chunks are still split on wide gaps, which is what
     * recovers word boundaries in producers (Chromium/Skia) that do not emit
     * spaces as their own {@link TextPosition}.
     */
    private static final class WordBoxStripper extends PDFTextStripper {
        /** Inter-glyph gap, as a fraction of glyph height, that starts a new word. */
        private static final float SPACE_RATIO = 0.20f;
        /** Glyph box above/below the baseline, as a fraction of font height. */
        private static final float ASCENT_RATIO = 0.80f;
        private static final float DESCENT_RATIO = 0.20f;

        private final List<WordInfo> words = new ArrayList<>();

        WordBoxStripper() throws IOException {
            super();
        }

        @Override
        protected void writeString(String text, List<TextPosition> positions) throws IOException {
            if (positions == null || positions.isEmpty()) return;
            List<TextPosition> word = new ArrayList<>();
            for (TextPosition p : positions) {
                String u = p.getUnicode();
                if (u == null || u.isEmpty()) continue;
                char c = u.charAt(0);
                if (c == '\uFFFC' || c < 32 || c == 127) continue;
                if (Character.isWhitespace(c) || c == '\u00A0' || c == '\u2007' || c == '\u202F') {
                    flush(word);
                    word = new ArrayList<>();
                    continue;
                }
                if (!word.isEmpty() && gapExceedsWord(p, word.get(word.size() - 1))) {
                    flush(word);
                    word = new ArrayList<>();
                }
                word.add(p);
            }
            flush(word);
        }

        private static boolean gapExceedsWord(TextPosition cur, TextPosition prev) {
            float h = Math.max(1f, prev.getHeightDir());
            float gap = cur.getXDirAdj() - (prev.getXDirAdj() + prev.getWidthDirAdj());
            return gap > SPACE_RATIO * h;
        }

        private void flush(List<TextPosition> word) {
            if (word.isEmpty()) return;
            StringBuilder sb = new StringBuilder();
            float minX = Float.MAX_VALUE;
            float minY = Float.MAX_VALUE;
            float maxX = -Float.MAX_VALUE;
            float maxY = -Float.MAX_VALUE;
            for (TextPosition p : word) {
                sb.append(p.getUnicode());
                // getYDirAdj() is the BASELINE, and glyph ink sits mostly above
                // it, so a box anchored there clips the whole x-height and keeps
                // only descenders.  Span ascent..descent instead.
                float fh = Math.max(1f, p.getHeightDir());
                float baseline = p.getYDirAdj();
                minX = Math.min(minX, p.getXDirAdj());
                minY = Math.min(minY, baseline - ASCENT_RATIO * fh);
                maxX = Math.max(maxX, p.getXDirAdj() + p.getWidthDirAdj());
                maxY = Math.max(maxY, baseline + DESCENT_RATIO * fh);
            }
            words.add(new WordInfo(sb.toString(), minX, minY, maxX - minX, maxY - minY));
        }

        List<WordInfo> getResults() {
            return words;
        }
    }

    private record CharInfo(char c, float x, float y, float w, float h) {
    }

    /** Word bounding box in PDF user space (bottom-left origin). */
    private record WordInfo(String text, float x, float y, float w, float h) {
    }

    private RealSrcDump() {
    }
}