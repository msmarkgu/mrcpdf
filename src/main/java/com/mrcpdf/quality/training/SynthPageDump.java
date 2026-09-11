package com.mrcpdf.quality.training;

import com.mrcpdf.quality.model.QualityModel;
import java.nio.file.Path;

/**
 * Writes the synthetic training corpus pages as one labeled preview sheet per
 * doc in a single PDF, so the clean/mild/degraded variants can be eyeballed.
 *
 * Usage: SynthPageDump [--docs N] [--seed S] [--out build/synthetic-pages.pdf]
 */
public final class SynthPageDump {

    public static void main(String[] args) throws Exception {
        int docs = 20;
        long seed = 42L;
        Path out = Path.of("build/synthetic-pages.pdf");
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--docs" -> docs = Integer.parseInt(args[++i]);
                case "--seed" -> seed = Long.parseLong(args[++i]);
                case "--out" -> out = Path.of(args[++i]);
                default -> {
                    System.err.println("Unknown arg: " + args[i]);
                    System.err.println("Usage: SynthPageDump [--docs N] [--seed S] [--out <pdf>]");
                    return;
                }
            }
        }
        SyntheticCorpus.generate(null, QualityModel.VARIANT_IMAGE_ONLY, docs, seed, out, System.out);
        System.out.println("Wrote " + out.toAbsolutePath());
    }

    private SynthPageDump() {
    }
}