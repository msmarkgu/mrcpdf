package com.mrcpdf.quality.training;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;

import com.mrcpdf.quality.model.MrcReadiness;

/**
 * The banner drawn onto every page of {@code build/real-train-src.pdf}.
 *
 * <p>This class exists for one bug.  {@code 47cc4b5} relabelled the corpus from
 * the R_mrc composite onto the audited word F1.  It changed what
 * {@code embedPage} was handed and left the banner's format string reading
 * {@code "R_MRC=%.3f"}, so all 500 pages printed a word F1 under the name of a
 * different metric.  Nothing failed: the number was a real measurement, just
 * mislabelled by +0.36 on the worst page.  A test asserting the banner names the
 * metric it prints is the only thing standing between that and the next relabel.
 */
class RealTrainDumpBannerTest {

    /**
     * A real row: {@code real-train-map.csv} page 184, which is physical PDF
     * page 40.  A 96.5% readable page whose R_mrc is only 0.609 because
     * {@code S_visual} collapsed while {@code S_ocr} stayed healthy.
     */
    private static final MrcReadiness.Breakdown PAGE_184 =
            breakdown(0.408, 0.570, 0.856, 0.777, 0.608655);

    /** Builds a {@link MrcReadiness.Breakdown} with only the printed fields filled in. */
    private static MrcReadiness.Breakdown breakdown(double sVisual, double sMask, double sOcr,
                                                    double sBg, double score) {
        double kappa = Math.min(Math.min(sVisual, sMask), Math.min(sOcr, sBg));
        return new MrcReadiness.Breakdown(
                0, 0, 0, sVisual,
                0, 0, 0, sMask,
                0, 0, 0, 0, sOcr,
                0, 0, sBg,
                0, kappa, 0, score, 0);
    }

    /**
     * The value following {@code key} in the banner, or null if the key is
     * absent.  The key must start the text or follow a space, so that looking up
     * {@code "k="} cannot match the tail of {@code "S_mask="}.
     */
    private static String valueOf(String text, String key) {
        for (int from = 0; ; ) {
            int i = text.indexOf(key, from);
            if (i < 0) return null;
            if (i == 0 || text.charAt(i - 1) == ' ') {
                int start = i + key.length();
                int end = start;
                while (end < text.length()
                        && (Character.isDigit(text.charAt(end)) || text.charAt(end) == '.')) {
                    end++;
                }
                return end == start ? null : text.substring(start, end);
            }
            from = i + 1;
        }
    }

    /** {@link #valueOf}, but failing with a named assertion when the key is absent. */
    private static String require(String text, String key) {
        String v = valueOf(text, key);
        assertTrue(v != null, "banner is missing the " + key + " field: " + text);
        return v;
    }

    // ── The bug this class exists to prevent ────────────────────────────────

    @Test
    void bannerDoesNotPrintAF1UnderTheNameOfR_mrc() {
        String text = RealTrainDump.bannerText(0.964912, PAGE_184, 0);
        assertFalse(text.contains("R_MRC="),
                "R_MRC= is the pre-47cc4b5 key and mislabels the audit F1: " + text);
        assertTrue(text.contains("audit="), "banner must name the label it prints: " + text);
        assertTrue(text.contains("R_mrc="), "banner must also print the real composite: " + text);
    }

    /**
     * Each key must carry its own number.  The four sub-scores are drawn from
     * the same {@code Breakdown}, so a reordered argument list would compile,
     * run, and quietly relabel every page.
     */
    @Test
    void bannerAttributesEachValueToItsOwnMetric() {
        String text = RealTrainDump.bannerText(0.964912, PAGE_184, 0);
        assertEquals("0.965", valueOf(text, "audit="), "label: " + text);
        assertEquals("0.609", valueOf(text, "R_mrc="), "composite: " + text);
        assertEquals("0.408", valueOf(text, "k="), "kappa: " + text);
        assertEquals("0.408", valueOf(text, "S_vis="), "visual sub-score: " + text);
        assertEquals("0.570", valueOf(text, "S_mask="), "mask sub-score: " + text);
        assertEquals("0.856", valueOf(text, "S_ocr="), "ocr sub-score: " + text);
        assertEquals("0.777", valueOf(text, "S_bg="), "background sub-score: " + text);
        assertTrue(text.contains("class=good"), "class must still print: " + text);
    }

    /**
     * The divergence that motivates printing seven numbers instead of one.  A
     * page can be legible and still score low on the composite, and the two
     * cases call for opposite responses: high {@code audit} with low
     * {@code R_mrc} means the 1-bit foreground mask will mangle strokes that
     * OCR read fine, so MRC should be off.
     */
    @Test
    void bannerDistinguishesLegibleFromCompositeFriendly() {
        String text = RealTrainDump.bannerText(0.964912, PAGE_184, 0);
        assertEquals("0.965", valueOf(text, "audit="));
        assertEquals("0.609", valueOf(text, "R_mrc="));
        assertFalse(valueOf(text, "audit=").equals(valueOf(text, "R_mrc=")),
                "a page like this must not render as a single agreement: " + text);
    }

    /**
     * {@code kappa} is the minimum of the four sub-scores, and is what the
     * bottleneck penalty is applied to.  Printing it without that relationship
     * would put a fifth unattributed float on the page.
     */
    @Test
    void printedKappaIsTheMinimumOfThePrintedSubScores() {
        double[] subs = {0.408, 0.570, 0.856, 0.777};
        String text = RealTrainDump.bannerText(
                0.5, breakdown(subs[0], subs[1], subs[2], subs[3], 0.5), 1);
        double min = Math.min(Math.min(subs[0], subs[1]), Math.min(subs[2], subs[3]));
        assertEquals(min, Double.parseDouble(require(text, "k=")), 1e-9,
                "kappa must be the min sub-score: " + text);
        assertEquals(0.408, min, 1e-9, "S_vis binds on this row, so kappa is S_vis");
    }

    @Test
    void everyClassNameStillPrints() {
        for (int cls = 0; cls < RealTrainDump.CLASS_NAMES.length; cls++) {
            String text = RealTrainDump.bannerText(0.5, PAGE_184, cls);
            assertTrue(text.endsWith("class=" + RealTrainDump.CLASS_NAMES[cls]), text);
        }
    }

    /**
     * The chip is centred and its white backing rect is sized to the measured
     * text, so a longer banner grows toward the page edges.  At seven metrics
     * the worst case still has over 100pt of slack; this pins that so adding a
     * field later cannot quietly run the chip off the page or under the
     * right-aligned provenance chip.
     */
    @Test
    void bannerFitsOnThePage() throws Exception {
        PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
        final double pageWidth = 612; // mirrors RealTrainDump.PAGE_W (US Letter)
        String worst = RealTrainDump.bannerText(
                1.0, breakdown(1.0, 1.0, 1.0, 1.0, 1.0), RealTrainDump.CLASS_NAMES.length - 1);
        float chipWidth = font.getStringWidth(worst) / 1000f * 8f + 8f;
        assertTrue(chipWidth < pageWidth - 20,
                "banner chip (" + chipWidth + "pt) must clear the page edges: " + worst);
    }
}