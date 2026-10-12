package com.mrcpdf.pipeline;

import java.awt.geom.Point2D;
import java.io.IOException;
import java.util.List;

import org.apache.pdfbox.contentstream.PDFStreamEngine;
import org.apache.pdfbox.contentstream.operator.DrawObject;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.contentstream.operator.OperatorName;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.contentstream.operator.state.Concatenate;
import org.apache.pdfbox.contentstream.operator.state.Restore;
import org.apache.pdfbox.contentstream.operator.state.Save;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.util.Matrix;

/**
 * Decides whether a page is digital native -- a vector page with real content
 * behind it, rather than a photograph of a piece of paper.
 *
 * <p>The distinction drives the MRC gate: a native page gains nothing from
 * compression that re-rasterises its text, so it is imported into the output
 * untouched. A page of any kind with a full-page raster on it is a scan (or the
 * OCR'd rendering of one), and stays on the normal path where the quality screen
 * decides.
 *
 * <p>The test is <em>drawn coverage</em>, taken from the CTM, not image pixel
 * count. Both easier rules fail in the direction that matters:
 *
 * <ul>
 *   <li>"the page has a text layer" would call an OCR'd scan native -- including
 *       {@code tests/scanned-text.pdf}, the fixture this pipeline is built
 *       around -- and then never compress it.</li>
 *   <li>"the largest embedded image has page-sized pixels" would call a 72dpi
 *       full-page scan native (few pixels, still fills the page) and a vector
 *       page carrying a large photograph not native (many pixels, drawn small).
 *       {@code TesseractOcrScore.pageExtentPixels} uses this proxy; it answers a
 *       different question there -- "how big is the bitmap Tesseract read" --
 *       and does not answer this one.</li>
 * </ul>
 *
 * <p>Reading the CTM needs a content-stream pass, which also gets form XObjects
 * and inline images for free. It is one pass over operators per page -- no
 * decoding, no rendering -- against a 300dpi rasterisation the pipeline used to
 * do for every page anyway.
 *
 * <p>Instances are single-use and are created on the calling thread; nothing
 * here is shared.
 */
public final class PageOrigin {

    /**
     * Fraction of the crop box a drawn raster must cover to make the page a scan.
     *
     * <p>0.85 rather than 1.0 because scans routinely carry a few points of white
     * margin, and rather than 0.5 because a half-page figure on a vector page is
     * not a scan. Erring low makes a page <em>not</em> native, which costs the
     * pass-through optimization and leaves the screen to decide -- the safe side.
     * Erring high would hand a scan to the pass-through path and skip MRC for it.
     */
    public static final double PAGE_FILL_COVERAGE = 0.85;

    private PageOrigin() {
    }

    /**
     * True when the page should be passed through untouched: no raster drawn over
     * it, so what is on the page is vector content the output can carry as-is.
     *
     * @param page the source page to classify
     * @throws IOException if the page's content stream cannot be read
     */
    public static boolean isDigitalNative(PDPage page) throws IOException {
        return probe(page).getCoverage() < PAGE_FILL_COVERAGE;
    }

    /**
     * The largest share of the crop box covered by any one drawn raster, 0.0 on
     * a page with no raster at all. Package-private so tests can assert the
     * measurement rather than only the verdict that falls out of it.
     */
    static double coverageOf(PDPage page) throws IOException {
        return probe(page).getCoverage();
    }

    /**
     * What one content-stream pass learns about a page: how much raster it
     * carries, and -- for the raster that carries the most -- the resolution the
     * source pixels imply.
     *
     * @param page the source page to measure
     * @throws IOException if the page's content stream cannot be read
     */
    public static Probe probe(PDPage page) throws IOException {
        RasterCoverage probe = new RasterCoverage(page.getCropBox());
        probe.processPage(page);
        return new Probe(probe.bestCoverage, probe.bestNativeDpi);
    }

    /**
     * The result of {@link #probe(PDPage)}.
     *
     * <p>{@code nativeDpi} is the resolution of the page's dominant raster: its
     * pixel dimensions divided by the points the raster is drawn over, taking the
     * more restrictive axis. It is 0 when no such resolution can be established
     * -- no raster at all, an inline image (which exposes no separate pixel
     * dimensions through the content stream), or an image object whose
     * dimensions cannot be read. Callers treat 0 as "keep the configured DPI".
     */
    public static final class Probe {
        private final double coverage;
        private final double nativeDpi;

        Probe(double coverage, double nativeDpi) {
            this.coverage = coverage;
            // Normalise the unknown sentinel: no raster can imply a resolution
            // at or below zero, so every non-positive measurement is "unknown".
            this.nativeDpi = nativeDpi > 0 ? nativeDpi : 0;
        }

        public double getCoverage() { return coverage; }

        public double getNativeDpi() { return nativeDpi; }

        public boolean hasNativeDpi() { return nativeDpi > 0; }
    }

    /**
     * Walks one page's operators, recording how much of the crop box each drawn
     * raster covers. The stock {@code DrawObject} processor is registered so form
     * XObjects are descended into; this subclass only measures, on the way past.
     */
    private static final class RasterCoverage extends PDFStreamEngine {

        private final PDRectangle box;
        private double bestCoverage;
        private double bestNativeDpi;

        RasterCoverage(PDRectangle box) {
            this.box = box;
            // PDFBox 3 registers no processors by default, and an unhandled
            // operator falls through to the no-op unsupportedOperator. The CTM
            // therefore never moves unless q/Q/cm are installed, and `Do` never
            // descends into a form unless DrawObject is. Everything else the
            // probe measures -- coverage, and the recursion -- is a consequence
            // of those four.
            addOperator(new Save(this));
            addOperator(new Restore(this));
            addOperator(new Concatenate(this));
            addOperator(new DrawObject(this));
        }

        @Override
        protected void processOperator(Operator operator, List<COSBase> operands)
                throws IOException {
            String name = operator.getName();
            if (OperatorName.DRAW_OBJECT.equals(name)) {
                if (!operands.isEmpty() && operands.get(0) instanceof COSName ref) {
                    PDResources resources = getResources();
                    if (resources != null && resources.isImageXObject(ref)) {
                        recordCoverage(imageOrNull(resources, ref));
                    }
                }
            } else if (OperatorName.BEGIN_INLINE_IMAGE.equals(name)) {
                // No resource to look up; the CTM alone says how much page it
                // fills, and no resolvable pixel dimensions are available.
                recordCoverage(null);
            }
            // super dispatches to the registered processors, including DrawObject,
            // which is what descends into Form XObjects.
            super.processOperator(operator, operands);
        }

        /**
         * Resolves the image behind a {@code Do}, for its pixel dimensions only.
         * A resource whose object cannot be read yields null: coverage is still
         * measured, the resolution simply stays unknown.
         */
        private static PDImageXObject imageOrNull(PDResources resources, COSName ref) {
            try {
                return resources.getXObject(ref) instanceof PDImageXObject img ? img : null;
            } catch (IOException e) {
                return null;
            }
        }

        /**
         * Measures the drawn raster's axis-aligned bounds against the crop box.
         *
         * <p>The {@code Do} operator paints the unit square, mapped by the CTM.
         * Four transformed corners give a bounding box that is exact for the
         * translate/scale/rotate/flip a page-filling image actually uses, and
         * slightly generous under skew -- generous in the direction that marks a
         * page as a scan, which is the side that keeps MRC available.
         */
        private void recordCoverage(PDImageXObject img) {
            Matrix ctm = getGraphicsState().getCurrentTransformationMatrix();
            float minX = Float.POSITIVE_INFINITY;
            float minY = Float.POSITIVE_INFINITY;
            float maxX = Float.NEGATIVE_INFINITY;
            float maxY = Float.NEGATIVE_INFINITY;
            for (float[] corner : new float[][]{{0, 0}, {1, 0}, {1, 1}, {0, 1}}) {
                Point2D p = ctm.transformPoint(corner[0], corner[1]);
                minX = Math.min(minX, (float) p.getX());
                maxX = Math.max(maxX, (float) p.getX());
                minY = Math.min(minY, (float) p.getY());
                maxY = Math.max(maxY, (float) p.getY());
            }
            double ix = Math.max(0, Math.min(maxX, box.getUpperRightX())
                    - Math.max(minX, box.getLowerLeftX()));
            double iy = Math.max(0, Math.min(maxY, box.getUpperRightY())
                    - Math.max(minY, box.getLowerLeftY()));
            double area = box.getWidth() * box.getHeight();
            if (area <= 0) return;
            double coverage = (ix * iy) / area;
            if (coverage <= bestCoverage) return;
            bestCoverage = coverage;
            bestNativeDpi = nativeDpiOf(img, maxX - minX, maxY - minY);
        }

        /**
         * The source resolution implied by a raster's pixels and the points it is
         * drawn over. The smaller of the two axis ratios is taken: rendering above
         * it upsamples one axis, so the restrictive axis is the one a faithful
         * re-render has to respect. Zero when the dimensions are unavailable.
         */
        private static double nativeDpiOf(PDImageXObject img,
                                          double drawnW, double drawnH) {
            if (img == null || drawnW <= 0 || drawnH <= 0) return 0;
            int pxW = img.getWidth();
            int pxH = img.getHeight();
            if (pxW <= 0 || pxH <= 0) return 0;
            return Math.min(pxW * 72.0 / drawnW, pxH * 72.0 / drawnH);
        }
    }
}
