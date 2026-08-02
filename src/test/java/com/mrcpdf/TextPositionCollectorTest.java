package com.mrcpdf;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.mrcpdf.model.TextBlock;

/**
 * Regression tests for the line-clustering heuristic in
 * {@code MrcPdf.TextPositionCollector.buildWordBlocks()}.
 *
 * The old heuristic anchored each line to its first character's baseline and
 * used a fixed page-wide median-height threshold. That split single visual
 * lines apart when baselines drifted (rotated scans) or when a page mixed font
 * sizes. The fix uses a running-mean baseline and an adaptive threshold
 * (max of the page-wide median and the current line's mean height).
 */
class TextPositionCollectorTest {

    @Test
    void buildWordBlocks_driftingBaseline_staysOneLine() throws Exception {
        Object collector = newCollector();
        List<Object> chars = charsField(collector);

        // A single "line" whose baseline drifts +1.2px per char (rotated scan),
        // bounded so the total drift stays within the adaptive threshold.
        // Old code: fixed first-char anchor, threshold 10 -> split at char 9.
        for (int i = 0; i < 14; i++) {
            addChar(chars, "x", i * 12f, 100f + i * 1.2f, 8f, 20f);
        }

        List<TextBlock> blocks = buildWords(collector, 72f);
        assertEquals(1, blocks.size(), "Drifting baseline must stay one line");
        assertEquals(14, blocks.get(0).getWord().length(), "All chars in one word");
    }

    @Test
    void buildWordBlocks_mixedFontSizes_keepsHeaderIntact() throws Exception {
        Object collector = newCollector();
        List<Object> chars = charsField(collector);

        // Large header (h=30) whose baselines drift 3px per char, plus two body
        // lines (h=10). The page-wide median height is 10, so the old fixed
        // threshold (5) split the header apart; the adaptive threshold uses the
        // header's own height (15) and keeps it together.
        for (int i = 0; i < 5; i++) {
            addChar(chars, "T", i * 20f, 100f + i * 3f, 16f, 30f);
        }
        for (int lineY = 150; lineY <= 190; lineY += 40) {
            for (int i = 0; i < 5; i++) {
                addChar(chars, "A", i * 14f, lineY, 12f, 10f);
            }
        }

        List<TextBlock> blocks = buildWords(collector, 72f);
        assertEquals(3, blocks.size(), "Header + two body lines");
        assertEquals("TTTTT", blocks.get(0).getWord(), "Header must stay one word");
    }

    @Test
    void buildWordBlocks_superscript_mergesIntoLine() throws Exception {
        Object collector = newCollector();
        List<Object> chars = charsField(collector);

        // "Hello" on the baseline (y=100) with a superscript "2" (y=94, h=10).
        // A moderate sub/superscript offset stays within the adaptive threshold
        // and must merge back into its line, not become its own line.
        String body = "Hello";
        for (int i = 0; i < body.length(); i++) {
            addChar(chars, body.substring(i, i + 1), i * 12f, 100f, 10f, 20f);
        }
        addChar(chars, "2", body.length() * 12f, 94f, 10f, 10f);

        List<TextBlock> blocks = buildWords(collector, 72f);
        assertEquals(1, blocks.size(), "Superscript must merge into its line");
        assertEquals("Hello2", blocks.get(0).getWord());
    }

    // ── reflection helpers (TextPositionCollector / CharPos are private) ──

    private static Object newCollector() throws Exception {
        Class<?> clazz = Class.forName("com.mrcpdf.MrcPdf$TextPositionCollector");
        Constructor<?> ctor = clazz.getDeclaredConstructor();
        ctor.setAccessible(true);
        return ctor.newInstance();
    }

    @SuppressWarnings("unchecked")
    private static List<Object> charsField(Object collector) throws Exception {
        Field f = collector.getClass().getDeclaredField("chars");
        f.setAccessible(true);
        return (List<Object>) f.get(collector);
    }

    private static void addChar(List<Object> chars, String text, float x, float y,
                                float width, float height) throws Exception {
        Class<?> cp = Class.forName("com.mrcpdf.MrcPdf$TextPositionCollector$CharPos");
        Constructor<?> ctor = cp.getDeclaredConstructor(
                String.class, float.class, float.class, float.class, float.class);
        ctor.setAccessible(true);
        chars.add(ctor.newInstance(text, x, y, width, height));
    }

    @SuppressWarnings("unchecked")
    private static List<TextBlock> buildWords(Object collector, float dpi) throws Exception {
        Method m = collector.getClass().getDeclaredMethod("buildWordBlocks", float.class);
        m.setAccessible(true);
        return (List<TextBlock>) m.invoke(collector, dpi);
    }
}
