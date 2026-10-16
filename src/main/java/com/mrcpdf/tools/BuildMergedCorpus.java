package com.mrcpdf.tools;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Builds the merged quality-gate training corpus: the synthetic corpus rows
 * verbatim, followed by the real clean-page rows pulled from the
 * eval/real-world feature CSVs (format "doc,page,composite,f1..f11").
 *
 * The real block is emitted in {@link #REAL_ORDER} (their row order in the
 * feature files, i.e. ascending page), with {@link #EXCLUDED_PAGES} dropped:
 * maorilandandoth01adamgoog p7 (blank page) and estivoautumnalr01craigoog p8
 * (blank low-contrast figure page).  fbis0396 and littleblackbook (handwriting)
 * are intentionally not in the order list and are never included.
 *
 * Number cells are copied byte-for-byte from the source CSVs so the merge is
 * lossless and reproducible.
 *
 * Usage: BuildMergedCorpus --synthetic build/quality-corpus.csv
 *                          --features eval/real-world/features
 *                          --out build/quality-corpus-real.csv
 */
public final class BuildMergedCorpus {

    private static final String SYNTHETIC_HEADER =
            "doc,label,blurSigmaPx,edgeWidthCV,hiFreqEnergy,laplacianVar,otsuSeparability,"
                    + "michelsonContrast,histRange,bgUniformity,noiseSigma,snr,inkCoverage";
    private static final String FEATURES_HEADER =
            "doc,page,composite,blurSigmaPx,edgeWidthCV,hiFreqEnergy,laplacianVar,otsuSeparability,"
                    + "michelsonContrast,histRange,bgUniformity,noiseSigma,snr,inkCoverage";

    private static final List<String> REAL_ORDER = List.of(
            "claudes",
            "bookofproof",
            "fiverings",
            "poganucpeopleth02stowgoog",
            "adefencepoetry00shelgoog",
            "fourdimensional00braggoog",
            "maorilandandoth01adamgoog",
            "ephraimandhelah00hoddgoog",
            "estivoautumnalr01craigoog");

    private static final Map<String, Set<Integer>> EXCLUDED_PAGES = Map.of(
            "maorilandandoth01adamgoog", Set.of(7),
            "estivoautumnalr01craigoog", Set.of(8));

    public static void main(String[] args) throws IOException {
        Path synthetic = null;
        Path featuresDir = null;
        Path out = null;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--synthetic" -> synthetic = Path.of(args[++i]);
                case "--features" -> featuresDir = Path.of(args[++i]);
                case "--out" -> out = Path.of(args[++i]);
                default -> {
                    System.err.println("Unknown arg: " + args[i]);
                    System.err.println("Usage: BuildMergedCorpus --synthetic <csv> --features <dir> --out <csv>");
                    return;
                }
            }
        }
        if (synthetic == null || featuresDir == null || out == null) {
            System.err.println("Missing required --synthetic/--features/--out");
            System.err.println("Usage: BuildMergedCorpus --synthetic <csv> --features <dir> --out <csv>");
            return;
        }

        StringBuilder merged = new StringBuilder();
        int lineCount = 0;
        merged.append(SYNTHETIC_HEADER).append('\n');
        lineCount++;

        List<String> syntheticLines = Files.readAllLines(synthetic, StandardCharsets.US_ASCII);
        if (!syntheticLines.isEmpty() && !syntheticLines.get(0).equals(SYNTHETIC_HEADER)) {
            throw new IllegalArgumentException("Unexpected synthetic header: " + syntheticLines.get(0));
        }
        for (int i = 1; i < syntheticLines.size(); i++) {
            merged.append(syntheticLines.get(i)).append('\n');
            lineCount++;
        }

        Map<String, List<String>> realByDoc = new TreeMap<>();
        List<Path> files;
        try (var stream = Files.list(featuresDir)) {
            files = stream.sorted().toList();
        }
        for (Path f : files) {
            String name = f.getFileName().toString();
            if (!name.endsWith(".features.csv")) {
                continue;
            }
            List<String> lines = Files.readAllLines(f, StandardCharsets.US_ASCII);
            if (!lines.isEmpty() && !lines.get(0).equals(FEATURES_HEADER)) {
                throw new IllegalArgumentException("Unexpected features header in " + f + ": " + lines.get(0));
            }
            for (int i = 1; i < lines.size(); i++) {
                String line = lines.get(i);
                String[] col = line.split(",", 14);
                if (col.length < 14) {
                    throw new IllegalArgumentException("Short row in " + f + ": " + line);
                }
                realByDoc.computeIfAbsent(col[0], k -> new java.util.ArrayList<>()).add(line);
            }
        }

        for (String doc : REAL_ORDER) {
            List<String> rows = realByDoc.get(doc);
            if (rows == null) {
                throw new IllegalArgumentException("Ordered real doc missing from features: " + doc);
            }
            Set<Integer> excluded = EXCLUDED_PAGES.getOrDefault(doc, Set.of());
            for (String row : rows) {
                String[] col = row.split(",", 14);
                int page = Integer.parseInt(col[1]);
                if (excluded.contains(page)) {
                    continue;
                }
                merged.append("real-").append(col[0]).append(",0");
                for (int f = 3; f < col.length; f++) {
                    merged.append(',').append(col[f]);
                }
                merged.append('\n');
                lineCount++;
            }
        }

        Path abs = out.toAbsolutePath();
        if (abs.getParent() != null) {
            Files.createDirectories(abs.getParent());
        }
        Files.writeString(abs, merged.toString(), StandardCharsets.US_ASCII);
        System.out.println("Wrote " + out + " (" + lineCount + " lines)");
    }

    private BuildMergedCorpus() {
    }
}