package com.mrcpdf.quality.training;

import com.mrcpdf.quality.model.QualityModel;
import java.nio.file.Path;

/**
 * Writes the synthetic training corpus pages as one labeled preview sheet per
 * doc in a single PDF, so the clean/degraded variants (with their synthetic
 * quality score) can be eyeballed.
 *
 * <p>This is the reachable entry point for score-conditioned sampling on the
 * synthetic side: {@code --steer} turns on the {@link ScoreSampler}, and the
 * preview is the only artifact that shows what a steered corpus looks like page
 * by page.  It is off by default so the dump matches the corpus {@code
 * TrainModel} builds.
 *
 * Usage: SynthPageDump [--docs N] [--pages N] [--seed S]
 *                      [--out build/synthetic-pages.pdf]
 *                      [--steer] [--steer-lo F] [--steer-hi F]
 *                      [--steer-dims visual,mask,bg]
 */
public final class SynthPageDump {

    public static void main(String[] args) throws Exception {
        int docs = 25;
        int pagesPerDoc = 20;
        long seed = 42L;
        Path out = Path.of("build/synthetic-pages.pdf");
        boolean steer = false;
        double steerLo = ScoreSampler.DEFAULT_LO;
        double steerHi = ScoreSampler.DEFAULT_HI;
        String steerDims = "visual,mask,bg";
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--docs" -> docs = Integer.parseInt(args[++i]);
                case "--pages" -> pagesPerDoc = Integer.parseInt(args[++i]);
                case "--seed" -> seed = Long.parseLong(args[++i]);
                case "--out" -> out = Path.of(args[++i]);
                case "--steer" -> steer = true;
                case "--no-steer" -> steer = false;
                case "--steer-lo" -> steerLo = Double.parseDouble(args[++i]);
                case "--steer-hi" -> steerHi = Double.parseDouble(args[++i]);
                case "--steer-dims" -> steerDims = args[++i];
                default -> {
                    System.err.println("Unknown arg: " + args[i]);
                    System.err.println("Usage: SynthPageDump [--docs N] [--pages N] [--seed S] "
                            + "[--out <pdf>] [--steer] [--steer-lo F] [--steer-hi F] "
                            + "[--steer-dims visual,mask,bg]");
                    return;
                }
            }
        }
        ScoreSampler.Steering steering = steer
                ? new ScoreSampler.Steering(true, steerLo, steerHi,
                        ScoreSampler.DEFAULT_TOLERANCE, ScoreSampler.DEFAULT_MAX_ATTEMPTS,
                        RealTrainDump.parseDims(steerDims))
                : null;
        SyntheticCorpus.generate(null, QualityModel.VARIANT_IMAGE_ONLY, docs, pagesPerDoc - 1,
                seed, out, System.out, steering);
        System.out.println("Wrote " + out.toAbsolutePath());
    }

    private SynthPageDump() {
    }
}