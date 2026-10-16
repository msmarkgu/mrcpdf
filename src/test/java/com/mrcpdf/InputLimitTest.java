package com.mrcpdf;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Input limits.
 *
 * <p>Memory is O(document): every page's background JPEG, JBIG2 stream and word
 * list is retained, and the whole output document accumulates before a byte is
 * saved. Without a ceiling, a large document or an absurd {@code --dpi} simply
 * dies with {@code OutOfMemoryError} partway through — confirmed at
 * {@code --dpi 1200}, which OOMed in {@code TextQualityScorer.gradient}.
 *
 * <p>Both checks are up-front and predictable, which is the only place a limit
 * is genuinely useful: refusing in one second beats dying after twenty minutes
 * of work.
 */
class InputLimitTest {

    // --max-pages

    @Test
    void pageCountWithinLimitIsFine() {
        assertNull(MrcPdf.pageLimitNotice(500, 1000));
    }

    @Test
    void pageCountAtLimitIsFine() {
        assertNull(MrcPdf.pageLimitNotice(1000, 1000), "the limit is inclusive");
    }

    @Test
    void pageCountOverLimitIsRefused() {
        assertNotNull(MrcPdf.pageLimitNotice(1001, 1000));
    }

    @Test
    void pageLimitIsOffWhenUnset() {
        assertNull(MrcPdf.pageLimitNotice(100000, 0),
            "no flag means no limit, not an accidental default");
    }

    @Test
    void pageLimitMessageNamesBothCounts() {
        String msg = MrcPdf.pageLimitNotice(5000, 1000);
        assertTrue(msg.contains("5000") && msg.contains("1000"),
            "the user needs the actual and permitted counts: " + msg);
    }

    @Test
    void pageLimitMessageSaysHowToRaiseIt() {
        assertTrue(MrcPdf.pageLimitNotice(5000, 1000).contains("--max-pages"),
            "a limit with no override is a dead end");
    }

    // --max-dpi

    @Test
    void dpiWithinLimitIsFine() {
        assertNull(MrcPdf.dpiLimitNotice(300f, 600f));
    }

    @Test
    void dpiAtLimitIsFine() {
        assertNull(MrcPdf.dpiLimitNotice(600f, 600f), "the limit is inclusive");
    }

    @Test
    void dpiOverLimitIsRefused() {
        assertNotNull(MrcPdf.dpiLimitNotice(1200f, 600f),
            "this is the case that OOMs rather than warning");
    }

    @Test
    void dpiLimitIsOffWhenUnset() {
        assertNull(MrcPdf.dpiLimitNotice(5000f, 0));
    }

    @Test
    void dpiLimitMessageExplainsTheCost() {
        String msg = MrcPdf.dpiLimitNotice(1200f, 600f);
        assertTrue(msg.contains("memory") || msg.contains("megapixel"),
            "say why, so the user understands it is not an arbitrary rule: " + msg);
    }

    @Test
    void dpiLimitMessageSaysHowToRaiseIt() {
        assertTrue(MrcPdf.dpiLimitNotice(1200f, 600f).contains("--max-dpi"),
            "a limit with no override is a dead end");
    }

    @Test
    void negativeLimitsAlsoMeanUnlimited() {
        // Defensive: a negative value must not become a limit that refuses
        // everything, which would be worse than having no limit at all.
        assertNull(MrcPdf.dpiLimitNotice(300f, -1));
        assertNull(MrcPdf.pageLimitNotice(100000, -1));
    }
}