package com.mrcpdf.quality.training;

import java.awt.image.BufferedImage;
import java.util.ArrayDeque;
import java.util.Deque;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;

import com.mrcpdf.util.ImageOps;

/**
 * Synthetic scanner: applies a {@link ScannerProfile} to a perfect digital page
 * raster and returns a scanned-looking grayscale image, mimicking the effects a
 * real capture chain introduces (curl/skew, resolution loss, optics blur, lamp
 * shading, paper texture, bleed-through, toner loss, sensor noise, streaks/dust,
 * and JPEG artifacts) as described in {@code notes/simulate-realworld-scans.md}.
 *
 * <p>The chain runs in physical order; all effects are deterministic given the
 * caller's {@link java.util.Random}.  All intermediate work is done on a flat
 * grayscale {@code int[]} (0 = black, 255 = white) and the result is returned
 * as a {@code TYPE_BYTE_GRAY} image so the corpus stays in the same color space
 * as the production grayscale scans.
 */
public final class ScannerSimulator {

    private static final int MIN_V = 0;
    private static final int MAX_V = 255;
    private static final double MAX_SALT_PIXELS = 0.004;

    private ScannerSimulator() {
    }

    /** Applies the profile's full degradation chain to {@code src}. */
    public static BufferedImage apply(BufferedImage src, ScannerProfile p, java.util.Random rng) {
        return fromGray(rasterize(src, p, rng), src.getWidth(), src.getHeight());
    }

    // ──────────────────────────────────────────────────────────────────────
    // Chain
    // ──────────────────────────────────────────────────────────────────────

    static int[] rasterize(BufferedImage src, ScannerProfile p, java.util.Random rng) {
        int w = src.getWidth();
        int h = src.getHeight();
        int[] g = ImageOps.toGrayscale(src);

        if (p.curl() > 0 || p.skewDeg() > 0) {
            g = warp(g, w, h, p.skewDeg(), p.curl(), cornerBg(g, w, h));
        }
        if (p.downscale() < 1.0) {
            g = resample(g, w, h, p.downscale());
        }
        if (p.inkSpread() != 0) {
            // Ink bleed happens on the paper (toner diffusion) and is captured
            // by the optics, so it runs before the blur passes: the final blur
            // softens the hard dilation edges instead of leaving crisp new
            // boundaries that would pollute the laplacian / hi-freq stats.
            g = rankFilter(g, w, h, Math.abs(p.inkSpread()), p.inkSpread() > 0);
        }
        if (p.blurSigma() > 1e-3) {
            g = defocusBlur(g, w, h, p.blurSigma(), rng);
        }
        if (p.motionBlurLen() >= 1) {
            g = motionBlur(g, w, h, p.motionBlurLen(), rng.nextDouble() * Math.PI, rng);
        }
        if (p.illumination() > 1e-3) {
            g = illuminate(g, w, h, p.illumination(), rng);
        }
        if (p.paperOpacity() > 1e-3) {
            g = applyPaper(g, w, h, p.paperOpacity(), rng);
        }
        if (p.bleedThrough() > 1e-3) {
            g = bleedThrough(g, w, h, p.bleedThrough());
        }
        if (p.gamma() != 1 || p.blackLevel() > 0 || p.whiteLevel() > 0) {
            g = applyContrast(g, p.gamma(), p.blackLevel(), p.whiteLevel());
        }
        if (p.streakCount() > 0 || p.dustCount() > 0) {
            g = addStreaks(g, w, h, p.streakCount(), p.dustCount(), rng);
        }
        if (p.jpegQuality() < 1.0f) {
            g = recompressJpeg(g, w, h, p.jpegQuality());
        }
        // Sensor noise goes after the JPEG pass so the injected noise is not
        // erased by the block-DCT quantization; the clean tiers keep only the
        // ring/upscale residue, so noiseSigma/snr rise with degradation.
        if (p.noiseSigma() > 0.01 || p.saltPepperFrac() > 0 || p.fixedPattern() > 0) {
            g = addSensorNoise(g, w, h, p.noiseSigma(), p.saltPepperFrac(), p.fixedPattern(), rng);
        }
        return g;
    }

    private static BufferedImage fromGray(int[] g, int w, int h) {
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_BYTE_GRAY);
        out.getRaster().setSamples(0, 0, w, h, 0, g);
        return out;
    }

    // ──────────────────────────────────────────────────────────────────────
    // Geometry: skew + page-curl
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Undo-skew rotation (theta in radians, around the page center) followed by
     * undo of a horizontal cylindrical curl, then bilinear sample.  Out-of-bounds
     * pixels take {@code bg}.
     */
    static int[] warp(int[] g, int w, int h, double skewDeg, double curl, int bg) {
        if (skewDeg == 0 && curl == 0) return g;
        double theta = Math.toRadians(skewDeg);
        double cos = Math.cos(theta);
        double sin = Math.sin(theta);
        double cx = 0.5 * w;
        double cy = 0.5 * h;
        double d = curl * w * 0.04; // horizontal edge bow, px
        int[] out = new int[g.length];
        for (int y = 0; y < h; y++) {
            double dy = y - cy;
            for (int x = 0; x < w; x++) {
                double dx = x - cx;
                // undo rotation
                double rx = cos * dx - sin * dy + cx;
                double ry = sin * dx + cos * dy + cy;
                // undo curl (x-only shear peaking at the page edges)
                double hx = (rx - cx) / (0.5 * w);
                double cu = d * (hx * hx);
                double sx = rx - cu;
                double sy = ry;
                out[y * w + x] = bilinear(g, w, h, sx, sy, bg);
            }
        }
        return out;
    }

    private static int bilinear(int[] g, int w, int h, double x, double y, int bg) {
        int x0 = (int) Math.floor(x);
        int y0 = (int) Math.floor(y);
        if (x0 < 0 || y0 < 0 || x0 + 1 >= w || y0 + 1 >= h) {
            if (x < 0 || y < 0 || x >= w || y >= h) return bg;
            return g[y0 * w + x0];
        }
        double fx = x - x0;
        double fy = y - y0;
        int a = g[y0 * w + x0];
        int b = g[y0 * w + x0 + 1];
        int c = g[(y0 + 1) * w + x0];
        int d = g[(y0 + 1) * w + x0 + 1];
        return (int) Math.round(
                (1 - fy) * ((1 - fx) * a + fx * b) + fy * ((1 - fx) * c + fx * d));
    }

    /** Average gray of the 2px border — used to fill skew margins. */
    private static int cornerBg(int[] g, int w, int h) {
        long sum = 0;
        long n = 0;
        for (int x = 0; x < w; x++) {
            sum += g[x] + g[(h - 1) * w + x];
            n += 2;
        }
        for (int y = 0; y < h; y++) {
            sum += g[y * w] + g[y * w + w - 1];
            n += 2;
        }
        return (int) (sum / Math.max(1, n));
    }

    // ──────────────────────────────────────────────────────────────────────
    // Resolution: capture downscale, then restore base DPI
    // ──────────────────────────────────────────────────────────────────────

    private static int[] resample(int[] g, int w, int h, double scale) {
        int sw = Math.max(1, (int) Math.round(w * scale));
        int sh = Math.max(1, (int) Math.round(h * scale));
        int[] small = new int[sw * sh];
        for (int y = 0; y < sh; y++) {
            double sy = (y + 0.5) * h / sh - 0.5;
            for (int x = 0; x < sw; x++) {
                double sx = (x + 0.5) * w / sw - 0.5;
                small[y * sw + x] = bilinear(g, w, h, sx, sy, 255);
            }
        }
        int[] out = new int[w * h];
        for (int y = 0; y < h; y++) {
            double sy = (y + 0.5) * sh / h - 0.5;
            for (int x = 0; x < w; x++) {
                double sx = (x + 0.5) * sw / w - 0.5;
                out[y * w + x] = bilinear(small, sw, sh, sx, sy, 255);
            }
        }
        return out;
    }

    // ──────────────────────────────────────────────────────────────────────
    // Optics: gaussian + motion blur
    // ──────────────────────────────────────────────────────────────────────

    /** Separable gaussian blur (two 1-D passes over the radius-3sigma kernel). */
    static int[] gaussianBlur(int[] g, int w, int h, double sigma) {
        int radius = Math.max(1, (int) Math.ceil(sigma * 3));
        double[] kernel = gaussianKernel(sigma, radius);
        int[] tmp = new int[g.length];
        horizontal(g, tmp, w, h, kernel, radius);
        return horizontal(tmp, new int[g.length], w, h, kernel, radius);
    }

    /**
     * Uniform gaussian defocus.  Kept as a single clean primitive: spatially
     * varying defocus (field curvature, smudge zones) only succeeds in *narrowing*
     * the mag&gt;30 edge population to the single surviving blur radius, which
     * collapses edgeWidthCV instead of raising it.
     */
    static int[] defocusBlur(int[] g, int w, int h, double sigma, java.util.Random rng) {
        return gaussianBlur(g, w, h, sigma);
    }

    private static double smoothstep(double a, double b, double x) {
        double t = Math.max(0, Math.min(1, (x - a) / (b - a)));
        return t * t * (3 - 2 * t);
    }

    private static int[] sepBlur(int[] g, int w, int h, double sigma) {
        int radius = Math.max(1, (int) Math.ceil(sigma * 3));
        double[] kernel = gaussianKernel(sigma, radius);
        int[] tmp = new int[g.length];
        horizontal(g, tmp, w, h, kernel, radius);
        return horizontal(tmp, new int[g.length], w, h, kernel, radius);
    }

    private static double[] gaussianKernel(double sigma, int radius) {
        double[] k = new double[2 * radius + 1];
        double sum = 0;
        for (int i = -radius; i <= radius; i++) {
            double v = Math.exp(-(i * i) / (2 * sigma * sigma));
            k[i + radius] = v;
            sum += v;
        }
        for (int i = 0; i < k.length; i++) k[i] /= sum;
        return k;
    }

    private static int[] horizontal(int[] g, int[] out, int w, int h, double[] k, int r) {
        for (int y = 0; y < h; y++) {
            int row = y * w;
            for (int x = 0; x < w; x++) {
                double acc = 0;
                for (int i = -r; i <= r; i++) {
                    int xi = x + i;
                    if (xi < 0) xi = 0;
                    else if (xi >= w) xi = w - 1;
                    acc += k[i + r] * g[row + xi];
                }
                out[row + x] = (int) Math.round(acc);
            }
        }
        return out;
    }

    /** Directional box blur along a random-angle line — motion of the scanner. */
    static int[] motionBlur(int[] g, int w, int h, double len, double angle, java.util.Random rng) {
        int taps = Math.max(3, (int) Math.round(len));
        double dx = Math.cos(angle);
        double dy = Math.sin(angle);
        int[] out = new int[g.length];
        double half = (taps - 1) / 2.0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                double acc = 0;
                for (int t = 0; t < taps; t++) {
                    double ox = x + dx * (t - half);
                    double oy = y + dy * (t - half);
                    acc += bilinear(g, w, h, ox, oy, g[y * w + x]);
                }
                out[y * w + x] = (int) Math.round(acc / taps);
            }
        }
        return out;
    }

    // ──────────────────────────────────────────────────────────────────────
    // Uneven illumination (scanner lamp / page shadows)
    // ──────────────────────────────────────────────────────────────────────

    static int[] illuminate(int[] g, int w, int h, double strength, java.util.Random rng) {
        double fx = rng.nextDouble() * 0.4 + 0.3;
        double fy = rng.nextDouble() * 0.4 + 0.3;
        double px = rng.nextDouble() * Math.PI * 2;
        double py = rng.nextDouble() * Math.PI * 2;
        double ax = 0.5 + rng.nextDouble();
        double ay = 0.5 + rng.nextDouble();
        double gradK = (rng.nextBoolean() ? 1 : -1) * (0.5 + rng.nextDouble());
        double radialK = (rng.nextBoolean() ? 1 : -1) * (0.5 + rng.nextDouble());

        int[] out = new int[g.length];
        // Precompute a coarse field, then bilinear-sample per pixel.
        int cw = 64;
        int ch = 64;
        double[] field = new double[cw * ch];
        double fieldMax = 0;
        for (int j = 0; j < ch; j++) {
            double ny = j / (double) (ch - 1);
            for (int i = 0; i < cw; i++) {
                double nx = i / (double) (cw - 1);
                double v = ax * Math.sin(2 * Math.PI * fx * nx + px)
                        + ay * Math.sin(2 * Math.PI * fy * ny + py)
                        + gradK * (nx + ny - 1)
                        + radialK * (Math.hypot(nx - 0.5, ny - 0.5) - 0.7071);
                field[j * cw + i] = v;
                fieldMax = Math.max(fieldMax, Math.abs(v));
            }
        }
        if (fieldMax > 0) {
            for (int i = 0; i < field.length; i++) field[i] /= fieldMax;
        }
        double[] rowField = new double[w];
        for (int y = 0; y < h; y++) {
            double ny = (y / (double) (h - 1)) * (ch - 1);
            int fy0 = (int) ny;
            double fyf = ny - fy0;
            int fy1 = Math.min(ch - 1, fy0 + 1);
            for (int x = 0; x < w; x++) {
                double nx = (x / (double) (w - 1)) * (cw - 1);
                int fx0 = (int) nx;
                double fxf = nx - fx0;
                int fx1 = Math.min(cw - 1, fx0 + 1);
                double v = (1 - fyf) * ((1 - fxf) * field[fy0 * cw + fx0] + fxf * field[fy0 * cw + fx1])
                        + fyf * ((1 - fxf) * field[fy1 * cw + fx0] + fxf * field[fy1 * cw + fx1]);
                rowField[x] = 1 + strength * v;
            }
            int row = y * w;
            for (int x = 0; x < w; x++) {
                out[row + x] = clamp((int) Math.round(g[row + x] * rowField[x]));
            }
        }
        return out;
    }

    // ──────────────────────────────────────────────────────────────────────
    // Paper texture
    // ──────────────────────────────────────────────────────────────────────

    private static int[] applyPaper(int[] g, int w, int h, double opacity, java.util.Random rng) {
        int sw = Math.max(16, w / 6);
        int sh = Math.max(16, h / 6);
        int[] tex = paperTexture(sw, sh, rng);
        int[] out = new int[g.length];
        for (int y = 0; y < h; y++) {
            double sy = (y + 0.5) / h * sh - 0.5;
            int row = y * w;
            for (int x = 0; x < w; x++) {
                double sx = (x + 0.5) / w * sw - 0.5;
                int t = bilinear(tex, sw, sh, sx, sy, 235);
                int v = g[row + x];
                // multiply: fibers darken; blend toward the texture per opacity
                v = (int) Math.round(v * (1 - opacity) + (v * t / 255.0) * opacity);
                out[row + x] = clamp(v);
            }
        }
        return out;
    }

    private static int[] paperTexture(int w, int h, java.util.Random rng) {
        int[] t = new int[w * h];
        java.util.Arrays.fill(t, 235);
        int fibers = (w * h) / 220;
        Graphics2DLike g = (x, y, v) -> t[y * w + x] = v;
        for (int i = 0; i < fibers; i++) {
            int x0 = rng.nextInt(w);
            int y0 = rng.nextInt(h);
            int len = 1 + rng.nextInt(5);
            int x1 = clampInt(x0 + (iflat(rng) < 0.3 ? 0 : (rng.nextInt(7) - 3)), 0, w - 1);
            int y1 = clampInt(y0 + (iflat(rng) < 0.3 ? (rng.nextInt(7) - 3) : 0), 0, h - 1);
            int shade = 205 + rng.nextInt(26);
            drawLine(g, x0, y0, x1, y1, shade);
        }
        // mote speckle
        int specks = (w * h) / 900;
        for (int i = 0; i < specks; i++) {
            int x = rng.nextInt(w);
            int y = rng.nextInt(h);
            int v = t[y * w + x];
            int d = 18 + rng.nextInt(22);
            t[y * w + x] = clamp(rng.nextBoolean() ? v - d : v + d);
        }
        // low-pass so fibers are soft, not gridded
        return boxBlur(t, w, h, 1);
    }

    private interface Graphics2DLike {
        void set(int x, int y, int v);
    }

    private static void drawLine(Graphics2DLike g, int x0, int y0, int x1, int y1, int v) {
        int dx = Math.abs(x1 - x0);
        int dy = Math.abs(y1 - y0);
        int sx = x0 < x1 ? 1 : -1;
        int sy = y0 < y1 ? 1 : -1;
        int err = dx - dy;
        while (true) {
            g.set(x0, y0, v);
            if (x0 == x1 && y0 == y1) break;
            int e2 = 2 * err;
            if (e2 > -dy) {
                err -= dy;
                x0 += sx;
            }
            if (e2 < dx) {
                err += dx;
                y0 += sy;
            }
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // Bleed-through: heavily blurred, mirrored self-ghost
    // ──────────────────────────────────────────────────────────────────────

    static int[] bleedThrough(int[] g, int w, int h, double frac) {
        int[] ghost = boxBlur(g, w, h, 10);
        int[] out = new int[g.length];
        for (int y = 0; y < h; y++) {
            int row = y * w;
            for (int x = 0; x < w; x++) {
                int mn = ghost[y * w + (w - 1 - x)];
                out[row + x] = clamp((int) Math.round(g[row + x] * (1 - frac) + mn * frac));
            }
        }
        return out;
    }

    /** Two-pass (vertical + horizontal) box mean filter. */
    static int[] boxBlur(int[] g, int w, int h, int r) {
        int[] tmp = new int[g.length];
        boxPassH(g, tmp, w, h, r);
        return boxPassV(tmp, new int[g.length], w, h, r);
    }

    private static int[] boxPassH(int[] g, int[] out, int w, int h, int r) {
        for (int y = 0; y < h; y++) {
            int row = y * w;
            long sum = 0;
            for (int x = -r; x <= r; x++) sum += g[row + clampInt(x, 0, w - 1)];
            out[row] = (int) (sum / (2 * r + 1));
            for (int x = 1; x < w; x++) {
                int add = x + r < w ? g[row + x + r] : g[row + w - 1];
                int sub = x - r - 1 >= 0 ? g[row + x - r - 1] : g[row];
                sum += add - sub;
                out[row + x] = (int) (sum / (2 * r + 1));
            }
        }
        return out;
    }

    private static int[] boxPassV(int[] g, int[] out, int w, int h, int r) {
        for (int x = 0; x < w; x++) {
            long sum = 0;
            for (int y = -r; y <= r; y++) sum += g[clampInt(y, 0, h - 1) * w + x];
            out[x] = (int) (sum / (2 * r + 1));
            for (int y = 1; y < h; y++) {
                int add = y + r < h ? g[(y + r) * w + x] : g[(h - 1) * w + x];
                int sub = y - r - 1 >= 0 ? g[(y - r - 1) * w + x] : g[x];
                sum += add - sub;
                out[y * w + x] = (int) (sum / (2 * r + 1));
            }
        }
        return out;
    }

    // ──────────────────────────────────────────────────────────────────────
    // Toner: contrast/gamma loss + ink spread (dilate/erode)
    // ──────────────────────────────────────────────────────────────────────

    static int[] applyContrast(int[] g, double gamma, double blackLevel, double whiteLevel) {
        double range = 1.0 - blackLevel - whiteLevel;
        int[] out = new int[g.length];
        for (int i = 0; i < g.length; i++) {
            double v = g[i] / 255.0;
            if (Math.abs(gamma - 1) > 1e-6) {
                v = Math.pow(v, 1.0 / gamma);
            }
            v = v * range + blackLevel;
            out[i] = clamp((int) Math.round(255 * v));
        }
        return out;
    }

    /**
     * Rank filter by severity-weighted min (dilate → bold ink) or max
     * (erode → faint ink), separable with monotonic queues (O(n) total).
     */
    static int[] rankFilter(int[] g, int w, int h, int r, boolean takeMin) {
        r = Math.min(r, 2);
        int[] tmp = rankPassH(g, w, h, r, takeMin);
        return rankPassV(tmp, w, h, r, takeMin);
    }

    private static int[] rankPassH(int[] g, int w, int h, int r, boolean min) {
        int[] out = new int[g.length];
        Deque<Integer> dq = new ArrayDeque<>();
        for (int y = 0; y < h; y++) {
            int row = y * w;
            dq.clear();
            for (int x = 0; x < w; x++) {
                int v = g[row + x];
                while (!dq.isEmpty() && (min ? g[row + dq.peekLast()] >= v : g[row + dq.peekLast()] <= v)) {
                    dq.pollLast();
                }
                dq.addLast(x);
                while (dq.peekFirst() <= x - (2 * r + 1)) dq.pollFirst();
                out[row + x] = g[row + dq.peekFirst()];
            }
        }
        return out;
    }

    private static int[] rankPassV(int[] g, int w, int h, int r, boolean min) {
        int[] out = new int[g.length];
        Deque<Integer> dq = new ArrayDeque<>();
        for (int x = 0; x < w; x++) {
            dq.clear();
            for (int y = 0; y < h; y++) {
                int v = g[y * w + x];
                while (!dq.isEmpty() && (min ? g[dq.peekLast() * w + x] >= v : g[dq.peekLast() * w + x] <= v)) {
                    dq.pollLast();
                }
                dq.addLast(y);
                while (dq.peekFirst() <= y - (2 * r + 1)) dq.pollFirst();
                out[y * w + x] = g[dq.peekFirst() * w + x];
            }
        }
        return out;
    }

    // ──────────────────────────────────────────────────────────────────────
    // Sensor: gaussian + salt & pepper + fixed-pattern noise
    // ──────────────────────────────────────────────────────────────────────

    static int[] addSensorNoise(int[] g, int w, int h, double sigma, double salt,
                                double fixedPattern, java.util.Random rng) {
        // Paper/sensor noise is added to background pixels only: the background
        // "noiseSigma" feature measures over background, and leaving the ink
        // edges untouched keeps the text-sharpness features (mag/lap edge
        // widths, HF energy, laplacian variance) responsive to blur instead of
        // being swamped by pixel grain.  Local background mirrors
        // FeatureExtractor (95th-percentile tiles, bilinear interpolation).
        int bgTile = 64, bgTol = 12;
        int tilesX = Math.max(1, (w + bgTile - 1) / bgTile);
        int tilesY = Math.max(1, (h + bgTile - 1) / bgTile);
        int[][] levels = new int[tilesY][tilesX];
        for (int ty = 0; ty < tilesY; ty++) {
            int sy = ty * bgTile;
            int ey = Math.min(sy + bgTile, h);
            for (int tx = 0; tx < tilesX; tx++) {
                int sx = tx * bgTile;
                int ex = Math.min(sx + bgTile, w);
                int[] hist = new int[256];
                for (int y = sy; y < ey; y++) {
                    int row = y * w;
                    for (int x = sx; x < ex; x++) {
                        int v = g[row + x];
                        if (v < 0) v = 0;
                        else if (v > 255) v = 255;
                        hist[v]++;
                    }
                }
                int tot = (ex - sx) * (ey - sy);
                int need = Math.max(1, (int) Math.ceil(0.95 * tot));
                int acc = 0, level = 0;
                for (int v = 255; v >= 0 && acc < need; v--) {
                    acc += hist[v];
                    level = v;
                }
                levels[ty][tx] = level;
            }
        }

        double[] colGain = new double[w];
        if (fixedPattern > 0) {
            // Gentle per-column sensor drift (a few percent of tone), smoothed
            // so it reads as a column gradient rather than hard bands.  It is
            // deliberately small: big gains that clamp to 0/255 would inject
            // impulses that saturate the histogram range and the laplacian/
            // mag statistics that must track blur instead.
            for (int x = 0; x < w; x++) colGain[x] = fixedPattern * 0.9 * rng.nextGaussian();
            for (int pass = 0; pass < 3; pass++) {
                double prev = colGain[0];
                for (int x = 1; x < w; x++) {
                    double cur = colGain[x];
                    colGain[x] = 0.5 * colGain[x] + 0.25 * (prev + colGain[Math.min(w - 1, x + 1)]);
                    prev = cur;
                }
            }
            for (int x = 0; x < w; x++) colGain[x] = Math.max(-0.06, Math.min(0.06, colGain[x]));
        }
        // Noise is a whisper of tone grain only.  It must stay under the
        // mag threshold so it never becomes an "edge": the edge-width, HF
        // energy, laplacian-variance and histogram-range features have to
        // track blur/ink spread, not injected pixel noise.  The median3
        // residual (noiseSigma feature) still reads the grain, and the
        // JPEG ringing on the low-quality tiers provides the bulk of the
        // degraded-vs-clean signal there.  No salt/pepper impulses: a bare
        // 0/255 speckle pins the histogram percentiles and inflates the
        // laplacian variance on the very tiers meant to look *smooth*.
        double effSigma = Math.min(2.4, sigma * 1.4);
        int[] out = new int[g.length];
        for (int y = 0; y < h; y++) {
            int row = y * w;
            int yT0 = Math.min(tilesY - 1, y / bgTile);
            int yT1 = Math.min(tilesY - 1, yT0 + 1);
            double fy = yT0 == yT1 ? 0 : (y - yT0 * bgTile) / (double) bgTile;
            for (int x = 0; x < w; x++) {
                int xT0 = Math.min(tilesX - 1, x / bgTile);
                int xT1 = Math.min(tilesX - 1, xT0 + 1);
                double fx = xT0 == xT1 ? 0 : (x - xT0 * bgTile) / (double) bgTile;
                double top = levels[yT0][xT0] * (1 - fx) + levels[yT0][xT1] * fx;
                double bot = levels[yT1][xT0] * (1 - fx) + levels[yT1][xT1] * fx;
                double localBg = top * (1 - fy) + bot * fy;
                int gv = g[row + x];
                if (gv < localBg + 2) { // ink or its halo: leave it untouched
                    out[row + x] = gv;
                    continue;
                }
                double v = gv;
                if (effSigma > 0) v += rng.nextGaussian() * effSigma;
                if (fixedPattern > 0) v *= (1 + colGain[x]);
                out[row + x] = clamp((int) Math.round(v));
            }
        }
        return out;
    }

    // ──────────────────────────────────────────────────────────────────────
    // Scanner streaks, dust and dead pixels
    // ──────────────────────────────────────────────────────────────────────

    static int[] addStreaks(int[] g, int w, int h, int streaks, int dust, java.util.Random rng) {
        int[] out = g;
        for (int k = 0; k < streaks; k++) {
            int dir = rng.nextInt(3);
            int amp = 14 + rng.nextInt(28);
            int sign = rng.nextBoolean() ? 1 : -1;
            int width = 1 + rng.nextInt(3);
            if (dir == 0) { // vertical
                int x = rng.nextInt(w);
                for (int i = 0; i < width && x + i < w; i++) {
                    for (int y = 0; y < h; y++) {
                        int idx = y * w + x + i;
                        out[idx] = clamp(out[idx] + sign * amp);
                    }
                }
            } else if (dir == 1) { // horizontal
                int y = rng.nextInt(h);
                for (int i = 0; i < width && y + i < h; i++) {
                    int rr = (y + i) * w;
                    for (int x = 0; x < w; x++) {
                        out[rr + x] = clamp(out[rr + x] + sign * amp);
                    }
                }
            } else { // faint roller band
                int y = rng.nextInt(h);
                int band = 6 + rng.nextInt(14);
                for (int i = 0; i < band && y + i < h; i++) {
                    int rr = (y + i) * w;
                    for (int x = 0; x < w; x++) {
                        out[rr + x] = clamp(out[rr + x] + sign * (amp / 3));
                    }
                }
            }
        }
        for (int k = 0; k < dust; k++) {
            int cx = rng.nextInt(w);
            int cy = rng.nextInt(h);
            int r = 1 + rng.nextInt(4);
            int amp = 18 + rng.nextInt(28);
            int sign = rng.nextBoolean() ? 1 : -1;
            for (int dy = -r; dy <= r; dy++) {
                for (int dx = -r; dx <= r; dx++) {
                    if (dx * dx + dy * dy > r * r) continue;
                    int x = cx + dx;
                    int y = cy + dy;
                    if (x < 0 || x >= w || y < 0 || y >= h) continue;
                    int idx = y * w + x;
                    out[idx] = clamp(out[idx] + sign * amp);
                }
            }
        }
        // a few dead pixels
        for (int k = 0; k < 6; k++) {
            int idx = rng.nextInt(w * h);
            out[idx] = rng.nextBoolean() ? 0 : 255;
        }
        return out;
    }

    // ──────────────────────────────────────────────────────────────────────
    // JPEG recompression
    // ──────────────────────────────────────────────────────────────────────

    static int[] recompressJpeg(int[] g, int w, int h, float quality) {
        try {
            BufferedImage img = fromGray(g, w, h);
            ImageWriter writer = ImageIO.getImageWritersByFormatName("jpg").next();
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(quality);
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            try (ImageOutputStream ios = new MemoryCacheImageOutputStream(bos)) {
                writer.setOutput(ios);
                writer.write(null, new IIOImage(img, null, null), param);
            }
            writer.dispose();
            BufferedImage back = ImageIO.read(new java.io.ByteArrayInputStream(bos.toByteArray()));
            return back == null ? g : ImageOps.toGrayscale(back);
        } catch (Exception e) {
            throw new IllegalStateException("JPEG degradation failed", e);
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // Misc
    // ──────────────────────────────────────────────────────────────────────

    private static int clamp(int v) {
        return Math.max(MIN_V, Math.min(MAX_V, v));
    }

    private static int clampInt(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static double iflat(java.util.Random rng) {
        return (rng.nextDouble() + rng.nextDouble()) / 2;
    }
}