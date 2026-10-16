package com.mrcpdf.pipeline;

import com.mrcpdf.model.PageResult;
import com.mrcpdf.model.TextBlock;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;

/**
 * Extracts per-character text positions from a PDF's existing text layer and assembles word-level
 * {@link TextBlock} entries. PDF coordinates are converted to page-image pixel coordinates
 * (top-left origin, y increasing downward).
 */
public class TextExtractor extends PDFTextStripper {

  private final List<CharPos> chars;

  public TextExtractor() throws IOException {
    super();
    setSortByPosition(true);
    chars = new ArrayList<>();
  }

  /**
   * Extracts text positions from a single page and packages them with the pre-encoded image data
   * into a {@link PageResult}.
   */
  public static PageResult extractPage(
      PDDocument doc,
      int pageIdx,
      float dpi,
      int imgW,
      int imgH,
      byte[] bgJpeg,
      byte[] fgColorJpeg,
      BufferedImage mask)
      throws IOException {
    TextExtractor c = new TextExtractor();
    c.setStartPage(pageIdx + 1);
    c.setEndPage(pageIdx + 1);
    c.writeText(doc, new StringWriter());
    return new PageResult(pageIdx, imgW, imgH, c.buildWordBlocks(dpi), bgJpeg, fgColorJpeg, mask);
  }

  /**
   * Extracts text blocks from a single page into the provided holder array. The result is stored in
   * {@code out[0]} as a list of text blocks (or an empty list if the page has no text). This runs
   * on the caller's thread.
   */
  @SuppressWarnings("unchecked")
  public static void extractTextBlocks(
      PDDocument doc, int pageIdx, float dpi, int imgW, int imgH, List<TextBlock>[] out)
      throws IOException {
    TextExtractor c = new TextExtractor();
    c.setStartPage(pageIdx + 1);
    c.setEndPage(pageIdx + 1);
    c.writeText(doc, new StringWriter());
    out[0] = c.buildWordBlocks(dpi);
  }

  /** Test seam: inject a character position directly (bypasses PDF rendering). */
  public void addChar(String text, float x, float y, float width, float height) {
    chars.add(new CharPos(text, x, y, width, height));
  }

  @Override
  protected void processTextPosition(TextPosition text) {
    String ch = text.getUnicode();
    if (ch == null || ch.isEmpty()) return;
    if (ch.chars().allMatch(Character::isWhitespace)) return;
    float charH = text.getHeight() > 0 ? text.getHeight() : text.getFontSizeInPt();
    chars.add(new CharPos(ch, text.getX(), text.getY(), text.getWidth(), charH));
  }

  /**
   * Clusters captured characters into visual lines, then splits each line into words by horizontal
   * gap.
   */
  public List<TextBlock> buildWordBlocks(float dpi) {
    List<TextBlock> blocks = new ArrayList<>();
    if (chars.isEmpty()) return blocks;

    chars.sort((a, b) -> Float.compare(a.y, b.y));

    List<List<CharPos>> lines = new ArrayList<>();
    List<CharPos> currentLine = new ArrayList<>();
    float[] heights = new float[chars.size()];
    for (int i = 0; i < chars.size(); i++) heights[i] = chars.get(i).height;
    java.util.Arrays.sort(heights);
    float pageMedianHeight = heights[heights.length / 2];

    float lineMeanY = 0f;
    float lineMeanHeight = 0f;
    int lineCount = 1;
    for (CharPos cp : chars) {
      if (currentLine.isEmpty()) {
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
      charPosList.add(
          new float[] {cp.x * scale, cp.y * scale, cp.width * scale, cp.height * scale});
    }
    int px = Math.round(minX * scale);
    int py = Math.round(minY * scale);
    int pw = Math.round((maxX - minX) * scale);
    int ph = Math.round((maxY - minY) * scale);
    return new TextBlock(
        sb.toString(), new Rectangle(px, py, Math.max(1, pw), Math.max(1, ph)), 100.0, charPosList);
  }

  static class CharPos {
    final String text;
    final float x, y, width, height;

    CharPos(String text, float x, float y, float width, float height) {
      this.text = text;
      this.x = x;
      this.y = y;
      this.width = width;
      this.height = height;
    }
  }
}
