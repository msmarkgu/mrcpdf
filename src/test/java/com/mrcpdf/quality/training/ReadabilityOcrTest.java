package com.mrcpdf.quality.training;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.mrcpdf.model.TextBlock;

/**
 * Guards the live Tesseract invocation against the failure mode that silently
 * zeroed an entire corpus run.
 *
 * <p>Regression: the subprocess was built as {@code tesseract img <mode> ...},
 * but in Tesseract 4.x the second positional argument is the <em>output base
 * name</em>, not the output mode. {@code tesseract img tsv} therefore wrote TSV
 * to a file literally named {@code tsv} and returned empty stdout with exit
 * status 0 and empty stderr. Every page then scored {@code R_mrc = 0.000} and
 * 100% of rows had their text features imputed — visible only as a
 * suspiciously uniform class distribution, not as an error.
 */
class ReadabilityOcrTest {

    private static final String TEXT =
            "Recurrent neural networks have firmly established the standard approach";

    private static BufferedImage page() {
        BufferedImage img = new BufferedImage(1700, 500, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, img.getWidth(), img.getHeight());
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(Color.BLACK);
        g.setFont(new Font(Font.SERIF, Font.PLAIN, 46));
        g.drawString(TEXT, 70, 250);
        g.dispose();
        return img;
    }

    private static List<TextBlock> truth() {
        List<TextBlock> out = new ArrayList<>();
        int x = 70;
        for (String w : TEXT.split(" ")) {
            out.add(new TextBlock(w, new java.awt.Rectangle(x, 205, 24 * w.length(), 56), 100.0));
            x += 24 * w.length() + 11;
        }
        return out;
    }

    @Test
    @DisplayName("recognize() returns words, boxes and confidences from one TSV call")
    void recognizesWords() throws IOException {
        assumeTrue(ReadabilityOcr.available(), "tesseract (eng) not installed");

        ReadabilityOcr.Result r = ReadabilityOcr.recognize(page());

        assertFalse(r.getWords().isEmpty(),
                "TSV stdout was empty - the tesseract config must be the third "
                        + "positional argument, after the 'stdout' output base");
        assertEquals(r.getWords().size(), r.getBlocks().size());
        assertTrue(r.getBlocks().stream().allMatch(b -> b.getBbox().width > 0));
        assertTrue(r.getBlocks().stream().anyMatch(b -> b.getConfidence() > 0),
                "TSV rows must carry real confidences, not the pinned 100 of a text layer");
    }

    @Test
    @DisplayName("a legible page audits near 1.0 against its own text")
    void legiblePageAuditsHigh() throws IOException {
        assumeTrue(ReadabilityOcr.available(), "tesseract (eng) not installed");

        double audit = ReadabilityOcr.measureWithWords(page(), truth()).getAuditScore();

        assertTrue(audit > 0.80, "expected a legible page to audit >=0.80, got " + audit);
    }

    @Test
    @DisplayName("the audit is only populated by the variant that measures it")
    void auditOnlyWhenMeasured() throws IOException {
        assumeTrue(ReadabilityOcr.available(), "tesseract (eng) not installed");

        assertFalse(Double.isNaN(ReadabilityOcr.measureWithWords(page(), truth()).getAuditScore()));
        assertTrue(Double.isNaN(ReadabilityOcr.recognize(page()).getAuditScore()));
    }

    @Test
    @DisplayName("auditScore is 1.0 for a perfect read and 0.0 for nothing")
    void perfectAndEmpty() {
        List<String> ref = List.of("a", "b", "c");

        assertEquals(1.0, ReadabilityOcr.auditScore(ref, ref), 1e-9);
        assertEquals(0.0, ReadabilityOcr.auditScore(ref, List.of()), 1e-9);
        assertEquals(0.0, ReadabilityOcr.auditScore(List.of(), ref), 1e-9);
        assertEquals(1.0, ReadabilityOcr.auditScore(List.of(), List.of()), 1e-9,
                "nothing to read against nothing written is not a failure");
    }

    @Test
    @DisplayName("auditScore is order-free, so columns do not read as unreadable")
    void ignoresReadingOrder() {
        List<String> left = List.of("one", "two", "three", "four");
        List<String> right = List.of("three", "four", "one", "two");

        // The defect this replaces: PDFBox emits a two-column page column-major
        // while --psm 6 reads it row-major, and an ordered edit rate read 0.122
        // on a pristine page for exactly this reason.
        assertEquals(1.0, ReadabilityOcr.auditScore(left, right), 1e-9);
    }

    @Test
    @DisplayName("auditScore absorbs a word the engine merged")
    void toleratesMergedWords() {
        // "register vol" -> "registervol": an ordered edit rate charged two
        // deletions plus an insertion and read a clean page at 0.075.  All four
        // reference words were read, so this is a perfect read, not 0.857.
        assertEquals(1.0, ReadabilityOcr.auditScore(
                List.of("federal", "register", "vol", "91"),
                List.of("federal", "registervol", "91")), 1e-9);
    }

    @Test
    @DisplayName("auditScore absorbs a word the engine split")
    void toleratesSplitWords() {
        assertEquals(1.0, ReadabilityOcr.auditScore(
                List.of("research"), List.of("re", "search")), 1e-9);
    }

    @Test
    @DisplayName("auditScore ignores hyphens and apostrophes")
    void toleratesPunctuationDifferences() {
        assertEquals(1.0, ReadabilityOcr.auditScore(
                List.of("co-operative", "don't"), List.of("cooperative", "dont")), 1e-9);
    }

    @Test
    @DisplayName("auditScore still charges genuinely misread and invented words")
    void chargesRealErrors() {
        // 1 of 3 reference words read, 2 invented: P = 1/3, R = 1/3, F1 = 1/3.
        assertEquals(1.0 / 3.0, ReadabilityOcr.auditScore(
                List.of("a", "b", "c"), List.of("a", "x", "z")), 1e-9);
        // A dropped word is a recall failure even with nothing invented.
        assertEquals(2.0 * (2.0 / 3.0) / (2.0 / 3.0 + 1.0), ReadabilityOcr.auditScore(
                List.of("a", "b", "c"), List.of("a", "b")), 1e-9);
    }

    @Test
    @DisplayName("auditScore counts repeated words as a multiset")
    void multisetSemantics() {
        assertEquals(1.0, ReadabilityOcr.auditScore(
                List.of("the", "the", "cat"), List.of("the", "cat", "the")), 1e-9);
        assertTrue(ReadabilityOcr.auditScore(List.of("the", "the"),
                List.of("the")) < 1.0, "a dropped repeat must not be forgiven");
    }

    /**
     * A token may only ever be spent once.  The three passes (exact, split,
     * merge) used to keep separate bookkeeping, so "ab" could be credited as an
     * exact hit and then again as a merge of "a"+"b", double-counting reference
     * words the page never actually justified.
     */
    @Test
    @DisplayName("auditScore never spends the same token twice")
    void tokensAreConsumedAtMostOnce() {
        // "ab" is an exact hit for ref[1]; the merge pass must not also be able
        // to claim it for ref[0]+ref[2].  Only ref[1] is read, so nothing may be
        // credited for "a" and "b" -- the score must stay below 1.0.
        double score = ReadabilityOcr.auditScore(
                List.of("a", "ab", "b"), List.of("ab"));
        // accounted = 1 exact hit; hyp tokens used = 1; nothing invented.
        // recall = 1/3, precision = 1/1, F1 = 2*(1/3)/(1/3+1) = 0.5.
        assertEquals(0.5, score, 1e-9, "the exact hit must not be re-spent as a merge");
    }

    /**
     * A single hypothesis token that is the exact reading of a reference word
     * must not also be usable as one half of a split elsewhere in the page.
     */
    @Test
    @DisplayName("auditScore does not let a join pass steal an exact match")
    void joinPassesOnlySeeLeftovers() {
        // ref:  x y z        hyp:  xy z y
        // The exact pass runs first and spends both "z" and the trailing "y":
        //   hit = 2, and rUsed = {x unused, y used, z used}
        // "xy" cannot then merge ref "x"+"y" because ref "y" is already spent.
        // accounted = 2 of 3, hyp used = 2, invented = 1.
        // recall = 2/3, precision = 2/3, F1 = 2/3.
        //
        // The old per-pass bookkeeping let the merge pass reach past the exact
        // pass, credit ref "x" and "y" a second time, and report 6/7 for a page
        // that had in fact read only 2 of its 3 words.
        assertEquals(2.0 / 3.0, ReadabilityOcr.auditScore(
                List.of("x", "y", "z"), List.of("xy", "z", "y")), 1e-9);
    }

    /**
     * Split and merge passes share one consumption map, so the same reference
     * word cannot be claimed by both.
     */
    @Test
    @DisplayName("auditScore does not let the split and merge passes double-book")
    void splitAndMergePassesShareConsumption() {
        // ref:  ab c     hyp:  a b c
        //  - "c" is an exact hit.
        //  - "a"+"b" splits ref "ab" -> 1 reference word.
        // All three reference words accounted for, all three hyp tokens used.
        assertEquals(1.0, ReadabilityOcr.auditScore(
                List.of("ab", "c"), List.of("a", "b", "c")), 1e-9);

        // ref:  ab c     hyp:  a b cc
        //  - split: ref "ab" = hyp "a"+"b"  -> 1 reference word.
        //  - "c" is never read: the trailing "cc" matches nothing, so there is
        //    no hyp token left to merge against ref "c".
        //  accounted = 1 of 2, hyp used = 2, invented = 1 ("cc").
        //  recall = 1/2, precision = 1/2, F1 = 1/2.
        assertEquals(0.5, ReadabilityOcr.auditScore(
                List.of("ab", "c"), List.of("a", "b", "cc")), 1e-9);
    }

}
