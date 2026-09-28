package com.mrcpdf.model;

import java.awt.Rectangle;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Parses Tesseract's TSV sidecar into {@link TextBlock}s carrying the engine's
 * real per-word bounding boxes and confidences.
 *
 * <p>This exists because the PDF text layer cannot supply confidences: a layer
 * read back out of a content stream pins every word to exactly 100.0, which
 * {@code FeatureExtractor.isInformativeTextLayer()} correctly rejects as
 * uninformative. Tesseract's TSV is the only cheap way to recover genuine
 * engine uncertainty at runtime (see notes/gemini-page-readability-score.md
 * §6.2), and it is what the OCR-intrinsic dimension of the MRC readiness score
 * consumes.
 *
 * <p>TSV rows arrive in reading order and use a 12-column header:
 * {@code level page_num block_num par_num line_num word_num left top width
 * height conf text}. Only {@code level == 5} rows are words; the block/paragraph
 * /line levels carry {@code conf == -1} and no text, and are skipped. The text
 * column is last and may itself contain spaces, so it is taken as the
 * remainder of the line rather than a single split field.
 */
public final class TsvExtractor {

    /** TSV nesting level identifying a word row. */
    private static final int LEVEL_WORD = 5;

    private TsvExtractor() {
    }

    /** Parses TSV content into word blocks, preserving the engine's reading order. */
    public static List<TextBlock> parse(String tsv) {
        List<TextBlock> out = new ArrayList<>();
        if (tsv == null || tsv.isEmpty()) return out;

        // Column positions come from the header so a Tesseract build that adds
        // or reorders fields still parses correctly.
        int cLevel = 0, cLeft = 6, cTop = 7, cWidth = 8, cHeight = 9, cConf = 10, cText = 11;
        boolean sawHeader = false;

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

            if (f.length <= cText) continue;
            int level = parseInt(f[cLevel]);
            if (level != LEVEL_WORD) continue;

            String word = f[cText].trim();
            if (word.isEmpty()) continue;
            int conf = parseInt(f[cConf]);
            if (conf < 0) continue;

            int x = parseInt(f[cLeft]);
            int y = parseInt(f[cTop]);
            int w = parseInt(f[cWidth]);
            int h = parseInt(f[cHeight]);
            if (w <= 0 || h <= 0) continue;

            out.add(new TextBlock(word, new Rectangle(x, y, w, h), conf));
        }
        return out;
    }

    /** Reads and parses a TSV file written by {@code tesseract ... tsv}. */
    public static List<TextBlock> load(Path tsv) throws IOException {
        return parse(Files.readString(tsv));
    }

    private static int parseInt(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
