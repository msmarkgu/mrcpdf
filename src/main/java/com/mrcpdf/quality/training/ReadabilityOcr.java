package com.mrcpdf.quality.training;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.imageio.ImageIO;

import com.mrcpdf.model.TextBlock;
import com.mrcpdf.model.TsvExtractor;
import com.mrcpdf.util.SubprocessRunner;

/**
 * Measures the "text readability" of a synthetic corpus page by OCR against the
 * known ground-truth text the page generator printed.
 *
 * <p>The corpus knows exactly which words were drawn on each page, so unlike a
 * parametric degradation score this is an <em>independent measurement of the
 * rendered pixels</em>: the page raster is OCR'd with Tesseract and the
 * recognized words are compared to the reference words.  The score is a
 * word-level F1 over normalized words (1.0 = every word read, nothing invented,
 * 0.0 = nothing read), matched as a multiset so reading order is irrelevant and
 * with split/merge tolerance so engine tokenization differences are not charged
 * as unreadability.  See {@link #auditScore} for why an edit rate was replaced.
 *
 * <p>Determinism is pinned to the installed Tesseract binary and language data
 * ({@code eng}); the emitted CSVs / JSON remain the replay artifacts.
 */
public final class ReadabilityOcr {

    private static final String TESSERACT = findTessler();
    /**
     * Per-page OCR budget.  A 300-DPI page under heavy machine load can exceed
     * two minutes, and a hard failure there aborts a multi-hour corpus build, so
     * the budget is generous and the call is retried once.
     */
    private static final long TIMEOUT_MS = Long.getLong("mrc.ocr.timeoutMs", 300_000L);

    /**
     * Tesseract is built on OpenMP and defaults to one thread per core.
     *
     * <p>Left alone, each worker spawns a full-width OpenMP pool, so five workers
     * ask for forty threads on eight cores. The pages do not get slower by the
     * amount the arithmetic suggests -- they get much slower, because the
     * schedulers thrash, and a page that takes 35s alone was observed exceeding
     * the 300s timeout under that load. Parallelism here comes from running
     * several pages at once, which is what the worker pool already does, so
     * Tesseract is pinned to one thread and the cores are shared predictably.
     */
    private static final Map<String, String> TESSERACT_ENV = Map.of("OMP_THREAD_LIMIT", "1");
    private static final int OCR_ATTEMPTS = 2;
    private static Boolean available;

    private static String findTessler() {
        String path = System.getenv("PATH");
        if (path != null) {
            for (String dir : path.split(File.pathSeparator)) {
                File candidate = new File(dir, "tesseract");
                if (candidate.isFile() && candidate.canExecute()) return candidate.getAbsolutePath();
            }
        }
        return "/usr/bin/tesseract";
    }

    private ReadabilityOcr() {
    }

    /** True when a usable Tesseract with the {@code eng} language pack is present. */
    public static synchronized boolean available() {
        if (available == null) {
            available = false;
            File exe = new File(TESSERACT);
            if (exe.isFile() && exe.canExecute()) {
                try {
                    SubprocessRunner.Result r = SubprocessRunner.run(exe,
                            List.of("--list-langs"), TIMEOUT_MS);
                    available = r.getExitCode() == 0 && r.getStdoutString().contains("eng");
                } catch (IOException e) {
                    available = false;
                }
            }
        }
        return available;
    }

    /**
     * OCR's {@code img} and returns the readback readability score in [0,1].
     *
     * @param img         the rendered page raster (300-DPI, same pixels the
     *                    FeatureExtractor reads).
     * @param groundTruth the page's known {@link TextBlock}s (reading order).
     * @throws IOException if Tesseract is unavailable or fails.
     */
    public static double measure(BufferedImage img, List<TextBlock> groundTruth) throws IOException {
        List<String> ref = normalize(text(groundTruth));
        List<String> hyp = words(img);
        return clamp(auditScore(ref, hyp));
    }

    /**
     * Both OCR-derived quantities from a single Tesseract invocation: the words it
     * recognized, with their boxes and confidences.
     *
     * <p>Asking for {@code stdout} and for {@code tsv} in separate calls would OCR
     * every page twice.  TSV is a strict superset of the plain text output, so one
     * {@code make tsv} call serves both consumers: {@link #auditScore} over the
     * recognized word strings for the ground-truth audit score, and the boxes plus
     * confidences that {@link com.mrcpdf.quality.model.MrcReadiness} needs for the
     * reference-free label.
     */
    public static Result recognize(BufferedImage img) throws IOException {
        String tsv = run(img, "tsv");
        if (tsv.isEmpty()) {
            throw new IOException("tesseract returned no TSV output for a "
                    + img.getWidth() + "x" + img.getHeight() + " page; refusing to "
                    + "score it as unreadable. Check the argument order: the config "
                    + "must be the third positional, after the 'stdout' output base.");
        }
        List<TextBlock> blocks = TsvExtractor.parse(tsv);
        List<String> words = new ArrayList<>(blocks.size());
        for (TextBlock b : blocks) {
            String w = normalizeWord(b.getWord());
            if (!w.isEmpty()) words.add(w);
        }
        return new Result(words, blocks);
    }

    /**
     * Audit WER and OCR word geometry from one Tesseract call, against the page's
     * known ground truth.
     */
    public static Result measureWithWords(BufferedImage img, List<TextBlock> groundTruth)
            throws IOException {
        List<String> ref = normalize(text(groundTruth));
        Result r = recognize(img);
        return new Result(r.getWords(), r.getBlocks(), clamp(auditScore(ref, r.getWords())));
    }

    /** OCR readback: the normalized words, their boxes, and optionally the WER. */
    public static final class Result {
        private final List<String> words;
        private final List<TextBlock> blocks;
        private final double auditScore;

        Result(List<String> words, List<TextBlock> blocks) {
            this(words, blocks, Double.NaN);
        }

        Result(List<String> words, List<TextBlock> blocks, double auditScore) {
            this.words = words;
            this.blocks = blocks;
            this.auditScore = auditScore;
        }

        /** Recognized words, normalized, in reading order. */
        public List<String> getWords() {
            return words;
        }

        /** Recognized words with their pixel boxes and confidences. */
        public List<TextBlock> getBlocks() {
            return blocks;
        }

        /** Word-level F1 against the supplied ground truth, or NaN if not measured. */
        public double getAuditScore() {
            return auditScore;
        }
    }

    /** Recognized words (normalized) for {@code img}. */
    static List<String> words(BufferedImage img) throws IOException {
        return recognize(img).getWords();
    }

    /**
     * Runs Tesseract on {@code img} and returns its stdout.
     *
     * <p>The config string is the third positional argument
     * ({@code "tsv"}), not the second: in Tesseract 4.x the second positional is
     * the <em>output base name</em>, so {@code tesseract img tsv} writes to a
     * file called {@code tsv} and leaves stdout empty.  That is silent — exit
     * status 0, no stderr — so a caller that only checks the exit code sees a
     * successful run that recognized nothing.
     */
    private static String run(BufferedImage img, String config) throws IOException {
        if (!available()) {
            throw new IOException("OCR readability requires tesseract (lang 'eng'); install it or "
                    + "run 'tesseract --list-langs' to verify — see notes/ocr-readable-score.md");
        }
        File tmp = File.createTempFile("mrcpdf-ocr-", ".png");
        try {
            ImageIO.write(img, "png", tmp);
            IOException last = null;
            for (int attempt = 1; attempt <= OCR_ATTEMPTS; attempt++) {
                try {
                    // "stdout" is the output base name; the config ("tsv") is the
                    // third positional.  Reversing them yields a zero-byte stdout
                    // and a successful exit code.
                    SubprocessRunner.Result r = SubprocessRunner.run(new File(TESSERACT),
                            List.of(tmp.getAbsolutePath(), "stdout", config,
                                    "-l", "eng", "--psm", "6"),
                            TIMEOUT_MS, TESSERACT_ENV);
                    if (r.getExitCode() != 0) {
                        throw new IOException("tesseract failed (exit " + r.getExitCode()
                                + "): " + r.getStderrString());
                    }
                    return r.getStdoutString();
                } catch (IOException e) {
                    last = e;
                    if (attempt < OCR_ATTEMPTS) {
                        System.err.println("ReadabilityOcr: attempt " + attempt + " failed ("
                                + e.getMessage() + "), retrying");
                    }
                }
            }
            throw last;
        } finally {
            tmp.delete();
        }
    }

    /**
     * Word-level F1 between the reference words and the recognized words, in
     * [0,1], where 1.0 means every reference word was read and nothing extra was
     * invented.
     *
     * <p><b>Why not a word error rate.</b>  The obvious metric — ordered
     * Levenshtein over normalized word lists — measures agreement between two
     * <em>segmentations</em>, not legibility.  Measured on pristine, un-degraded
     * base pages it read:
     *
     * <pre>
     *   arxiv-attention p2  (single column)   1 - WER = 0.976
     *   scotus-cook p3                        1 - WER = 0.889
     *   arxiv-bert p2       (two columns)     1 - WER = 0.122
     *   fr-applicable-reqs p11 (Federal Reg.) 1 - WER = 0.075
     * </pre>
     *
     * A clean two-column page scores 0.12 because PDFBox emits the left column
     * then the right while {@code --psm 6} reads across the page, so the
     * sequences disagree while every word is perfectly legible.  A clean Federal
     * Register page scores 0.08 because Tesseract merges across a tight space
     * ({@code register vol} -> {@code registervol}), which an edit distance
     * charges as two deletions plus an insertion.  Meanwhile the OCR-confidence
     * dimension read 0.91 on the same raster.  An audit that disagrees with the
     * renderer it is auditing cannot police a label, and the old 1-WER
     * <em>label</em> failing this way is a plausible source of its 39/125
     * contaminated clean pages.
     *
     * <p>So the comparison is order-free and segmentation-tolerant:
     * <ol>
     *   <li>exact match on a canonical form with hyphens and apostrophes
     *       stripped, matched as a multiset so repeated words are handled;</li>
     *   <li>then, over the leftovers, a reference word split into two
     *       recognized fragments ({@code re search} / {@code research});</li>
     *   <li>then two reference words merged into one recognized token
     *       ({@code register vol} / {@code registervol}).</li>
     * </ol>
     * Each reconciliation credits one match, so the score stays a proper F1 over
     * word counts. Reading order stops mattering because a multiset has no order,
     * and the two failure modes above stop mattering because they are absorbed
     * rather than penalized.
     *
     * <p>No fuzzy or partial credit: a genuinely misread word still misses. That
     * keeps the audit a measurement rather than a similarity score, and keeps it
     * independent of {@code R_mrc}, which never looks at reference text at all.
     */
    static double auditScore(List<String> ref, List<String> hyp) {
        int m = ref.size();
        int n = hyp.size();
        if (m == 0 && n == 0) return 1.0;
        if (m == 0 || n == 0) return 0.0;

        String[] r = new String[m];
        for (int i = 0; i < m; i++) r[i] = canon(ref.get(i));
        String[] h = new String[n];
        for (int i = 0; i < n; i++) h[i] = canon(hyp.get(i));

        // One shared set of consumption flags, shared by all three passes.  An
        // earlier version gave each pass its own flags over the full lists, so a
        // token could be credited by the exact pass and then credited a second
        // time by a join pass -- the same reference word counted as both read and
        // split, which let the score exceed what the words actually justified.
        boolean[] rUsed = new boolean[m];
        boolean[] hUsed = new boolean[n];

        int hit = consumeExact(r, h, rUsed, hUsed);
        int split = consumeJoins(r, h, rUsed, hUsed, true);
        int merge = consumeJoins(r, h, rUsed, hUsed, false);

        // Everything is counted in reference words, which is the only unit the two
        // extractors agree on.  A split is one reference word read; a merge is
        // two.  Counting raw tokens instead would re-penalize the segmentation
        // difference this method exists to absorb, and would cap a perfect read
        // of a page whose words the engine joined at 0.857.
        double accounted = hit + split + 2.0 * merge;
        int tokensUsed = hit + 2 * split + merge;
        double invented = Math.max(0, n - tokensUsed);

        double recall = accounted / m;
        double precision = accounted / (accounted + invented);
        return (recall + precision) == 0 ? 0.0 : 2 * recall * precision / (recall + precision);
    }

    /**
     * Pass 1: consume one identical canonical token from each side.
     *
     * <p>Returns the number of reference words satisfied.  Greedy on a
     * token-multiset, which is exact here: equal counts are the only way a token
     * can be matched, so the pairing order cannot change the total.
     */
    private static int consumeExact(String[] r, String[] h, boolean[] rUsed, boolean[] hUsed) {
        java.util.Map<String, java.util.ArrayDeque<Integer>> byToken = new java.util.HashMap<>();
        for (int i = 0; i < r.length; i++) {
            if (!rUsed[i]) byToken.computeIfAbsent(r[i], k -> new java.util.ArrayDeque<>()).addLast(i);
        }
        int matched = 0;
        for (int j = 0; j < h.length; j++) {
            if (hUsed[j]) continue;
            java.util.ArrayDeque<Integer> q = byToken.get(h[j]);
            while (q != null && !q.isEmpty() && rUsed[q.peekFirst()]) q.pollFirst();
            if (q == null || q.isEmpty()) continue;
            rUsed[q.pollFirst()] = true;
            hUsed[j] = true;
            matched++;
        }
        return matched;
    }

    /**
     * Pass 2/3: consume every place where a leftover token equals the
     * concatenation of two adjacent leftover tokens on the other side.
     *
     * <p>Because {@code rUsed}/{@code hUsed} are the same arrays the exact pass
     * and the other join pass already wrote, each side can only ever be
     * consumed once.  A genuinely misread word is never absorbed by a
     * coincidental join, and no join can steal a token an earlier pass read.
     *
     * @param engineSplitWords when true, one <em>reference</em> word is matched
     *        against two adjacent <em>hypothesis</em> fragments (the engine split
     *        it); when false, two adjacent reference words are matched against
     *        one hypothesis token (the engine joined them)
     * @return reference words credited: 1 per split, 2 per merge
     */
    private static int consumeJoins(String[] r, String[] h, boolean[] rUsed, boolean[] hUsed,
                                    boolean engineSplitWords) {
        String[] joined = engineSplitWords ? r : h;
        String[] halves = engineSplitWords ? h : r;
        boolean[] joinedUsed = engineSplitWords ? rUsed : hUsed;
        boolean[] halvesUsed = engineSplitWords ? hUsed : rUsed;

        int matched = 0;
        for (int i = 0; i < joined.length; i++) {
            if (joinedUsed[i]) continue;
            for (int j = 0; j + 1 < halves.length; j++) {
                if (halvesUsed[j] || halvesUsed[j + 1]) continue;
                if (!joined[i].equals(halves[j] + halves[j + 1])) continue;
                joinedUsed[i] = true;
                halvesUsed[j] = true;
                halvesUsed[j + 1] = true;
                matched++;
                break;
            }
        }
        return matched;
    }

    /**
     * Hyphens and apostrophes are the two characters the two extractors disagree
     * about for reasons that have nothing to do with legibility: PDFBox keeps the
     * hyphen in {@code co-operative} where the engine may not, and the engine
     * drops the apostrophe in {@code don't} where PDFBox keeps it.
     */
    private static String canon(String w) {
        return w.replace("-", "").replace("'", "");
    }

    private static List<String> text(List<TextBlock> blocks) {
        List<String> words = new ArrayList<>(blocks.size());
        for (TextBlock b : blocks) {
            String w = b.getWord();
            if (w != null) words.add(w);
        }
        return words;
    }

    /** Normalizes free text to a word list (lowercase, no punctuation). */
    private static List<String> normalize(String s) {
        List<String> out = new ArrayList<>();
        for (String w : s.split("\\s+")) {
            w = normalizeWord(w);
            if (!w.isEmpty()) out.add(w);
        }
        return out;
    }

    private static List<String> normalize(List<String> words) {
        List<String> out = new ArrayList<>(words.size());
        for (String w : words) {
            w = normalizeWord(w);
            out.add(w);
        }
        return out;
    }

    private static String normalizeWord(String w) {
        String x = w.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9'\\-]", "");
        while (x.length() > 1 && (x.endsWith("-") || x.endsWith("'"))) {
            x = x.substring(0, x.length() - 1);
        }
        while (x.length() > 1 && (x.startsWith("-") || x.startsWith("'"))) {
            x = x.substring(1);
        }
        return x;
    }

    private static double clamp(double v) {
        return Math.max(0, Math.min(1, v));
    }
}