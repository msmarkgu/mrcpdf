package com.mrcpdf.quality.training;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;

/**
 * Extracts the base pages of a real-corpus dump into a single review PDF, so
 * the corpus can be eyeballed without paging through 20 grades per base page.
 *
 * <p>{@code dumpRealCorpus} writes one output page per (base page x grade), so
 * the Nth base page is buried at page {@code N x pagesPerDoc}. This tool pulls
 * out only the {@code clean} rows of the variant map and copies those pages out
 * with {@link PDDocument#importPage} — a byte-for-byte copy of the already
 * embedded JPEG streams, so nothing is re-rendered and no quality is lost.
 * (Re-dumping with {@code --pages 2} would cost another full pass over every
 * base page to reproduce the same pixels.)
 *
 * <p>Writes an index CSV {@code outPage,doc,baseFile,basePage,variantPage} so
 * every review page is traceable to its source document and to its position in
 * the full dump. The variant map has no base-page column, so {@code --log} (the
 * dump's own stdout, which prints {@code real<N> p<M>: render ...}) is the only
 * source for {@code basePage}; when it is absent the PDF is still written and
 * {@code basePage} comes out empty with a warning.
 *
 * <p>Usage: ExportRealBasePages --variant-map build/real-corpus-variant-map.csv
 *                              [--log build/corpus-full.log]
 *                              [--available build/real-bases/available.txt]
 *                              [--out build/real-base-pages.pdf]
 *                              [--index build/real-base-pages.csv]
 */
public final class ExportRealBasePages {

    /** Matches the per-base-page progress line {@code RealSrcDump} prints. */
    private static final Pattern LOG_LINE =
            Pattern.compile("^\\s*(real\\d+) p(\\d+): render .*$");

    public static void main(String[] args) throws IOException {
        Path variantMap = null;
        Path src = null;
        Path log = null;
        Path available = null;
        Path out = null;
        Path index = null;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--variant-map" -> variantMap = Path.of(args[++i]);
                case "--src" -> src = Path.of(args[++i]);
                case "--log" -> log = Path.of(args[++i]);
                case "--available" -> available = Path.of(args[++i]);
                case "--out" -> out = Path.of(args[++i]);
                case "--index" -> index = Path.of(args[++i]);
                default -> {
                    System.err.println("Unknown arg: " + args[i]);
                    System.err.println("Usage: ExportRealBasePages --variant-map <csv> [--src <pdf>] "
                            + "[--log <txt>] [--available <txt>] [--out <pdf>] [--index <csv>]");
                    return;
                }
            }
        }
        if (variantMap == null) {
            System.err.println("--variant-map is required");
            return;
        }
        if (src == null) {
            src = Path.of("build/real-corpus-src.pdf");
        }
        if (!Files.isReadable(src)) {
            System.err.println("--src is not readable: " + src);
            return;
        }
        if (out == null) {
            out = Path.of("build/real-base-pages.pdf");
        }
        if (index == null) {
            index = Path.of("build/real-base-pages.csv");
        }

        List<CleanRow> rows = readCleanRows(variantMap);
        if (rows.isEmpty()) {
            System.err.println("No clean rows in " + variantMap);
            return;
        }
        Deque<String> basePages = log != null ? readLogBasePages(log) : null;
        if (basePages == null) {
            System.err.println("WARN: no usable --log; basePage column will be empty");
        }
        List<String> availableFiles =
                available != null && Files.isReadable(available) ? readLines(available) : null;
        if (availableFiles == null) {
            System.err.println("WARN: no usable --available; baseFile column will be empty");
        }

        Files.createDirectories(out.toAbsolutePath().getParent());
        StringBuilder csv = new StringBuilder("outPage,doc,baseFile,basePage,variantPage\n");
        int copied = 0;
        try (PDDocument srcDoc = Loader.loadPDF(src.toFile());
             PDDocument dst = new PDDocument()) {
            for (CleanRow row : rows) {
                int srcPage = row.variantPage() - 1;
                if (srcPage < 0 || srcPage >= srcDoc.getNumberOfPages()) {
                    System.err.println("WARN: " + row.doc() + " variant page "
                            + row.variantPage() + " is outside the source dump, skipping");
                    continue;
                }
                dst.importPage(srcDoc.getPage(srcPage));
                copied++;
                String baseFile = availableFiles != null && docIndex(row.doc()) < availableFiles.size()
                        ? availableFiles.get(docIndex(row.doc())) : "";
                String basePage = basePages == null || basePages.isEmpty() ? "" : basePages.pop();
                csv.append(copied).append(',').append(row.doc()).append(',')
                        .append(baseFile).append(',').append(basePage).append(',')
                        .append(row.variantPage()).append('\n');
            }
            dst.save(out.toFile());
        }
        Files.writeString(index, csv.toString(), StandardCharsets.US_ASCII);

        System.out.println("Wrote " + out.toAbsolutePath() + " (" + copied + " base pages)");
        System.out.println("Wrote " + index.toAbsolutePath());
        if (basePages != null && !basePages.isEmpty()) {
            System.err.println("WARN: " + basePages.size() + " log entries left unmatched");
        }
    }

    private record CleanRow(String doc, int variantPage) {
    }

    private static List<CleanRow> readCleanRows(Path map) throws IOException {
        List<CleanRow> out = new ArrayList<>();
        for (String line : Files.readAllLines(map, StandardCharsets.US_ASCII)) {
            if (line.isBlank() || line.startsWith("page,")) continue;
            String[] c = line.split(",", 6);
            if (c.length < 3) {
                throw new IllegalArgumentException("Bad map row: " + line);
            }
            if (!c[2].equals("clean")) continue;
            out.add(new CleanRow(c[1], Integer.parseInt(c[0])));
        }
        return out;
    }

    /** Returns each base page index in dump order, so it can be popped per row. */
    private static Deque<String> readLogBasePages(Path log) throws IOException {
        Deque<String> basePages = new ArrayDeque<>();
        for (String line : Files.readAllLines(log, StandardCharsets.UTF_8)) {
            Matcher m = LOG_LINE.matcher(line);
            if (m.matches()) {
                basePages.add(m.group(2));
            }
        }
        return basePages;
    }

    private static List<String> readLines(Path p) throws IOException {
        List<String> out = new ArrayList<>();
        for (String line : Files.readAllLines(p, StandardCharsets.UTF_8)) {
            String t = line.trim();
            if (!t.isEmpty() && !t.startsWith("#")) {
                out.add(t.substring(t.lastIndexOf('/') + 1));
            }
        }
        return out;
    }

    private static int docIndex(String doc) {
        int i = doc.lastIndexOf("real");
        if (i < 0) return -1;
        try {
            return Integer.parseInt(doc.substring(i + 4));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private ExportRealBasePages() {
    }
}
