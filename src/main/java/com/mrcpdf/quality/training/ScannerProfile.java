package com.mrcpdf.quality.training;

/**
 * Immutable degradation profile for {@link ScannerSimulator}: every parameter a
 * synthetic scanner applies to a perfect original, in physical order.  Length
 * quantities are in pixels at the base render DPI (300); brightness quantities
 * are in 8-bit gray levels unless labeled as [0,1] fractions.
 *
 * <p>Fields mirror the natural scan chain (see the notes in
 * {@code notes/simulate-realworld-scans.md}): geometry (curl, skew), capture
 * resolution (downscale), optics (blur/motion), illumination, paper texture,
 * bleed-through, toner (contrast/gamma/ink spread), sensor (noise, salt &
 * pepper, fixed pattern, streaks/dust), and JPEG recompression.
 */
public record ScannerProfile(
        double curl,
        double skewDeg,
        double downscale,
        double blurSigma,
        double motionBlurLen,
        double illumination,
        double paperOpacity,
        double bleedThrough,
        double noiseSigma,
        double saltPepperFrac,
        double fixedPattern,
        int streakCount,
        int dustCount,
        double blackLevel,
        double whiteLevel,
        double gamma,
        int inkSpread,
        float jpegQuality) {

    /** Identity profile: the simulator's {@code apply} returns the input unchanged. */
    public static final ScannerProfile CLEAN = new ScannerProfile(
            0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 0, 1f);

    /** Whether the profile changes anything at all. */
    public boolean isClean() {
        return this.equals(CLEAN);
    }
}