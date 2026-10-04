package com.mrcpdf.tools;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.stream.Stream;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * Computes the OCR score {@code S_ocr} for a page from its Tesseract TSV
 * sidecar plus the searchable PDF that Tesseract wrote alongside it.
 *
 * <p>The score describes what the OCR engine managed to read: four terms over
 * the word confidences (mean, low tail, dispersion, lexical validity), gated by
 * how many words the engine found at all.  It is a standalone extractor — the
 * constants and arithmetic are mirrored from {@code MrcReadiness}, which is
 * where they were calibrated, but nothing here calls it, so the tool can be
 * handed a raw Tesseract output folder without dragging in the rest of the
 * pipeline.  Change one and you must change the other.
 *
 * <p>The score was calibrated against the <em>pixel</em> dimensions of the OCR
 * raster, so the page extent must be in pixels too.  The PDF's own page box is
 * in points (a 300-DPI letter page is 612x792 pt while its TSV coordinates run
 * to 2550x3301 px), so points would reject almost every word box.  The raster
 * pixel size is recovered from the image the PDF embeds, which is exactly the
 * bitmap Tesseract read.
 */
@Command(
    name = "tesseract-ocr-score",
    description = "Compute the Tesseract OCR score (S_ocr) for one page or a folder of pages",
    mixinStandardHelpOptions = true
)
public final class TesseractOcrScore implements Callable<Integer> {

    @Parameters(index = "0", arity = "0..1", paramLabel = "TSV",
                description = "Tesseract TSV sidecar for a single page")
    private Path tsv;

    @Parameters(index = "1", arity = "0..1", paramLabel = "PDF",
                description = "Searchable PDF for the same page (supplies the raster extent)")
    private Path pdf;

    @Option(names = "--dir", paramLabel = "FOLDER",
            description = "Batch mode: folder of same-named <page>.pdf + <page>.tsv pairs")
    private Path dir;

    @Option(names = "--out", paramLabel = "FILE",
            description = "Batch output file (default: <folder>/tesseract-ocr-scores.txt)")
    private Path out;

    @Option(names = "--page", paramLabel = "N",
            description = "PDF page to use (default: page_num from the TSV, else 1)")
    private Integer page;

    @Option(names = "--dpi", paramLabel = "N",
            description = "DPI for the page-box fallback when a page embeds no image (default: 300)")
    private int dpi = 300;

    // ── S_ocr constants, mirroring MrcReadiness ──────────────────────────────
    private static final double O_CONF_MEAN = 0.35;
    private static final double O_CONF_TAIL = 0.25;
    private static final double O_DISPERSION = 0.20;
    private static final double O_LEX = 0.20;
    /** Usable boxes at which the evidence gate reaches full credit. */
    private static final int EVIDENCE_MIN_BOXES = 50;
    private static final double DISPERSION_REF = 0.5;
    private static final double EPS = 1e-9;

    /** One TSV word row. {@code conf} is a float in the TSV spec. */
    record Word(String text, int x, int y, int width, int height, double conf, int page) {
    }

    /** Parsed word rows plus the page number the first word row belongs to. */
    record ParsedTsv(List<Word> words, int pageNum) {

        /**
         * The word rows belonging to {@code page}, in reading order.  A TSV
         * written from a multi-page PDF carries a row per page, and those rows
         * must not be pooled: the score for one page is measured against that
         * page's extent, so another page's words would be scored off-page and
         * their confidences folded into this page's statistics.
         */
        List<Word> wordsOnPage(int page) {
            return words.stream().filter(w -> w.page() == page).toList();
        }
    }

    @Override
    public Integer call() {
        try {
            return dir != null ? runBatch() : runSingle();
        } catch (IOException | IllegalArgumentException e) {
            System.err.println("error: " + e.getMessage());
            return 1;
        }
    }

    public static void main(String[] args) {
        System.exit(new CommandLine(new TesseractOcrScore()).execute(args));
    }

    // ── Modes ────────────────────────────────────────────────────────────────

    private int runSingle() throws IOException {
        if (tsv == null || pdf == null) {
            System.err.println("error: provide <page.tsv> <page.pdf>, or --dir <folder>");
            return 2;
        }
        if (!Files.isRegularFile(tsv)) {
            System.err.println("error: no such TSV: " + tsv);
            return 2;
        }
        if (!Files.isRegularFile(pdf)) {
            System.err.println("error: no such PDF: " + pdf);
            return 2;
        }
        System.out.printf(Locale.ROOT, "%.6f%n", scorePage(tsv, pdf, page, dpi));
        return 0;
    }

    private int runBatch() throws IOException {
        if (tsv != null || pdf != null) {
            System.err.println("error: --dir cannot be combined with positional TSV/PDF files");
            return 2;
        }
        if (!Files.isDirectory(dir)) {
            System.err.println("error: not a directory: " + dir);
            return 2;
        }
        List<Path> pdfs = discoverPdfs(dir);
        if (pdfs.isEmpty()) {
            System.err.println("error: no .pdf files in " + dir);
            return 2;
        }
        Path target = out != null ? out : dir.resolve("tesseract-ocr-scores.txt");

        // Pair every PDF with its TSV up front so a missing half fails before
        // any output is written, keeping line N paired with page N.
        List<String> lines = new ArrayList<>();
        for (Path pagePdf : pdfs) {
            String base = baseName(pagePdf);
            Path pageTsv = dir.resolve(base + ".tsv");
            if (!Files.isRegularFile(pageTsv)) {
                throw new IOException("missing TSV for " + pagePdf.getFileName()
                        + " (expected " + pageTsv.getFileName() + ")");
            }
            double score = scorePage(pageTsv, pagePdf, page, dpi);
            lines.add(base + "\t" + String.format(Locale.ROOT, "%.6f", score));
        }
        Files.write(target, lines);
        System.err.printf("Scored %d page(s) -> %s%n", lines.size(), target);
        return 0;
    }

    // ── Orchestration ────────────────────────────────────────────────────────

    /**
     * Scores one page from its TSV and the PDF page supplying the raster extent.
     * The PDF is used only for the pixel dimensions; all quality comes from the
     * TSV word confidences.
     */
    public static double scorePage(Path tsvPath, Path pdfPath, Integer pageOverride, int dpi)
            throws IOException {
        ParsedTsv parsed = parseTsv(Files.readString(tsvPath));
        int pageNum = pageOverride != null ? pageOverride : parsed.pageNum();
        int[] extent = pageExtentPixels(pdfPath, pageNum - 1, dpi);
        return score(parsed.wordsOnPage(pageNum), extent[0], extent[1]);
    }

    // ── Score ────────────────────────────────────────────────────────────────

    /**
     * Combines the four OCR terms over the usable boxes, gated by evidence.
     *
     * <p>A page with no usable boxes scores {@code 0.0}: an engine that found
     * nothing legible is the worst case, not an unknown one.
     */
    static double score(List<Word> words, int w, int h) {
        List<Word> usable = usableBoxes(words, w, h);
        double[] m = mOcr(usable);
        double weighted = O_CONF_MEAN * m[0] + O_CONF_TAIL * m[1]
                + O_DISPERSION * m[2] + O_LEX * m[3];
        return clamp01(weighted * mEvidence(usable.size()));
    }

    /** OCR words with boxes that are plausibly glyph-local on a {@code w x h} page. */
    static List<Word> usableBoxes(List<Word> words, int w, int h) {
        List<Word> out = new ArrayList<>();
        if (words == null) return out;
        for (Word word : words) {
            int bw = word.width();
            int bh = word.height();
            if (bw < 4 || bh < 3) continue;
            if (word.x() < 0 || word.y() < 0 || word.x() + bw > w || word.y() + bh > h) continue;
            if (bw > 0.8 * w || bh > 0.8 * h) continue;
            out.add(word);
        }
        return out;
    }

    /** @return {@code {mConfMean, mConfTail, mDispersion, mLex}} */
    private static double[] mOcr(List<Word> words) {
        if (words.isEmpty()) return new double[]{0.0, 0.0, 0.0, 0.0};
        int n = words.size();
        double[] conf = new double[n];
        double sum = 0;
        int valid = 0;
        for (int i = 0; i < n; i++) {
            conf[i] = Math.max(0.0, Math.min(100.0, words.get(i).conf()));
            sum += conf[i];
            if (isLexicallyValid(words.get(i).text())) valid++;
        }
        double mean = sum / n;

        double[] sorted = conf.clone();
        Arrays.sort(sorted);
        double tail = sorted[Math.max(0, (int) Math.floor(0.10 * (n - 1)))];

        double sq = 0;
        for (double c : conf) sq += (c - mean) * (c - mean);
        double std = n > 1 ? Math.sqrt(sq / (n - 1)) : 0.0;
        double cv = std / (mean + EPS);

        return new double[]{
                clamp01(mean / 100.0),
                clamp01(tail / 100.0),
                clamp01(1.0 - Math.min(1.0, cv / DISPERSION_REF)),
                (double) valid / n
        };
    }

    /** At least two characters, more than 80% alphanumeric: rejects punctuation soup. */
    private static boolean isLexicallyValid(String word) {
        if (word == null) return false;
        int total = 0;
        int alnum = 0;
        for (int i = 0; i < word.length(); i++) {
            char c = word.charAt(i);
            if (Character.isWhitespace(c)) continue;
            total++;
            if (Character.isLetterOrDigit(c)) alnum++;
        }
        return total >= 2 && (double) alnum / total > 0.80;
    }

    private static double mEvidence(int boxCount) {
        if (boxCount <= 0) return 0.0;
        return Math.min(1.0, boxCount / (double) EVIDENCE_MIN_BOXES);
    }

    private static double clamp01(double v) {
        if (Double.isNaN(v)) return 0.0;
        return Math.max(0.0, Math.min(1.0, v));
    }

    // ── TSV parsing ──────────────────────────────────────────────────────────

    /** Parses Tesseract TSV content into word rows, following the header layout. */
    static ParsedTsv parseTsv(String tsv) {
        List<Word> words = new ArrayList<>();
        if (tsv == null || tsv.isEmpty()) return new ParsedTsv(words, 1);

        int cLevel = 0, cPage = 1, cLeft = 6, cTop = 7, cWidth = 8, cHeight = 9, cConf = 10, cText = 11;
        boolean sawHeader = false;
        int firstPage = -1;

        for (String rawLine : tsv.split("\n")) {
            String line = rawLine.endsWith("\r") ? rawLine.substring(0, rawLine.length() - 1) : rawLine;
            if (line.isBlank()) continue;

            String[] f = line.split("\t", -1);
            if (!sawHeader) {
                if (f.length > 11 && "level".equals(f[0].trim()) && "conf".equals(f[10].trim())) {
                    sawHeader = true;
                    for (int i = 0; i < f.length; i++) {
                        switch (f[i].trim()) {
                            case "level" -> cLevel = i;
                            case "page_num" -> cPage = i;
                            case "left" -> cLeft = i;
                            case "top" -> cTop = i;
                            case "width" -> cWidth = i;
                            case "height" -> cHeight = i;
                            case "conf" -> cConf = i;
                            case "text" -> cText = i;
                            default -> { }
                        }
                    }
                }
                continue;
            }

            int needed = Math.max(cText, Math.max(cConf, Math.max(cPage,
                    Math.max(cLevel, Math.max(cLeft, Math.max(cTop,
                    Math.max(cWidth, cHeight)))))));
            if (f.length <= needed) continue;
            if (parseInt(f[cLevel]) != 5) continue;

            String word = f[cText].trim();
            if (word.isEmpty()) continue;
            // Tesseract writes conf as a float, so this must not be parsed as an
            // int: "92.4" would fail to parse, read as -1, and drop the row,
            // silently scoring the page zero.
            double conf = parseDouble(f[cConf]);
            if (conf < 0) continue;

            int x = parseInt(f[cLeft]);
            int y = parseInt(f[cTop]);
            int w = parseInt(f[cWidth]);
            int h = parseInt(f[cHeight]);
            if (w <= 0 || h <= 0) continue;

            int page = parseInt(f[cPage]);
            words.add(new Word(word, x, y, w, h, conf, page));
            if (firstPage < 0) firstPage = page;
        }
        if (!sawHeader) {
            throw new IllegalArgumentException("no TSV header row; not a Tesseract TSV file");
        }
        return new ParsedTsv(words, firstPage > 0 ? firstPage : 1);
    }

    private static int parseInt(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static double parseDouble(String s) {
        try {
            return Double.parseDouble(s.trim());
        } catch (NumberFormatException e) {
            return -1.0;
        }
    }

    // ── PDF page extent ──────────────────────────────────────────────────────

    /**
     * Pixel dimensions of the page's raster, taken from the largest embedded
     * image; falls back to the page box scaled by {@code dpi} when there is
     * none.  The embedded image is the bitmap Tesseract read, so its pixels are
     * the coordinate space the TSV boxes live in.
     */
    static int[] pageExtentPixels(Path pdf, int pageIndex, int dpi) throws IOException {
        if (pageIndex < 0) throw new IllegalArgumentException("page must be >= 1");
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            if (pageIndex >= doc.getNumberOfPages()) {
                throw new IllegalArgumentException("PDF has " + doc.getNumberOfPages()
                        + " page(s); requested page " + (pageIndex + 1));
            }
            PDPage page = doc.getPage(pageIndex);

            PDImageXObject best = null;
            long bestArea = -1;
            PDResources resources = page.getResources();
            if (resources != null) {
                for (COSName name : resources.getXObjectNames()) {
                    PDXObject xo = resources.getXObject(name);
                    if (xo instanceof PDImageXObject img) {
                        long area = (long) img.getWidth() * img.getHeight();
                        if (area > bestArea) {
                            bestArea = area;
                            best = img;
                        }
                    }
                }
            }
            if (best != null) return new int[]{best.getWidth(), best.getHeight()};

            PDRectangle box = page.getCropBox() != null ? page.getCropBox() : page.getMediaBox();
            return new int[]{
                    (int) Math.round(box.getWidth() / 72.0 * dpi),
                    (int) Math.round(box.getHeight() / 72.0 * dpi)};
        }
    }

    // ── Batch discovery ──────────────────────────────────────────────────────

    private static List<Path> discoverPdfs(Path folder) throws IOException {
        List<Path> pdfs = new ArrayList<>();
        try (Stream<Path> stream = Files.list(folder)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> hasExtension(p, ".pdf"))
                    .forEach(pdfs::add);
        }
        pdfs.sort(Comparator.comparing(TesseractOcrScore::baseName, TesseractOcrScore::compareNatural));
        return pdfs;
    }

    private static boolean hasExtension(Path p, String ext) {
        return p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(ext);
    }

    private static String baseName(Path p) {
        String name = p.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    /** Digit-aware comparison so {@code p_2} sorts before {@code p_10}. */
    private static int compareNatural(String a, String b) {
        int i = 0, j = 0;
        while (i < a.length() && j < b.length()) {
            char ca = a.charAt(i);
            char cb = b.charAt(j);
            if (Character.isDigit(ca) && Character.isDigit(cb)) {
                int i0 = i, j0 = j;
                while (i < a.length() && Character.isDigit(a.charAt(i))) i++;
                while (j < b.length() && Character.isDigit(b.charAt(j))) j++;
                int cmp = compareNumericRuns(a.substring(i0, i), b.substring(j0, j));
                if (cmp != 0) return cmp;
            } else {
                int cmp = Character.compare(ca, cb);
                if (cmp != 0) return cmp;
                i++;
                j++;
            }
        }
        return Integer.compare(a.length() - i, b.length() - j);
    }

    private static int compareNumericRuns(String x, String y) {
        String xs = stripLeadingZeros(x);
        String ys = stripLeadingZeros(y);
        if (xs.length() != ys.length()) return Integer.compare(xs.length(), ys.length());
        return xs.compareTo(ys);
    }

    private static String stripLeadingZeros(String s) {
        int i = 0;
        while (i < s.length() - 1 && s.charAt(i) == '0') i++;
        return s.substring(i);
    }
}
