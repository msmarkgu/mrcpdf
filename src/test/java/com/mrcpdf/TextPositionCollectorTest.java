package com.mrcpdf;

import static org.junit.jupiter.api.Assertions.*;

import com.mrcpdf.model.TextBlock;
import com.mrcpdf.pipeline.TextExtractor;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Regression tests for the line-clustering heuristic in {@code TextExtractor.buildWordBlocks()}.
 */
class TextPositionCollectorTest {

  @Test
  void buildWordBlocks_driftingBaseline_staysOneLine() throws Exception {
    TextExtractor te = new TextExtractor();
    for (int i = 0; i < 14; i++) {
      te.addChar("x", i * 12f, 100f + i * 1.2f, 8f, 20f);
    }
    List<TextBlock> blocks = te.buildWordBlocks(72f);
    assertEquals(1, blocks.size(), "Drifting baseline must stay one line");
    assertEquals(14, blocks.get(0).getWord().length(), "All chars in one word");
  }

  @Test
  void buildWordBlocks_mixedFontSizes_keepsHeaderIntact() throws Exception {
    TextExtractor te = new TextExtractor();
    for (int i = 0; i < 5; i++) {
      te.addChar("T", i * 20f, 100f + i * 3f, 16f, 30f);
    }
    for (int lineY = 150; lineY <= 190; lineY += 40) {
      for (int i = 0; i < 5; i++) {
        te.addChar("A", i * 14f, lineY, 12f, 10f);
      }
    }
    List<TextBlock> blocks = te.buildWordBlocks(72f);
    assertEquals(3, blocks.size(), "Header + two body lines");
    assertEquals("TTTTT", blocks.get(0).getWord(), "Header must stay one word");
  }

  @Test
  void buildWordBlocks_superscript_mergesIntoLine() throws Exception {
    TextExtractor te = new TextExtractor();
    String body = "Hello";
    for (int i = 0; i < body.length(); i++) {
      te.addChar(body.substring(i, i + 1), i * 12f, 100f, 10f, 20f);
    }
    te.addChar("2", body.length() * 12f, 94f, 10f, 10f);
    List<TextBlock> blocks = te.buildWordBlocks(72f);
    assertEquals(1, blocks.size(), "Superscript must merge into its line");
    assertEquals("Hello2", blocks.get(0).getWord());
  }
}
