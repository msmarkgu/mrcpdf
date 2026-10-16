package com.mrcpdf.quality.training;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.mrcpdf.model.TextBlock;

/**
 * Reconciles the two ways of recovering a page's reference words, so that
 * ground-truth WER measures the <em>pixels</em> rather than the reference.
 *
 * <p>PDFBox offers two views of a page's text layer and they disagree:
 * <ul>
 *   <li>{@link org.apache.pdfbox.text.PDFTextStripper}'s assembled output, which
 *       honours the word boundaries the producer encoded (including the word
 *       spaces Chromium/Skia never emit as separate glyphs); and</li>
 *   <li>per-word boxes, which {@link RealSrcDump} recovers by splitting glyph
 *       runs on inter-glyph gaps.</li>
 * </ul>
 *
 * <p>The second view is what supplies the boxes the glyph-local features need,
 * but its word segmentation is gap-based and therefore wrong for letter-spaced
 * text.  Measured on the real corpus, {@code scotus-cook} page 1 yields 71
 * reference words from the assembled text and 85 from the boxes, the extra
 * tokens being the title "PRELIMINARY" split into fourteen single characters.
 * Tesseract reads that page correctly, so the WER for a pristine page was being
 * driven to roughly 0.5 by a defect in the reference alone.  Ten of the
 * twenty-five base documents showed at least one such page.
 *
 * <p>So the assembled text is authoritative for the word sequence, and the box
 * words are aligned onto it rather than the other way round.  This class holds
 * that reconciliation and nothing else: the words it returns are the reference,
 * and each carries a box only where one could be aligned, because
 * {@code extractText} needs boxes while the WER comparison needs words.
 */
final class ReferenceAnchor {

    /**
     * Below this many reference words a WER is not worth reporting.  A handful of
     * tokens makes the edit rate hypersensitive to a single misread, and pages
     * this sparse are dominated by tables, figures and running heads rather than
     * prose.
     */
    static final int MIN_REFERENCE_WORDS = 40;

    /**
     * Words of disagreement tolerated while aligning boxes to the reference
     * sequence before the alignment is rejected.  Alignment is a merge/skip
     * walk, not a general edit distance, so a bound is needed to keep a
     * pathological page from consuming unbounded time.
     */
    private static final int MAX_ALIGN_DRIFT = 24;

    private ReferenceAnchor() {
    }

    /**
     * Reconciles assembled reference words with box words.
     *
     * @param assembledWords words from the assembled stripper output, in reading
     *                       order
     * @param boxWords       per-word boxes from the gap-based stripper
     * @return the reference sequence; each entry carries a box where one was
     *         aligned and null otherwise
     */
    static List<TextBlock> align(List<String> assembledWords, List<TextBlock> boxWords) {
        List<TextBlock> out = new ArrayList<>(assembledWords.size());
        if (boxWords.isEmpty()) {
            for (String w : assembledWords) out.add(new TextBlock(w, null, 0));
            return out;
        }

        int i = 0;   // index into assembledWords
        int j = 0;   // index into boxWords
        int drift = 0;
        while (i < assembledWords.size() && j < boxWords.size() && drift <= MAX_ALIGN_DRIFT) {
            String ref = normalize(assembledWords.get(i));
            String box = normalize(boxWords.get(j).getWord());

            if (ref.isEmpty()) {
                i++;
                continue;
            }
            if (ref.equals(box)) {
                out.add(new TextBlock(ref, boxWords.get(j).getBbox(),
                        boxWords.get(j).getConfidence()));
                i++;
                j++;
                continue;
            }

            // The box words are usually finer-grained than the reference (a word
            // split on letter spacing), so try to consume several of them to
            // rebuild the current reference word before giving up on it.
            StringBuilder merged = new StringBuilder();
            int k = j;
            int consumed = 0;
            while (k < boxWords.size() && consumed < 24) {
                String piece = normalize(boxWords.get(k).getWord());
                if (piece.isEmpty()) {
                    k++;
                    consumed++;
                    continue;
                }
                if (merged.length() + piece.length() > ref.length()) break;
                merged.append(piece);
                consumed++;
                k++;
                if (merged.toString().equals(ref)) break;
                // Bail out as soon as the prefix stops matching, so this stays
                // linear rather than quadratic in the reference length.
                if (!ref.startsWith(merged.toString())) break;
            }
            if (merged.toString().equals(ref)) {
                out.add(new TextBlock(ref, unionBox(boxWords, j, k), 0));
                i++;
                j = k;
                drift += consumed;
            } else {
                // Genuine disagreement.  The assembled text is authoritative, so
                // the reference word is kept and the box cursor is left alone:
                // the box word that just failed to match may well belong to the
                // <em>next</em> reference word, and consuming it here would
                // silently drop a correct box further along.  Only the reference
                // cursor advances, so the walk still terminates, and drift bounds
                // how long a hopeless page can keep this up.
                out.add(new TextBlock(ref, null, 0));
                i++;
                drift++;
            }
        }
        // Reference words with no box words left to align against.
        for (; i < assembledWords.size(); i++) {
            String ref = normalize(assembledWords.get(i));
            if (!ref.isEmpty()) out.add(new TextBlock(ref, null, 0));
        }
        return out;
    }

    /** Whether a page has enough reference words for its WER to be meaningful. */
    static boolean isUsable(List<TextBlock> reference) {
        return reference.size() >= MIN_REFERENCE_WORDS;
    }

    /** Smallest box containing every box in {@code [from, to)}. */
    private static java.awt.Rectangle unionBox(List<TextBlock> boxes, int from, int to) {
        java.awt.Rectangle union = null;
        for (int k = from; k < to && k < boxes.size(); k++) {
            java.awt.Rectangle b = boxes.get(k).getBbox();
            if (b == null) continue;
            if (union == null) {
                union = new java.awt.Rectangle(b);
            } else {
                union = union.union(b);
            }
        }
        return union;
    }

    /**
     * Normalizes a reference token the same way {@link ReadabilityOcr} normalizes
     * a hypothesis, so the two are comparable.
     */
    static String normalize(String w) {
        if (w == null) return "";
        String x = w.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9'\\-]", "");
        while (x.length() > 1 && (x.endsWith("-") || x.endsWith("'"))) x = x.substring(0, x.length() - 1);
        while (x.length() > 1 && (x.startsWith("-") || x.startsWith("'"))) x = x.substring(1);
        return x;
    }
}
