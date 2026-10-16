package com.mrcpdf.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationWidget;
import org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm;
import org.apache.pdfbox.pdmodel.interactive.form.PDTextField;
import org.junit.jupiter.api.Test;

/**
 * AcroForm preservation.
 *
 * <p>Widget annotations survive as annotations, but on their own they are inert:
 * without an {@code /AcroForm} root in the output catalog, viewers treat them as
 * artwork. Form-heavy documents are a core MRC target, so the form root and its
 * field tree must survive the page rebuild.
 *
 * <p>The subtle part is identity. The field tree is deep-copied separately from
 * the page annotations; if each widget ends up as two distinct objects the field
 * renders twice and edits land on whichever copy the viewer picked.
 */
class AcroFormPreservationTest {

    private static PDDocument formSource() throws IOException {
        PDDocument doc = new PDDocument();
        PDPage page = new PDPage(PDRectangle.LETTER);
        doc.addPage(page);

        PDAcroForm acroForm = new PDAcroForm(doc);
        doc.getDocumentCatalog().setAcroForm(acroForm);
        acroForm.setDefaultResources(new org.apache.pdfbox.pdmodel.PDResources());

        PDTextField field = new PDTextField(acroForm);
        field.setPartialName("fullname");
        PDAnnotationWidget widget = field.getWidgets().get(0);
        widget.setRectangle(new PDRectangle(72, 700, 200, 20));
        page.getAnnotations().add(widget);
        acroForm.getFields().add(field);
        return doc;
    }

    private static PDDocument blankOutputFor(PDDocument source) {
        PDDocument output = new PDDocument();
        for (int i = 0; i < source.getNumberOfPages(); i++) {
            PDRectangle cb = source.getPage(i).getCropBox();
            output.addPage(new PDPage(new PDRectangle(cb.getWidth(), cb.getHeight())));
        }
        return output;
    }

    private static List<PDPage> allPages(PDDocument doc) {
        List<PDPage> pages = new java.util.ArrayList<>();
        doc.getPages().forEach(pages::add);
        return pages;
    }

    @Test
    void outputCatalogHasAnAcroFormRoot() throws IOException {
        try (PDDocument source = formSource()) {
            try (PDDocument output = blankOutputFor(source)) {
                new MetadataPreserver().preserve(source, output, allPages(output));
                assertNotNull(output.getDocumentCatalog().getAcroForm(),
                    "without /AcroForm the widgets are inert art and the form is lost");
            }
        }
    }

    @Test
    void formFieldsAreCounted() throws IOException {
        try (PDDocument source = formSource()) {
            try (PDDocument output = blankOutputFor(source)) {
                var result = new MetadataPreserver().preserve(source, output, allPages(output));
                assertEquals(1, result.formFields(), "the field should be reported");
            }
        }
    }

    @Test
    void fieldTreeReusesTheWidgetPlacedOnThePage() throws IOException {
        try (PDDocument source = formSource()) {
            try (PDDocument output = blankOutputFor(source)) {
                new MetadataPreserver().preserve(source, output, allPages(output));

                PDAnnotationWidget onPage = (PDAnnotationWidget) output.getPage(0)
                        .getAnnotations().get(0);
                PDAnnotationWidget inFieldTree = output.getDocumentCatalog()
                        .getAcroForm().getFields().get(0).getWidgets().get(0);

                assertSame(onPage.getCOSObject(), inFieldTree.getCOSObject(),
                    "two copies of the widget means the field renders twice and an"
                    + " edit lands on whichever copy the viewer picked");
            }
        }
    }

    @Test
    void documentWithoutAFormIsUnaffected() throws IOException {
        try (PDDocument source = new PDDocument()) {
            source.addPage(new PDPage());
            try (PDDocument output = blankOutputFor(source)) {
                var result = new MetadataPreserver().preserve(source, output, allPages(output));
                assertEquals(0, result.formFields());
                org.junit.jupiter.api.Assertions.assertNull(
                    output.getDocumentCatalog().getAcroForm(),
                    "do not invent an empty form root");
            }
        }
    }

    @Test
    void formSurvivesASaveAndReload() throws IOException {
        java.io.File tmp = java.io.File.createTempFile("acroform-", ".pdf");
        try (PDDocument source = formSource()) {
            try (PDDocument output = blankOutputFor(source)) {
                new MetadataPreserver().preserve(source, output, allPages(output));
                output.save(tmp);
            }
        }
        try (PDDocument reloaded = Loader.loadPDF(tmp)) {
            assertNotNull(reloaded.getDocumentCatalog().getAcroForm(),
                "the form must be in the written bytes, not just the in-memory tree");
            assertEquals(1, reloaded.getDocumentCatalog().getAcroForm()
                    .getFields().get(0).getWidgets().size());
        } finally {
            tmp.delete();
        }
    }
}