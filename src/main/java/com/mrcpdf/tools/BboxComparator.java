package com.mrcpdf.tools;

import java.io.File;
import java.io.IOException;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;

/**
 * Compares word bounding boxes between two PDFs (in PDF user-space points).
 * <p>
 * Usage:
 * <pre>
 *   java -cp build/mrcpdf.jar com.mrcpdf.tools.BboxComparator original.pdf mrc.pdf
 * </pre>
 */
public class BboxComparator {

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("Usage: BboxComparator <original.pdf> <mrc.pdf>");
            System.exit(1);
        }

        File origFile = new File(args[0]);
        File mrcFile = new File(args[1]);

        System.out.println("=== Original: " + origFile + " (" + origFile.length() + " bytes) ===");
        List<List<WordInfo>> origPages = extractAllPages(origFile);

        System.out.println("=== MRC: " + mrcFile + " (" + mrcFile.length() + " bytes) ===");
        List<List<WordInfo>> mrcPages = extractAllPages(mrcFile);

        int maxPages = Math.min(origPages.size(), mrcPages.size());
        int totalDiff = 0;
        int totalMatch = 0;
        int totalMiss = 0;

        for (int p = 0; p < maxPages; p++) {
            List<WordInfo> orig = origPages.get(p);
            List<WordInfo> mrc = mrcPages.get(p);
            System.out.println("\n=== Page " + (p + 1) + " (" + orig.size() + " orig / " + mrc.size() + " mrc words) ===");
            if (orig.isEmpty() && mrc.isEmpty()) continue;

            boolean headerPrinted = false;
            for (int i = 0; i < orig.size(); i++) {
                WordInfo ow = orig.get(i);
                WordInfo mw = findMatch(ow, mrc);
                if (mw != null) {
                    float dx = mw.x - ow.x;
                    float dy = mw.y - ow.y;
                    float dw = mw.w - ow.w;
                    float dh = mw.h - ow.h;
                    if (Math.abs(dx) > 0.5f || Math.abs(dy) > 0.5f || Math.abs(dw) > 0.5f || Math.abs(dh) > 0.5f) {
                        if (!headerPrinted) {
                            System.out.println("  Differences (position/size delta >0.5pt):");
                            headerPrinted = true;
                        }
                        System.out.printf("  %-20s orig=(%7.1f,%7.1f %5.1fx%5.1f) mrc=(%7.1f,%7.1f %5.1fx%5.1f)  Δ=(%+.1f,%+.1f %+.1fx%+.1f)%n",
                            "'" + truncate(ow.text, 18) + "'",
                            ow.x, ow.y, ow.w, ow.h,
                            mw.x, mw.y, mw.w, mw.h,
                            dx, dy, dw, dh);
                        totalDiff++;
                    } else {
                        totalMatch++;
                    }
                } else {
                    if (!headerPrinted) {
                        System.out.println("  Missing words:");
                        headerPrinted = true;
                    }
                    System.out.printf("  %-20s orig=(%7.1f,%7.1f %5.1fx%5.1f)  NO MATCH in mrc%n",
                        "'" + truncate(ow.text, 18) + "'", ow.x, ow.y, ow.w, ow.h);
                    totalMiss++;
                }
            }
        }

        System.out.println("\n=== Summary ===");
        System.out.println("  Matched (≤0.5pt diff): " + totalMatch);
        System.out.println("  Differing (>0.5pt):    " + totalDiff);
        System.out.println("  Missing in MRC:        " + totalMiss);

        if (totalDiff == 0 && totalMiss == 0) {
            System.out.println("  ✓ All words match within tolerance.");
        } else {
            System.out.println("  ✗ See details above.");
        }
    }

    private static String truncate(String s, int maxLen) {
        if (s == null || s.length() <= maxLen) return s != null ? s : "";
        return s.substring(0, maxLen) + "…";
    }

    private static WordInfo findMatch(WordInfo target, List<WordInfo> candidates) {
        WordInfo best = null;
        double bestScore = Double.MAX_VALUE;
        for (WordInfo c : candidates) {
            if (!c.text.equals(target.text)) continue;
            // Must be on the same approximate line (Y diff < half char height)
            float dy = Math.abs(c.y - target.y);
            float avgH = (c.h + target.h) / 2f;
            if (dy > avgH * 1.5f) continue;
            // Score by horizontal distance
            double score = Math.abs(c.x - target.x);
            if (score < bestScore) {
                bestScore = score;
                best = c;
            }
        }
        return best;
    }

    private static List<List<WordInfo>> extractAllPages(File pdf) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            int pageCount = doc.getNumberOfPages();
            WordStripper stripper = new WordStripper(pageCount);
            stripper.setStartPage(1);
            stripper.setEndPage(pageCount);
            stripper.setSortByPosition(true);
            stripper.writeText(doc, new StringWriter());
            return stripper.getResults();
        }
    }

    // ── PDFTextStripper that captures per-page word bboxes in PDF user space ──

    private static class WordStripper extends PDFTextStripper {
        private final List<List<CharInfo>> pageChars;

        WordStripper(int pageCount) throws IOException {
            super();
            pageChars = new ArrayList<>(pageCount);
            for (int i = 0; i < pageCount; i++) {
                pageChars.add(new ArrayList<>());
            }
        }

        @Override
        protected void processTextPosition(TextPosition text) {
            int pageIdx = getCurrentPageNo() - 1;
            if (pageIdx < 0 || pageIdx >= pageChars.size()) return;
            String ch = text.getUnicode();
            if (ch == null || ch.isEmpty()) return;
            if (ch.chars().allMatch(Character::isWhitespace)) return;
            float totalW = text.getWidth();
            float perCharW = totalW / Math.max(1, ch.length());
            float baseX = text.getX();
            float baseY = text.getY();
            float charH = text.getHeight() > 0 ? text.getHeight() : text.getFontSizeInPt();
            for (int i = 0; i < ch.length(); i++) {
                char c = ch.charAt(i);
                if (Character.isWhitespace(c)) continue;
                pageChars.get(pageIdx).add(new CharInfo(c, baseX + i * perCharW, baseY, perCharW, charH));
            }
        }

        List<List<WordInfo>> getResults() {
            List<List<WordInfo>> pages = new ArrayList<>(pageChars.size());
            for (int p = 0; p < pageChars.size(); p++) {
                pages.add(buildWords(pageChars.get(p)));
            }
            return pages;
        }

        private List<WordInfo> buildWords(List<CharInfo> chars) {
            List<WordInfo> words = new ArrayList<>();
            if (chars.isEmpty()) return words;

            chars.sort((a, b) -> {
                int cmp = Float.compare(a.y, b.y);
                if (cmp != 0) return cmp;
                return Float.compare(a.x, b.x);
            });

            List<CharInfo> word = new ArrayList<>();
            word.add(chars.get(0));
            for (int i = 1; i < chars.size(); i++) {
                CharInfo prev = word.get(word.size() - 1);
                CharInfo cur = chars.get(i);
                float dy = cur.y - prev.y;
                boolean sameLine = Math.abs(dy) < prev.h * 0.5f;
                float gap = cur.x - (prev.x + prev.w);
                if (!sameLine || gap > prev.w * 0.5f) {
                    words.add(toWord(word));
                    word = new ArrayList<>();
                }
                word.add(cur);
            }
            if (!word.isEmpty()) {
                words.add(toWord(word));
            }
            return words;
        }

        private WordInfo toWord(List<CharInfo> chars) {
            StringBuilder sb = new StringBuilder();
            float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE;
            float maxX = Float.MIN_VALUE, maxY = Float.MIN_VALUE;
            for (CharInfo c : chars) {
                sb.append(c.c);
                minX = Math.min(minX, c.x);
                minY = Math.min(minY, c.y);
                maxX = Math.max(maxX, c.x + c.w);
                maxY = Math.max(maxY, c.y + c.h);
            }
            return new WordInfo(sb.toString(), minX, minY, maxX - minX, maxY - minY);
        }

        private static class CharInfo {
            final char c;
            final float x, y, w, h;
            CharInfo(char c, float x, float y, float w, float h) {
                this.c = c; this.x = x; this.y = y; this.w = w; this.h = h;
            }
        }
    }

    // ── Data class ──

    static class WordInfo {
        final String text;
        final float x, y, w, h;
        WordInfo(String text, float x, float y, float w, float h) {
            this.text = text; this.x = x; this.y = y; this.w = w; this.h = h;
        }
    }
}
