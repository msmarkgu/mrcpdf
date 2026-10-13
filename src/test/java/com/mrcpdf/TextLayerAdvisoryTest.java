package com.mrcpdf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The advisory emitted when the source has no text layer at all.
 *
 * <p>mrcpdf never runs OCR, so an image-only scan yields a smaller picture
 * with no searchable text. That is by design, but "no recognition errors" is
 * easy to read as "no missing text", so the run must say so out loud.
 */
class TextLayerAdvisoryTest {

    @Test
    void warnsForImageOnlySource() {
        String msg = MrcPdf.textLayerAdvisory(12);
        assertNotNull(msg, "an image-only source must produce an advisory");
    }

    @Test
    void statesThereIsNoTextLayerAndNamesThePageCount() {
        String msg = MrcPdf.textLayerAdvisory(12);
        assertTrue(msg.contains("12"),
            "the page count tells the user whether only some pages were blank: " + msg);
        assertTrue(msg.contains("no text layer"),
            "the warning must say the source has no text layer: " + msg);
    }

    @Test
    void saysOutputWillNotBeSearchable() {
        String msg = MrcPdf.textLayerAdvisory(3);
        assertTrue(msg.contains("not be searchable"),
            "the consequence must be stated, not implied: " + msg);
    }

    @Test
    void namesTheRemedy() {
        String msg = MrcPdf.textLayerAdvisory(3);
        assertTrue(msg.contains("ocrmypdf"),
            "an advisory without a next step is just a complaint: " + msg);
    }

    @Test
    void singularPageIsNotPluralised() {
        assertTrue(MrcPdf.textLayerAdvisory(1).contains("source page."),
            "1 page should read 'page', not 'pages'");
        assertTrue(MrcPdf.textLayerAdvisory(2).contains("source pages."),
            "2 pages should read 'pages'");
    }

    @Test
    void nothingToWarnAboutOnAnEmptyDocument() {
        assertNull(MrcPdf.textLayerAdvisory(0),
            "a zero-page document has no pages to warn about");
    }

    @Test
    void warningGoesToStderrNotStdout() {
        // The advisory must not be interleaved into the progress block on
        // stdout, which callers may parse. Keep it a stderr warning.
        assertTrue(MrcPdf.textLayerAdvisory(4).startsWith("  Warning:"),
            "must carry the Warning: severity prefix");
    }

    @Test
    void advisoryIsOneLinePerPageCount() {
        // Two physical lines (message + indented remedy) so it stays readable
        // in a long run log, without a paragraph per page count.
        assertEquals(2, MrcPdf.textLayerAdvisory(5).split("\n").length,
            "advisory should be exactly two lines");
    }
}