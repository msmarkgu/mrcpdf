package com.mrcpdf.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.junit.jupiter.api.Test;

/**
 * The PDF/A-shaped metadata writer.
 *
 * <p>These tests are about internal consistency of what the writer stamps, not
 * about PDF/A conformance. It is not conformant -- the file is written as PDF
 * 1.6 where PDF/A-2 needs 1.7, and the invisible layer uses a non-embedded
 * Standard 14 font. That is documented rather than hidden, and `--help` and the
 * README both say so.
 */
class PdfaWriterTest {

    private static String xmpOf(PDDocument doc) throws Exception {
        try (java.io.InputStream in =
                doc.getDocumentCatalog().getMetadata().createInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void stampsPdfaPartAndConformance() throws Exception {
        try (PDDocument doc = new PDDocument()) {
            PdfaWriter.addPdfaMetadata(doc);
            String xmp = xmpOf(doc);
            assertTrue(xmp.contains("<pdfaid:part>2</pdfaid:part>"),
                "the file must actually declare part 2: " + xmp);
            assertTrue(xmp.contains("<pdfaid:conformance>B</pdfaid:conformance>"),
                "the file must actually declare conformance B: " + xmp);
        }
    }

    /**
     * The xpacket begin marker exists to carry a real U+FEFF byte-order mark.
     * A text block does not process \uFEFF escapes, so a text-block literal
     * emits the six characters backslash-u-F-E-F-F instead, which defeats the
     * marker's entire purpose.
     */
    @Test
    void xpacketBeginCarriesARealBomNotTheEscapeText() throws Exception {
        try (PDDocument doc = new PDDocument()) {
            PdfaWriter.addPdfaMetadata(doc);
            String xmp = xmpOf(doc);
            assertTrue(xmp.contains("﻿"),
                "the xpacket must contain an actual U+FEFF character");
            assertTrue(!xmp.contains("\\uFEFF"),
                "a literal backslash-u-F-E-F-F is not a BOM -- this is the bug"
                + " that text-block escaping produced");
        }
    }

    /**
     * A source that declares PDF/A-1 must not have that declaration carried
     * through unchanged while the document is built with PDF/A-2 features --
     * that yields a file claiming to be two incompatible things at once.
     */
    @Test
    void existingPartDeclarationIsOverwrittenNotIgnored() throws Exception {
        try (PDDocument doc = new PDDocument()) {
            org.apache.pdfbox.pdmodel.common.PDMetadata meta =
                new org.apache.pdfbox.pdmodel.common.PDMetadata(doc);
            String xmp =
                "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\">"
                    + "<rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">"
                    + "<rdf:Description rdf:about=\"\" "
                    + "xmlns:pdfaid=\"http://www.aiim.org/pdfa/ns/id/\">"
                    + "<pdfaid:part>1</pdfaid:part>"
                    + "<pdfaid:conformance>A</pdfaid:conformance>"
                    + "</rdf:Description></rdf:RDF></x:xmpmeta>";
            meta.importXMPMetadata(xmp.getBytes(StandardCharsets.UTF_8));
            doc.getDocumentCatalog().setMetadata(meta);

            PdfaWriter.addPdfaMetadata(doc);
            String out = xmpOf(doc);
            assertTrue(out.contains("<pdfaid:part>2</pdfaid:part>"),
                "a source declaring part 1 must not leave the output declaring"
                + " part 1 while it is built with PDF/A-2 features: " + out);
            assertTrue(!out.contains("<pdfaid:part>1</pdfaid:part>"),
                "the stale part 1 declaration survived: " + out);
        }
    }

    @Test
    void attachesAnSrgbOutputIntent() throws Exception {
        try (PDDocument doc = new PDDocument()) {
            PdfaWriter.addPdfaMetadata(doc);
            assertNotNull(doc.getDocumentCatalog().getOutputIntents(),
                "PDF/A-2b requires an output intent");
            assertEquals(1, doc.getDocumentCatalog().getOutputIntents().size());
        }
    }

    @Test
    void doesNotTouchDocumentsWithoutMetadata() throws Exception {
        try (PDDocument doc = new PDDocument()) {
            PdfaWriter.addPdfaMetadata(doc);
            // No pre-existing XMP: the static fallback must still produce
            // well-formed, parseable metadata rather than nothing.
            assertNotNull(doc.getDocumentCatalog().getMetadata());
            assertTrue(xmpOf(doc).contains("pdfaid:part"),
                "the fallback path must stamp the same declaration");
        }
    }
}