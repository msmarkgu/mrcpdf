package com.mrcpdf.pipeline;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSFloat;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSNumber;
import org.apache.pdfbox.cos.COSObject;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.PDDocumentNameDictionary;
import org.apache.pdfbox.pdmodel.PDDestinationNameTreeNode;
import org.apache.pdfbox.pdmodel.PDEmbeddedFilesNameTreeNode;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDMetadata;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotation;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageDestination;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageFitWidthDestination;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem;

/**
 * Copies metadata from a source PDF to an output PDF.
 *
 * Preserved elements:
 *   - PDDocumentInformation (title, author, subject, keywords, creator, producer)
 *   - PDDocumentOutline (bookmark tree) — rebuilt with destinations remapped
 *     from the source pages to the corresponding output pages
 *   - Per-page PDAnnotation lists — each annotation COS dictionary is duplicated
 *   - Embedded files (attachments) — COS-level deep copy of the Names/EmbeddedFiles tree
 *   - XML metadata stream (XMP)
 *
 * Limitations:
 *   - Named destinations are preserved but may reference missing pages.
 */
public class MetadataPreserver {

    /**
     * Counts of metadata elements copied from source to output.
     */
    public record PreserveResult(int outlines, int annotations, int embeddedFiles,
            int droppedNamedDests) {}

    /**
     * Copies all metadata from source to output.
     *
     * @param source      The original PDF.
     * @param output      The newly assembled PDF.
     * @param outputPages Output pages indexed to match source pages.
     * @return Counts of copied elements.
     */
    public PreserveResult preserve(PDDocument source, PDDocument output, List<PDPage> outputPages) throws IOException {
        return preserve(source, output, outputPages, Collections.emptySet());
    }

    /**
     * Preserves metadata from the source document into the assembled output.
     *
     * @param source          The source document.
     * @param output          The newly assembled PDF.
     * @param outputPages     Output pages indexed to match source pages.
     * @param passThroughPages Output pages that are the source pages imported
     *                         verbatim ({@code output.importPage}); their user
     *                         space is unchanged, so annotation geometry must
     *                         not be remapped.
     * @return Counts of copied elements.
     */
    public PreserveResult preserve(PDDocument source, PDDocument output, List<PDPage> outputPages,
                                   Set<PDPage> passThroughPages) throws IOException {
        copyDocumentInfo(source, output);
        int outlines = copyOutline(source, output, outputPages);
        int annotations = copyAnnotations(source, outputPages, passThroughPages);
        int embeddedFiles = copyEmbeddedFiles(source, output);
        int droppedNamedDests = countNamedDests(source);
        copyXmlMetadata(source, output);
        return new PreserveResult(outlines, annotations, embeddedFiles, droppedNamedDests);
    }

    private void copyDocumentInfo(PDDocument source, PDDocument output) {
        PDDocumentInformation info = source.getDocumentInformation();
        if (info != null) {
            PDDocumentInformation dest = new PDDocumentInformation();
            copyIfSet(info::getTitle, dest::setTitle);
            copyIfSet(info::getAuthor, dest::setAuthor);
            copyIfSet(info::getSubject, dest::setSubject);
            copyIfSet(info::getKeywords, dest::setKeywords);
            copyIfSet(info::getCreator, dest::setCreator);
            copyIfSet(info::getProducer, dest::setProducer);
            output.setDocumentInformation(dest);
        }
    }

    private void copyIfSet(ThrowingSupplier<String> getter, ThrowingConsumer<String> setter) {
        try {
            String val = getter.get();
            if (val != null) setter.accept(val);
        } catch (Exception e) {
            // skip
        }
    }

    @FunctionalInterface
    private interface ThrowingSupplier<T> {
        T get() throws Exception;
    }

    @FunctionalInterface
    private interface ThrowingConsumer<T> {
        void accept(T t) throws Exception;
    }

    private int copyOutline(PDDocument source, PDDocument output, List<PDPage> outputPages) throws IOException {
        PDDocumentOutline srcOutline = source.getDocumentCatalog().getDocumentOutline();
        if (srcOutline == null) return 0;

        // Rebuild the outline instead of deep-copying the COS dictionary: the
        // deep copy would carry /Dest indirect references to the SOURCE pages,
        // which PDFBox then serializes into the output as a hidden second copy
        // of the source document (bloating the file).  Each item is recreated
        // with its destination remapped to the matching output page by index.
        PDDocumentOutline dstOutline = new PDDocumentOutline();
        int count = 0;
        for (PDOutlineItem srcItem : srcOutline.children()) {
            dstOutline.addLast(copyOutlineItem(srcItem, source, outputPages));
            count++;
        }
        output.getDocumentCatalog().setDocumentOutline(dstOutline);
        return count;
    }

    private PDOutlineItem copyOutlineItem(PDOutlineItem srcItem, PDDocument source,
                                          List<PDPage> outputPages) {
        PDOutlineItem dstItem = new PDOutlineItem();
        String title = srcItem.getTitle();
        dstItem.setTitle(title != null ? title : "");
        remapDestination(srcItem, dstItem, source, outputPages);
        for (PDOutlineItem child : srcItem.children()) {
            dstItem.addLast(copyOutlineItem(child, source, outputPages));
        }
        return dstItem;
    }

    private void remapDestination(PDOutlineItem srcItem, PDOutlineItem dstItem,
                                  PDDocument source, List<PDPage> outputPages) {
        org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDDestination dest = null;
        try {
            dest = srcItem.getDestination();
        } catch (Exception e) {
            return;
        }
        if (!(dest instanceof PDPageDestination)) return;
        try {
            PDPage srcPage = ((PDPageDestination) dest).getPage();
            if (srcPage == null) return;
            int idx = source.getPages().indexOf(srcPage);
            if (idx < 0 || idx >= outputPages.size()) return;
            PDPageFitWidthDestination outDest = new PDPageFitWidthDestination();
            outDest.setPage(outputPages.get(idx));
            dstItem.setDestination(outDest);
        } catch (Exception e) {
            // Destinations that cannot be resolved are dropped, not carried over.
        }
    }

    private int copyAnnotations(PDDocument source, List<PDPage> outputPages,
                                Set<PDPage> passThroughPages) throws IOException {
        int totalAnnotations = 0;
        int pageCount = Math.min(source.getNumberOfPages(), outputPages.size());
        for (int i = 0; i < pageCount; i++) {
            PDPage srcPage = source.getPage(i);
            PDPage dstPage = outputPages.get(i);

            List<PDAnnotation> annotations = srcPage.getAnnotations();
            if (annotations.isEmpty()) continue;

            COSArray dstAnnots = new COSArray();
            for (PDAnnotation ann : annotations) {
                COSDictionary cloned = deepCopyCOSDictionary(ann.getCOSObject());
                // Strip /P (parent page) — the cloned annotation dictionary should not
                // carry a reference back to the source page.
                cloned.removeItem(COSName.P);
                remapAnnotationDest(cloned, source, outputPages);
                if (!passThroughPages.contains(dstPage)) {
                    // Pass-through pages keep the source page's geometry verbatim
                    // (importPage copies MediaBox/CropBox origin and /Rotate), so a
                    // /Rect in source user space is already correct in the output.
                    remapAnnotationGeometry(cloned, srcPage);
                }
                dstAnnots.add(cloned);
            }
            dstPage.getCOSObject().setItem(COSName.ANNOTS, dstAnnots);
            totalAnnotations += annotations.size();
        }
        return totalAnnotations;
    }

    /**
     * Remaps page references inside a cloned annotation dictionary from source
     * pages to the corresponding output pages. Handles /Dest (explicit or array
     * form) and /A → /D (action destination).
     */
    private void remapAnnotationDest(COSDictionary annot, PDDocument source, List<PDPage> outputPages) {
        COSBase dest = annot.getDictionaryObject(COSName.DEST);
        if (dest != null) {
            COSBase remapped = resolvePageRef(dest, source, outputPages);
            if (remapped != dest) annot.setItem(COSName.DEST, remapped);
        }
        COSBase action = annot.getDictionaryObject(COSName.A);
        if (action instanceof COSDictionary actionDict) {
            COSBase aDest = actionDict.getDictionaryObject(COSName.D);
            if (aDest != null) {
                COSBase remapped = resolvePageRef(aDest, source, outputPages);
                if (remapped != aDest) actionDict.setItem(COSName.D, remapped);
            }
        }
    }

    /**
     * If {@code value} is a COSObject whose referent is a source page dictionary,
     * returns the matching output page's COSObject. Otherwise returns {@code value}
     * unchanged. Identity-based: compares the underlying COSObject, not page index,
     * because annotations may hold indirect references to the page object.
     */
    private COSBase resolvePageRef(COSBase value, PDDocument source, List<PDPage> outputPages) {
        if (!(value instanceof COSObject cosObj)) return value;
        COSBase referent = cosObj.getObject();
        if (!(referent instanceof COSDictionary)) return value;
        for (int i = 0; i < outputPages.size(); i++) {
            if (source.getPage(i).getCOSObject() == referent) {
                return outputPages.get(i).getCOSObject();
            }
        }
        return value;
    }

    /**
     * Remaps annotation geometry from the source page's default user space to
     * the output page's fresh, un-rotated, zero-origin user space.
     *
     * <p>The /Rect (and /QuadPoints, /Vertices, /InkList, /L, /CL) of a source
     * annotation lives in source default user space: pre-/{@code Rotate}, and
     * relative to the page origin rather than the crop box.  The pipeline's
     * output page is the page as PDFBox renders it — crop box shifted to the
     * origin, rotation applied, axes swapped for 90/270 — drawn into a brand-new
     * page whose user space starts at (0,0).  The annotation must move through
     * the same mapping or its region lands on the wrong part of the page.
     *
     * <p>Transformation of source point (x, y) (y-up, page space) to output
     * point (mx, my) (y-up, output page, dims swapped for 90/270):
     * <pre>
     *   R=0:   (x − llx,        (lly + H) − y)
     *   R=90:  ((lly + H) − y,  (llx + W) − x)
     *   R=180: ((llx + W) − x,  y − lly)
     *   R=270: (y − lly,        x − llx)
     * </pre>
     */
    private void remapAnnotationGeometry(COSDictionary annot, PDPage srcPage) {
        PDRectangle crop = srcPage.getCropBox();
        float llx = crop.getLowerLeftX();
        float lly = crop.getLowerLeftY();
        float w = crop.getWidth();
        float h = crop.getHeight();
        int rotation = srcPage.getRotation() % 360;
        if (rotation < 0) rotation += 360;

        remapRect(annot, rotation, llx, lly, w, h);
        remapPointPairs(annot, COSName.QUADPOINTS, rotation, llx, lly, w, h);
        remapPointPairs(annot, COSName.VERTICES, rotation, llx, lly, w, h);
        remapPointPairs(annot, COSName.L, rotation, llx, lly, w, h);
        remapPointPairs(annot, COSName.getPDFName("CL"), rotation, llx, lly, w, h);
        COSBase ink = annot.getDictionaryObject(COSName.getPDFName("InkList"));
        if (ink instanceof COSArray inkList) {
            for (COSBase sub : inkList) {
                COSBase subVal = sub;
                if (subVal instanceof COSObject cosObj) subVal = cosObj.getObject();
                if (subVal instanceof COSArray stroke) {
                    remapPointArray(stroke, rotation, llx, lly, w, h);
                }
            }
        }
    }

    private static float mapX(float x, float y, int rotation, float llx, float lly,
                              float w, float h) {
        switch (rotation) {
            case 90: return (lly + h) - y;
            case 180: return (llx + w) - x;
            case 270: return y - lly;
            default: return x - llx;
        }
    }

    private static float mapY(float x, float y, int rotation, float llx, float lly,
                              float w, float h) {
        switch (rotation) {
            case 90: return (llx + w) - x;
            case 180: return y - lly;
            case 270: return x - llx;
            default: return (lly + h) - y;
        }
    }

    private void remapRect(COSDictionary annot, int rotation, float llx, float lly,
                           float w, float h) {
        COSBase rectVal = annot.getDictionaryObject(COSName.RECT);
        if (!(rectVal instanceof COSArray rect) || rect.size() != 4) return;
        float x0 = cosFloat(rect, 0), y0 = cosFloat(rect, 1);
        float x1 = cosFloat(rect, 2), y1 = cosFloat(rect, 3);
        float mx0 = mapX(x0, y0, rotation, llx, lly, w, h);
        float my0 = mapY(x0, y0, rotation, llx, lly, w, h);
        float mx1 = mapX(x1, y1, rotation, llx, lly, w, h);
        float my1 = mapY(x1, y1, rotation, llx, lly, w, h);
        rect.set(0, new COSFloat(Math.min(mx0, mx1)));
        rect.set(1, new COSFloat(Math.min(my0, my1)));
        rect.set(2, new COSFloat(Math.max(mx0, mx1)));
        rect.set(3, new COSFloat(Math.max(my0, my1)));
    }

    /** Transforms each (x, y) pair in a flat number array (QuadPoints, Vertices, L, CL). */
    private void remapPointPairs(COSDictionary annot, COSName key, int rotation,
                                 float llx, float lly, float w, float h) {
        COSBase value = annot.getDictionaryObject(key);
        if (value instanceof COSArray arr) {
            remapPointArray(arr, rotation, llx, lly, w, h);
        }
    }

    private void remapPointArray(COSArray arr, int rotation, float llx, float lly,
                                 float w, float h) {
        for (int i = 0; i + 1 < arr.size(); i += 2) {
            float x = cosFloat(arr, i);
            float y = cosFloat(arr, i + 1);
            arr.set(i, new COSFloat(mapX(x, y, rotation, llx, lly, w, h)));
            arr.set(i + 1, new COSFloat(mapY(x, y, rotation, llx, lly, w, h)));
        }
    }

    private static float cosFloat(COSArray arr, int index) {
        COSBase base = arr.get(index);
        if (base instanceof COSNumber num) {
            return num.floatValue();
        }
        return 0f;
    }

    /**
     * Counts the source's named destinations, which are <em>not</em> carried
     * over.
     *
     * <p>Carrying the {@code /Names/Dests} tree across verbatim would be worse
     * than dropping it: the destinations point at page references that no
     * longer resolve once pages are rebuilt, so a link through one would fail
     * at click time with nothing to indicate why. Carrying them across
     * <em>correctly</em> needs the same page-reference remapping the outline
     * code already performs, which is a larger change than its apparent size.
     *
     * <p>So they are dropped, and counted here so the caller can report the
     * loss instead of letting it pass unnoticed.
     */
private int countNamedDests(PDDocument source) throws IOException {
        PDDocumentNameDictionary srcNames = source.getDocumentCatalog().getNames();
        if (srcNames == null) return 0;
        PDDestinationNameTreeNode srcDests = srcNames.getDests();
        if (srcDests == null) return 0;
        var names = srcDests.getNames();
        return names != null ? names.size() : 0;
    }

    private int copyEmbeddedFiles(PDDocument source, PDDocument output) throws IOException {
        PDDocumentNameDictionary srcNames = source.getDocumentCatalog().getNames();
        if (srcNames == null) return 0;
        PDEmbeddedFilesNameTreeNode srcEmbedded = srcNames.getEmbeddedFiles();
        if (srcEmbedded == null) return 0;

        COSDictionary srcDict = srcEmbedded.getCOSObject();
        COSDictionary dstDict = deepCopyCOSDictionary(srcDict);
        PDEmbeddedFilesNameTreeNode dstEmbedded = new PDEmbeddedFilesNameTreeNode(dstDict);

        PDDocumentNameDictionary dstNames = output.getDocumentCatalog().getNames();
        if (dstNames == null) {
            dstNames = new PDDocumentNameDictionary(output.getDocumentCatalog());
            output.getDocumentCatalog().setNames(dstNames);
        }
        dstNames.setEmbeddedFiles(dstEmbedded);

        var names = srcEmbedded.getNames();
        return names != null ? names.size() : 0;
    }

    private void copyXmlMetadata(PDDocument source, PDDocument output) throws IOException {
        PDMetadata srcMeta = source.getDocumentCatalog().getMetadata();
        if (srcMeta == null) return;

        try (var in = srcMeta.createInputStream()) {
            byte[] data = in.readAllBytes();
            PDMetadata dstMeta = new PDMetadata(output);
            dstMeta.importXMPMetadata(data);
            output.getDocumentCatalog().setMetadata(dstMeta);
        } catch (Exception e) {
            // Non-critical — skip XML metadata copy on failure
        }
    }

    private static COSDictionary deepCopyCOSDictionary(COSDictionary original) {
        return deepCopyCOSDictionary(original, new IdentityHashMap<>());
    }

    private static COSDictionary deepCopyCOSDictionary(COSDictionary original,
                                                        IdentityHashMap<COSBase, COSBase> visited) {
        COSBase existing = visited.get(original);
        if (existing instanceof COSDictionary d) return d;

        COSDictionary copy = new COSDictionary();
        visited.put(original, copy);
        for (var entry : original.entrySet()) {
            COSName key = entry.getKey();
            copy.setItem(key, deepCopyValue(entry.getValue(), visited));
        }
        return copy;
    }

    private static COSStream deepCopyCOSStream(COSStream original,
                                                IdentityHashMap<COSBase, COSBase> visited) {
        COSBase existing = visited.get(original);
        if (existing instanceof COSStream s) return s;

        COSStream copy = new COSStream();
        visited.put(original, copy);
        for (var entry : original.entrySet()) {
            COSName key = entry.getKey();
            if (COSName.LENGTH.equals(key)) continue;
            copy.setItem(key, deepCopyValue(entry.getValue(), visited));
        }
        try (InputStream in = original.createInputStream();
             OutputStream out = copy.createOutputStream()) {
            in.transferTo(out);
        } catch (IOException e) {
            // skip — stream data could not be copied
        }
        return copy;
    }

    private static COSArray deepCopyCOSArray(COSArray original,
                                              IdentityHashMap<COSBase, COSBase> visited) {
        COSBase existing = visited.get(original);
        if (existing instanceof COSArray a) return a;

        COSArray copy = new COSArray();
        visited.put(original, copy);
        for (var value : original) {
            copy.add(deepCopyValue(value, visited));
        }
        return copy;
    }

    /**
     * Deep-copies a COS value.  COSObject indirect references are left as-is
     * because they are internal to the metadata tree being copied (outline
     * items reference each other via COSObject).  Cross-document references
     * to page objects are handled by stripping known page-dependent keys
     * (e.g. /P from annotation dictionaries) in the caller.
     */
    private static COSBase deepCopyValue(COSBase value,
                                          IdentityHashMap<COSBase, COSBase> visited) {
        if (value instanceof COSStream stream) {
            return deepCopyCOSStream(stream, visited);
        }
        if (value instanceof COSDictionary dict) {
            return deepCopyCOSDictionary(dict, visited);
        }
        if (value instanceof COSArray arr) {
            return deepCopyCOSArray(arr, visited);
        }
        // COSObject (indirect references) and primitive COS types are left
        // as-is — they are immutable value objects or references valid within
        // the copied subtree.
        return value;
    }
}
