package com.mrcpdf.quality.training;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.mrcpdf.model.TextBlock;

class ReferenceAnchorTest {

    // ── The bug this class exists to prevent ────────────────────────────────

    /**
     * The regression that motivated the whole class: "PRELIMINARY" is laid out
     * with letter spacing, so the gap-based box stripper emits it as fourteen
     * single-character words.  Tesseract reads the page correctly, so scoring the
     * page against that reference punishes a pristine page for a reference
     * defect.
     */
    @Test
    void letterSpacedWordIsRebuiltFromSingleCharacterBoxes() {
        List<TextBlock> boxes = new ArrayList<>();
        int x = 100;
        for (char c : "PRELIMINARY".toCharArray()) {
            boxes.add(new TextBlock(String.valueOf(c), new Rectangle(x, 40, 8, 20), 90));
            x += 12; // letter spacing: the gap exceeds SPACE_RATIO * height
        }
        List<TextBlock> ref = ReferenceAnchor.align(List.of("PRELIMINARY", "OPINIONS"), boxes);

        assertEquals(2, ref.size());
        assertEquals("preliminary", ref.get(0).getWord(), "must be one word, not fourteen");
        assertNotNull(ref.get(0).getBbox(), "merged word must keep a box");
        assertEquals(100, ref.get(0).getBbox().x, "box must start at the first character");
        // 11 characters, so the last box starts at 100 + 12*10 and is 8 wide.
        assertEquals(100 + 12 * 10 + 8,
                ref.get(0).getBbox().x + ref.get(0).getBbox().width,
                "box must span through the last character");
    }

    @Test
    void letterSpacedWordAloneMatchesAssembledExactly() {
        List<TextBlock> boxes = new ArrayList<>();
        for (char c : "SCOTUS".toCharArray()) {
            boxes.add(new TextBlock(String.valueOf(c), new Rectangle(10, 10, 6, 16), 80));
        }
        List<TextBlock> ref = ReferenceAnchor.align(List.of("scotus"), boxes);
        assertEquals(1, ref.size());
        assertEquals("scotus", ref.get(0).getWord());
        assertNotNull(ref.get(0).getBbox());
    }

    // ── Ordinary prose must pass through untouched ──────────────────────────

    @Test
    void alignedProseKeepsOneBoxPerWord() {
        List<TextBlock> boxes = List.of(
                new TextBlock("Displacements", new Rectangle(10, 10, 100, 20), 90),
                new TextBlock("of", new Rectangle(120, 10, 20, 20), 90),
                new TextBlock("Metallic", new Rectangle(150, 10, 70, 20), 90));
        List<TextBlock> ref =
                ReferenceAnchor.align(List.of("displacements", "of", "metallic"), boxes);

        assertEquals(3, ref.size());
        for (int i = 0; i < 3; i++) {
            assertEquals(boxes.get(i).getBbox(), ref.get(i).getBbox(),
                    "an exact match must keep its own box, not a union");
        }
    }

    @Test
    void referenceSurvivesWhenNoBoxesAtAll() {
        List<TextBlock> ref = ReferenceAnchor.align(
                List.of("a", "b", "c"), List.of());
        assertEquals(3, ref.size());
        for (TextBlock b : ref) {
            assertNull(b.getBbox(), "no box words means no boxes to align");
        }
        assertEquals("a", ref.get(0).getWord(), "the word sequence must survive intact");
    }

    // ── Disagreement handling ───────────────────────────────────────────────

    @Test
    void unalignableWordIsKeptWithoutABox() {
        // The reference has a word the boxes do not contain at all.
        List<TextBlock> boxes = List.of(
                new TextBlock("alpha", new Rectangle(10, 10, 50, 20), 90),
                new TextBlock("zulu", new Rectangle(70, 10, 40, 20), 90));
        List<TextBlock> ref = ReferenceAnchor.align(
                List.of("alpha", "quantum", "zulu"), boxes);

        assertEquals(3, ref.size(), "a missing reference word must not be dropped");
        assertEquals("quantum", ref.get(1).getWord());
        assertNull(ref.get(1).getBbox(), "an unaligned word has no box to contribute");
        assertEquals("zulu", ref.get(2).getWord(), "alignment must resume afterwards");
        assertNotNull(ref.get(2).getBbox());
    }

    @Test
    void extraBoxWordsDoNotAddReferenceWords() {
        // Boxes may be finer-grained than the reference; the reference decides how
        // many words the page has.
        List<TextBlock> boxes = new ArrayList<>();
        for (String s : List.of("a", "b", "c", "d", "e", "f")) {
            boxes.add(new TextBlock(s, new Rectangle(10, 10, 5, 10), 90));
        }
        List<TextBlock> ref = ReferenceAnchor.align(List.of("abcdef"), boxes);
        assertEquals(1, ref.size());
        assertEquals("abcdef", ref.get(0).getWord());
    }

    @Test
    void extraReferenceWordsDoNotBorrowEarlierBoxes() {
        List<TextBlock> boxes = List.of(
                new TextBlock("one", new Rectangle(10, 10, 30, 20), 90));
        List<TextBlock> ref = ReferenceAnchor.align(List.of("one", "two", "three"), boxes);
        assertEquals(3, ref.size());
        assertNotNull(ref.get(0).getBbox());
        assertNull(ref.get(1).getBbox());
        assertNull(ref.get(2).getBbox());
    }

    @Test
    void alignmentTerminatesOnPathologicalInput() {
        // A long reference that shares no prefix with a long run of boxes must not
        // spin: every merge attempt fails on the first prefix check.
        StringBuilder ref = new StringBuilder();
        for (int i = 0; i < 5000; i++) ref.append('q');
        List<String> assembled = new ArrayList<>();
        List<TextBlock> boxes = new ArrayList<>();
        for (int i = 0; i < 5000; i++) {
            assembled.add("z" + i);
            boxes.add(new TextBlock("y" + i, new Rectangle(i, 0, 4, 10), 90));
        }
        List<TextBlock> out = ReferenceAnchor.align(assembled, boxes);
        assertEquals(5000, out.size());
    }

    // ── Normalization must match the hypothesis side ───────────────────────

    @Test
    void normalizationDropsPunctuationAndCase() {
        assertEquals("vol", ReferenceAnchor.normalize("Vol."));
        assertEquals("91", ReferenceAnchor.normalize("91,"));
        assertEquals("berensteinzelevinsky", ReferenceAnchor.normalize("BERENSTEIN–ZELEVINSKY"));
        assertEquals("", ReferenceAnchor.normalize("..."));
        assertEquals("", ReferenceAnchor.normalize(null));
    }

    // ── The minimum-word guard ─────────────────────────────────────────────

    @Test
    void tooFewReferenceWordsIsNotUsable() {
        assertFalse(ReferenceAnchor.isUsable(List.of()),
                "an empty page has no measurable readability");
        List<TextBlock> few = new ArrayList<>();
        for (int i = 0; i < ReferenceAnchor.MIN_REFERENCE_WORDS - 1; i++) {
            few.add(new TextBlock("w" + i, null, 0));
        }
        assertFalse(ReferenceAnchor.isUsable(few),
                "one word short of the threshold must be rejected");
        few.add(new TextBlock("last", null, 0));
        assertTrue(ReferenceAnchor.isUsable(few), "exactly at the threshold is accepted");
    }
}
