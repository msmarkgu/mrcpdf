package com.mrcpdf.util;

import java.awt.image.BufferedImage;

/**
 * Shared low-level image primitives used by the segmentation pipeline and the
 * text-quality scorer.  Kept in {@code com.mrcpdf.util} so both the pipeline
 * and quality packages can reuse the same conversions without exposing
 * package-private implementation details of {@code ImageSegmenter}.
 */
public final class ImageOps {

    private ImageOps() {
    }

    /**
     * Converts a flat ARGB int array to a grayscale int array
     * (0 = black, 255 = white) using ITU-R BT.601 luma with integer
     * arithmetic.  Integer coefficients (scaled by 256):
     * 0.299*256 = 77, 0.587*256 = 150, 0.114*256 = 29; 77+150+29 = 256,
     * so (R=G=B) maps to itself.
     */
    public static int[] toGrayscale(int[] rgba) {
        int[] gray = new int[rgba.length];
        for (int i = 0; i < rgba.length; i++) {
            int rgb = rgba[i];
            int r = (rgb >> 16) & 0xFF;
            int g = (rgb >> 8) & 0xFF;
            int b = rgb & 0xFF;
            gray[i] = (77 * r + 150 * g + 29 * b) >> 8;
        }
        return gray;
    }

    /** Reads a BufferedImage's pixels into a flat grayscale int array. */
    public static int[] toGrayscale(BufferedImage image) {
        int width = image.getWidth();
        int height = image.getHeight();
        int[] rgba = new int[width * height];
        image.getRGB(0, 0, width, height, rgba, 0, width);
        return toGrayscale(rgba);
    }

    /**
     * Otsu statistics over an 8-bit grayscale array.
     *
     * @param threshold        threshold t maximizing between-class variance
     * @param separability     between-variance / total-variance in [0, 1]
     *                         (0 when the image is uniform)
     * @param betweenVariance  normalized between-class variance at the optimum
     *                         split, {@code pB * pF * (muB - muF)^2}
     * @param totalVariance    total intensity variance
     */
    public record OtsuStats(int threshold,
                            double separability,
                            double betweenVariance,
                            double totalVariance) {
    }

    /**
     * Computes Otsu's optimum threshold plus the bimodality statistics used by
     * the quality scorer.  The threshold search is identical to the classic
     * implementation: exhaustively scan all splits t = 0..255 and pick the one
     * maximizing the (unscaled) between-class variance {@code wB * wF * (muB -
     * muF)^2}, where the weights are class pixel counts.  The separability
     * scores are computed from the normalized proportion-based variance.
     */
    public static OtsuStats otsuStats(int[] pixels) {
        int[] histogram = new int[256];
        for (int p : pixels) {
            histogram[p]++;
        }

        int total = pixels.length;
        if (total == 0) {
            return new OtsuStats(0, 0, 0, 0);
        }

        double sum = 0;
        for (int i = 0; i < 256; i++) {
            sum += (double) i * histogram[i];
        }
        double meanAll = sum / total;
        double sumSq = 0;
        for (int i = 0; i < 256; i++) {
            sumSq += (double) i * i * histogram[i];
        }
        double totalVariance = sumSq / total - meanAll * meanAll;
        if (totalVariance < 0) {
            totalVariance = 0;
        }

        double sumB = 0;
        int wB = 0;
        double maxVariance = 0;
        int threshold = 0;
        double bestBetween = 0;

        for (int i = 0; i < 256; i++) {
            wB += histogram[i];
            if (wB == 0) continue;
            int wF = total - wB;
            if (wF == 0) break;

            sumB += (double) i * histogram[i];
            double meanB = sumB / wB;
            double meanF = (sum - sumB) / wF;
            double variance = (double) wB * wF * (meanB - meanF) * (meanB - meanF);

            if (variance > maxVariance) {
                maxVariance = variance;
                threshold = i;
                double pB = (double) wB / total;
                double pF = (double) wF / total;
                bestBetween = pB * pF * (meanB - meanF) * (meanB - meanF);
            }
        }

        double separability = totalVariance > 0
                ? Math.max(0, Math.min(1, bestBetween / totalVariance))
                : 0;
        return new OtsuStats(threshold, separability, bestBetween, totalVariance);
    }
}