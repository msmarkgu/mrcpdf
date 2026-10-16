package com.mrcpdf.tools;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.JPEGFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import picocli.CommandLine;

class TesseractOcrScoreTest {

    private static final String HEADER =
            "level\tpage_num\tblock_num\tpar_num\tline_num\tword_num\tleft\ttop\twidth\theight\tconf\ttext";

    // ── Score math ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("fifty perfect words reach the evidence floor and score exactly one")
    void densePerfectWordsScoreOne() {
        TesseractOcrScore.ParsedTsv parsed = TesseractOcrScore.parseTsv(tsv(50));
        assertEquals(1.0, TesseractOcrScore.score(parsed.words(), 200, 200), 1e-12);
    }

    @Test
    @DisplayName("with no words the evidence gate drives the score to zero")
    void noWordsScoresZero() {
        assertEquals(0.0, TesseractOcrScore.score(List.of(), 200, 200), 1e-12);
        assertEquals(0.0, TesseractOcrScore.score(TesseractOcrScore.parseTsv("").words(), 200, 200), 1e-12);
    }

    @Test
    @DisplayName("the evidence gate scales linearly below fifty boxes")
    void evidenceGateScalesBelowFloor() {
        TesseractOcrScore.ParsedTsv parsed = TesseractOcrScore.parseTsv(tsv(10));
        assertEquals(10 / 50.0, TesseractOcrScore.score(parsed.words(), 200, 200), 1e-12);
    }

    @Test
    @DisplayName("punctuation soup loses only the lexical term")
    void punctuationSoupLosesTheLexicalTerm() {
        StringBuilder sb = new StringBuilder(HEADER).append('\n');
        for (int i = 0; i < 50; i++) {
            sb.append(row(10 + (i % 10) * 18, 10 + (i / 10) * 30, 12, 10, 100, "(,)]"));
        }
        TesseractOcrScore.ParsedTsv parsed = TesseractOcrScore.parseTsv(sb.toString());
        // mean, tail and dispersion stay perfect; lexical validity is zero, so
        // the weighted sum loses exactly the 0.20 lexical weight.
        assertEquals(0.80, TesseractOcrScore.score(parsed.words(), 200, 200), 1e-12);
    }

    @Test
    @DisplayName("usable boxes drop tiny, off-page and oversized boxes")
    void usableBoxesRejectsUnusableBoxes() {
        List<TesseractOcrScore.Word> words = List.of(
                new TesseractOcrScore.Word("ok", 0, 0, 10, 10, 99, 1),
                new TesseractOcrScore.Word("offscreen", 95, 0, 10, 10, 99, 1),
                new TesseractOcrScore.Word("huge", 0, 0, 900, 900, 99, 1),
                new TesseractOcrScore.Word("tiny", 0, 0, 2, 2, 99, 1));
        List<TesseractOcrScore.Word> usable = TesseractOcrScore.usableBoxes(words, 100, 100);
        assertEquals(1, usable.size());
        assertEquals("ok", usable.get(0).text());
    }

    @Test
    @DisplayName("parse keeps only valid word rows, in order")
    void parseIgnoresNonWordAndEmptyRows() {
        String tsv = HEADER + "\n"
                + "1\t1\t0\t0\t0\t0\t0\t0\t0\t0\t-1\t\n"
                + "4\t1\t1\t1\t1\t0\t10\t10\t50\t10\t-1\t\n"
                + "5\t1\t1\t1\t1\t1\t10\t10\t30\t10\t-1\t\n"
                + "5\t1\t1\t1\t1\t2\t10\t10\t30\t10\t0\t\n"
                + "5\t1\t1\t1\t1\t3\t10\t10\t0\t10\t90\tword\n"
                + "5\t1\t1\t1\t1\t4\t10\t10\t30\t10\t90\talpha\n";
        TesseractOcrScore.ParsedTsv parsed = TesseractOcrScore.parseTsv(tsv);
        assertEquals(1, parsed.words().size());
        assertEquals("alpha", parsed.words().get(0).text());
        assertEquals(1, parsed.pageNum());
    }

    @Test
    @DisplayName("rows survive a fractional confidence, which the TSV spec allows")
    void parseKeepsFractionalConfidences() {
        String tsv = HEADER + "\n" + row(10, 10, 30, 10, 92.4, "alpha");
        TesseractOcrScore.ParsedTsv parsed = TesseractOcrScore.parseTsv(tsv);
        assertEquals(1, parsed.words().size(), "a fractional conf must not read as -1 and drop the row");
        assertEquals(92.4, parsed.words().get(0).conf(), 1e-9);
    }

    @Test
    @DisplayName("a page whose every box is unusable scores zero, not unknown")
    void allBoxesUnusableScoresZero() {
        // Fifty well-formed rows, but every box is 2x2, so nothing survives
        // usableBoxes and the evidence gate has nothing to scale.
        StringBuilder sb = new StringBuilder(HEADER).append('\n');
        for (int i = 0; i < 50; i++) sb.append(row(10, 10, 2, 2, 100, "word" + i));
        TesseractOcrScore.ParsedTsv parsed = TesseractOcrScore.parseTsv(sb.toString());
        assertEquals(50, parsed.words().size());
        assertEquals(0.0, TesseractOcrScore.score(parsed.words(), 200, 200), 1e-12);
    }

    @Test
    @DisplayName("a TSV with no header row is rejected rather than scored as blank")
    void headerlessTsvIsRejected() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> TesseractOcrScore.parseTsv("5\t1\t1\t1\t1\t1\t10\t10\t30\t10\t90\talpha\n"));
        assertTrue(e.getMessage().contains("header"), e.getMessage());
    }

    @Test
    @DisplayName("rows from another page are not pooled into this page's score")
    void pagesAreScoredSeparately(@TempDir Path dir) throws IOException {
        Path pdf = writeImagePdf(dir.resolve("two.pdf"), 200, 200, 2);
        String tsv = HEADER + "\n"
                + tsvRows(1, 50, 20)      // page 1: legible but weak
                + tsvRows(2, 50, 100);    // page 2: perfect
        Files.writeString(dir.resolve("two.tsv"), tsv);

        TesseractOcrScore.ParsedTsv parsed = TesseractOcrScore.parseTsv(tsv);
        assertEquals(1, parsed.pageNum(), "the first word row decides the default page");
        assertEquals(50, parsed.wordsOnPage(1).size());
        assertEquals(50, parsed.wordsOnPage(2).size());

        assertEquals(1.0, TesseractOcrScore.scorePage(
                dir.resolve("two.tsv"), pdf, 2, 300), 1e-12,
                "page 2's perfect words must not be diluted by page 1's weak ones");
        assertTrue(TesseractOcrScore.scorePage(dir.resolve("two.tsv"), pdf, 1, 300)
                        < 1.0,
                "page 1 scores on its own words, which are weaker");
    }

    // ── PDF page extent ──────────────────────────────────────────────────────

    @Test
    @DisplayName("page extent comes from the embedded image's pixels, not the page box")
    void pageExtentUsesEmbeddedImagePixels(@TempDir Path dir) throws IOException {
        Path pdf = writeImagePdf(dir.resolve("img.pdf"), 300, 400);
        assertArrayEquals(new int[]{300, 400}, TesseractOcrScore.pageExtentPixels(pdf, 0, 72));
    }

    @Test
    @DisplayName("page extent falls back to the page box scaled by DPI when no image is embedded")
    void pageExtentFallsBackToPageBoxTimesDpi(@TempDir Path dir) throws IOException {
        Path pdf = dir.resolve("noimg.pdf");
        try (PDDocument doc = new PDDocument()) {
            doc.addPage(new PDPage(new PDRectangle(72f, 144f)));
            doc.save(pdf.toFile());
        }
        assertArrayEquals(new int[]{100, 200}, TesseractOcrScore.pageExtentPixels(pdf, 0, 100));
    }

    // ── End-to-end ───────────────────────────────────────────────────────────

    @Test
    void scorePageReadsTsvAndPdf(@TempDir Path dir) throws IOException {
        Path pdf = writeImagePdf(dir.resolve("p_0001.pdf"), 200, 200);
        Path tsv = dir.resolve("p_0001.tsv");
        Files.writeString(tsv, tsv(50));
        assertEquals(1.0, TesseractOcrScore.scorePage(tsv, pdf, null, 300), 1e-12);
    }

    @Test
    @DisplayName("batch writes name-tab-score lines in natural page order")
    void batchWritesScoresInPageOrder(@TempDir Path dir) throws IOException {
        // Deliberately unpadded ids: with zero-padded names a lexicographic sort
        // would produce the same order and the test could not fail.
        int pages = 12;
        for (int i = 1; i <= pages; i++) {
            String base = "p_" + i;
            writeImagePdf(dir.resolve(base + ".pdf"), 200, 200);
            Files.writeString(dir.resolve(base + ".tsv"), tsv(50));
        }
        Path out = dir.resolve("scores.txt");
        int exit = new CommandLine(new TesseractOcrScore())
                .execute("--dir", dir.toString(), "--out", out.toString());
        assertEquals(0, exit);

        List<String> lines = Files.readAllLines(out);
        assertEquals(pages, lines.size());
        for (int i = 1; i <= pages; i++) {
            assertEquals("p_" + i + "\t1.000000", lines.get(i - 1));
        }
    }

    @Test
    @DisplayName("a missing TSV fails the batch before any output is written")
    void batchFailsFastOnMissingPair(@TempDir Path dir) throws IOException {
        writeImagePdf(dir.resolve("p_0001.pdf"), 200, 200);
        Files.writeString(dir.resolve("p_0001.tsv"), tsv(50));
        writeImagePdf(dir.resolve("p_0002.pdf"), 200, 200);
        Path out = dir.resolve("scores.txt");
        int exit = new CommandLine(new TesseractOcrScore())
                .execute("--dir", dir.toString(), "--out", out.toString());
        assertNotEquals(0, exit);
        assertFalse(Files.exists(out), "fail-fast must not leave a partial output file");
    }

    @Test
    @DisplayName("a TSV with no PDF beside it is dropped, not scored")
    void batchIgnoresOrphanTsv(@TempDir Path dir) throws IOException {
        writeImagePdf(dir.resolve("p_0001.pdf"), 200, 200);
        Files.writeString(dir.resolve("p_0001.tsv"), tsv(50));
        Files.writeString(dir.resolve("stray.tsv"), tsv(50));
        Path out = dir.resolve("scores.txt");
        int exit = new CommandLine(new TesseractOcrScore())
                .execute("--dir", dir.toString(), "--out", out.toString());
        assertEquals(0, exit);
        assertEquals(List.of("p_0001\t1.000000"), Files.readAllLines(out));
    }

    // ── Fixtures ─────────────────────────────────────────────────────────────

    /** {@code n} perfect word rows laid out inside a 200x200 page. */
    private static String tsv(int n) {
        return HEADER + "\n" + tsvRows(1, n, 100);
    }

    /** {@code n} word rows on one TSV page, tiled so no box leaves 200x200. */
    private static String tsvRows(int page, int n, double conf) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            sb.append(row(page, 10 + (i % 10) * 18, 10 + (i / 10) * 30, 12, 10, conf, "word" + i));
        }
        return sb.toString();
    }

    private static String row(int x, int y, int w, int h, double conf, String text) {
        return row(1, x, y, w, h, conf, text);
    }

    private static String row(int page, int x, int y, int w, int h, double conf, String text) {
        return "5\t" + page + "\t1\t1\t1\t1\t" + x + "\t" + y + "\t" + w + "\t" + h
                + "\t" + conf + "\t" + text + "\n";
    }

    /** Writes a PDF embedding a {@code w x h} JPEG as each page's raster. */
    private static Path writeImagePdf(Path path, int w, int h) throws IOException {
        return writeImagePdf(path, w, h, 1);
    }

    private static Path writeImagePdf(Path path, int w, int h, int pages) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            for (int p = 0; p < pages; p++) {
                PDPage page = new PDPage(new PDRectangle(200f, 300f));
                doc.addPage(page);
                BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
                PDImageXObject xobj = JPEGFactory.createFromImage(doc, image);
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    cs.drawImage(xobj, 0, 0, 200, 300);
                }
            }
            doc.save(path.toFile());
        }
        return path;
    }
}
