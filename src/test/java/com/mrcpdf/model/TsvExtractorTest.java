package com.mrcpdf.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Rectangle;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Parser tests for {@link TsvExtractor}, pinned to Tesseract 4.x TSV layout. */
class TsvExtractorTest {

    /** Verbatim shape of a real {@code tesseract img stdout tsv} header plus rows. */
    private static final String TSV = String.join("\n",
            "level\tpage_num\tblock_num\tpar_num\tline_num\tword_num\tleft\ttop\twidth\theight\tconf\ttext",
            "1\t1\t0\t0\t0\t0\t0\t0\t2550\t3299\t-1\t",
            "2\t1\t1\t1\t1\t0\t0\t0\t2550\t3299\t-1\t",
            "5\t1\t1\t1\t1\t1\t120\t240\t180\t44\t96\tIntroduction",
            "5\t1\t1\t1\t1\t2\t310\t240\t300\t44\t91\tto",
            // A real page emitted this garbage in the low-confidence tail.
            "5\t1\t1\t1\t1\t3\t610\t240\t90\t40\t26\t7,)(-",
            "5\t1\t1\t1\t2\t1\t120\t320\t260\t44\t88\tefficiency");

    @Test
    @DisplayName("parses only level-5 rows, keeping reading order and boxes")
    void parsesWords() {
        List<TextBlock> got = TsvExtractor.parse(TSV);

        assertEquals(4, got.size(), "block/paragraph level rows must be skipped");
        assertEquals("Introduction", got.get(0).getWord());
        assertEquals("efficiency", got.get(3).getWord(), "row order must be preserved");
        assertEquals(new Rectangle(120, 240, 180, 44), got.get(0).getBbox());
        assertEquals(96.0, got.get(0).getConfidence(), 1e-9);
        assertEquals(26.0, got.get(2).getConfidence(), 1e-9, "low confidence is data, not an error");
    }

    @Test
    @DisplayName("keeps a text column containing spaces")
    void keepsMultiWordText() {
        String tsv = TSV.replace("\t7,)(-", "\tstate of the art");
        List<TextBlock> got = TsvExtractor.parse(tsv);

        assertEquals(4, got.size());
        assertEquals("state of the art", got.get(2).getWord());
    }

    @Test
    @DisplayName("tolerates CRLF line endings")
    void toleratesCrlf() {
        assertEquals(TsvExtractor.parse(TSV).size(),
                TsvExtractor.parse(TSV.replace("\n", "\r\n")).size());
    }

    @Test
    @DisplayName("returns empty for empty or header-only input")
    void emptyInputs() {
        assertTrue(TsvExtractor.parse(null).isEmpty());
        assertTrue(TsvExtractor.parse("").isEmpty());
        assertTrue(TsvExtractor.parse(TSV.split("\n")[0]).isEmpty());
    }

    @Test
    @DisplayName("skips zero-area and negative-confidence rows")
    void skipsDegenerateRows() {
        String tsv = TSV + "\n5\t1\t1\t1\t2\t2\t700\t320\t0\t44\t90\tzero"
                + "\n5\t1\t1\t1\t2\t3\t700\t320\t60\t44\t-1\tnegative";
        List<TextBlock> got = TsvExtractor.parse(tsv);

        assertEquals(4, got.size());
    }
}
