package com.mrcpdf.pipeline;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.CCITTFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdmodel.graphics.state.RenderingMode;
import org.apache.pdfbox.util.Matrix;

import com.mrcpdf.model.PageResult;
import com.mrcpdf.model.TextBlock;

/**
 * Re-assembles a searchable PDF from per-page inputs:
 *   - Source PDF (copied for page dimensions / media boxes)
 *   - Cleaned background images
 *   - Binary foreground masks (CCITT G4 compressed stencil overlay)
 *   - OCR results (invisible text layer)
 *
 * MRC-like layout (per page):
 *   1. Background layer: cleaned page image (Lossless PNG for now).
 *   2. Foreground mask: CCITT G4 compressed binary mask, drawn as a
 *      PDF ImageMask stencil in black — reveals only the text pixels
 *      on top of the background.
 *   3. Text layer: invisible OCR text (RenderingMode.NEITHER = Tr 3),
 *      selectable and searchable but not visible.
 *
 * Rendering mode NEITHER makes text invisible on screen/print while
 * keeping it selectable and searchable.
 *
 * Coordinate transformation:
 *   Page images at 300 DPI → PDF user space at 72 DPI.
 *   scaleX = pageWidth_pts  / imageWidth_px
 *   scaleY = pageHeight_pts / imageHeight_px
 *   Y is flipped: image origin is top-left, PDF origin is bottom-left.
 */
public class PDFAssembler {

    private final PDType1Font font;
    private final float minFontSize;
    private final MetadataPreserver preserver = new MetadataPreserver();
    private File fontFile;
    private PDFont dynamicFont;
    private JBIG2Compressor compressor;
    private double backgroundScale;
    private float bgSmoothSigma;
    private float bgJpegQuality;
    // JPEG quality used when a page is routed to the fidelity path (quality gate
    // skipped MRC): no foreground mask, so the background is the only visual
    // layer and must carry the page at full resolution.
    private float fidelityJpegQuality;
    private String producer;
    private int skippedGlyphCount;
    private PDStream jbig2GlobalStream;
    private boolean foregroundColorEnabled;
    private double fgScale;
    private float fgJpegQuality;
    private final Map<Integer, Boolean> glyphEncodeCache = new HashMap<>();
    private final Map<String, Float> stringWidthCache = new HashMap<>();

    public PDFAssembler() {
        this("HELVETICA", 1f);
    }

    public PDFAssembler(String fontName, float minFontSize) {
        Standard14Fonts.FontName resolved;
        try {
            resolved = Standard14Fonts.FontName.valueOf(fontName);
        } catch (IllegalArgumentException e) {
            resolved = Standard14Fonts.FontName.HELVETICA;
        }
        this.font = new PDType1Font(resolved);
        this.minFontSize = minFontSize;
        this.backgroundScale = 1.0;
        this.bgSmoothSigma = 0f;
        this.bgJpegQuality = 0.50f;
        this.fidelityJpegQuality = 0.85f;
        this.foregroundColorEnabled = false;
        this.fgScale = 0.5;
        this.fgJpegQuality = 0.7f;
    }

    public void setFont(File fontFile) {
        this.fontFile = fontFile;
    }

    public void setCompressor(JBIG2Compressor compressor) {
        this.compressor = compressor;
    }

    public void setBackgroundScale(double scale) {
        this.backgroundScale = Math.max(0.1, Math.min(1.0, scale));
    }

    public void setBgSmoothSigma(float sigma) {
        this.bgSmoothSigma = Math.max(0f, sigma);
    }

    public void setBgJpegQuality(float quality) {
        this.bgJpegQuality = Math.max(0.1f, Math.min(1.0f, quality));
    }

    /** JPEG quality for the fidelity (non-MRC, no mask) background layer. */
    public void setFidelityJpegQuality(float quality) {
        this.fidelityJpegQuality = Math.max(0.1f, Math.min(1.0f, quality));
    }

    public void setProducer(String producer) {
        this.producer = producer;
    }

    /**
     * Enables the true MRC foreground color plane: the binary mask is used as a
     * soft mask (SMask) over a (downsampled, lossy) copy of the original page,
     * so text pixels render at their original color instead of flat black.
     * Disabled by default (plain black stencil). Not PDF/A-2b compatible.
     */
    public void setForegroundColorEnabled(boolean enabled) {
        this.foregroundColorEnabled = enabled;
    }

    /** Downsample factor for the foreground color plane (0 < scale <= 1, default 0.25). */
    public void setFgScale(double scale) {
        this.fgScale = Math.max(0.1, Math.min(1.0, scale));
    }

    /** JPEG quality for the foreground color plane (0.1 - 1.0, default 0.7). */
    public void setFgJpegQuality(float quality) {
        this.fgJpegQuality = Math.max(0.1f, Math.min(1.0f, quality));
    }

    public double getBackgroundScale() { return backgroundScale; }
    public float getBgSmoothSigma() { return bgSmoothSigma; }
    public float getBgJpegQuality() { return bgJpegQuality; }
    public boolean isForegroundColorEnabled() { return foregroundColorEnabled; }
    public double getFgScale() { return fgScale; }
    public float getFgJpegQuality() { return fgJpegQuality; }

    private PDImageXObject encodeBackgroundJpeg(PDDocument doc, BufferedImage image, float quality, boolean hasMask) throws IOException {
        byte[] jpegBytes = ImageEncoder.encodeBackground(image, quality, hasMask, backgroundScale, bgSmoothSigma);
        return PDImageXObject.createFromByteArray(doc, jpegBytes, "image");
    }

    /**
     * Encodes the foreground color plane: a downsampled (fgScale), lossy copy of
     * the original page, at 4:4:4 chroma to keep colored text edges sharp. No
     * Gaussian smoothing is applied — this layer is clipped by the soft mask to
     * the exact text shapes, so pre-blur would soften the colors.
     */
    private PDImageXObject encodeForegroundColorJpeg(PDDocument doc, BufferedImage image) throws IOException {
        byte[] jpegBytes = ImageEncoder.encodeForegroundColor(image, fgJpegQuality, fgScale);
        return PDImageXObject.createFromByteArray(doc, jpegBytes, "image");
    }

    static BufferedImage gaussianBlur(BufferedImage image, float sigma) {
        int radius = (int) Math.ceil(2 * sigma);
        if (radius < 1) return image;

        float[] kernel = new float[2 * radius + 1];
        float sum = 0;
        for (int i = -radius; i <= radius; i++) {
            float v = (float) Math.exp(-(i * i) / (2 * sigma * sigma));
            kernel[i + radius] = v;
            sum += v;
        }
        for (int i = 0; i < kernel.length; i++) kernel[i] /= sum;

        int w = image.getWidth(), h = image.getHeight();
        BufferedImage temp = new BufferedImage(w, h, BufferedImage.TYPE_3BYTE_BGR);
        BufferedImage result = new BufferedImage(w, h, BufferedImage.TYPE_3BYTE_BGR);

        // Horizontal pass
        int[] srcRow = new int[w];
        int[] dstRow = new int[w];
        for (int y = 0; y < h; y++) {
            image.getRGB(0, y, w, 1, srcRow, 0, w);
            for (int x = 0; x < w; x++) {
                float r = 0, g = 0, b = 0;
                for (int k = -radius; k <= radius; k++) {
                    int sx = Math.max(0, Math.min(w - 1, x + k));
                    int px = srcRow[sx];
                    float f = kernel[k + radius];
                    r += f * ((px >> 16) & 0xFF);
                    g += f * ((px >> 8) & 0xFF);
                    b += f * (px & 0xFF);
                }
                dstRow[x] = 0xFF000000 | ((int) r << 16) | ((int) g << 8) | (int) b;
            }
            temp.setRGB(0, y, w, 1, dstRow, 0, w);
        }

        // Vertical pass (banded to reduce peak memory)
        int BAND_HEIGHT = 64;
        for (int bandStart = 0; bandStart < h; bandStart += BAND_HEIGHT) {
            int bandEnd = Math.min(h, bandStart + BAND_HEIGHT);
            int readStart = Math.max(0, bandStart - radius);
            int readEnd = Math.min(h, bandEnd + radius);
            int readH = readEnd - readStart;
            int bandH = bandEnd - bandStart;

            int[] pixels = temp.getRGB(0, readStart, w, readH, null, 0, w);
            int[] outPixels = new int[w * bandH];

            for (int y = bandStart; y < bandEnd; y++) {
                for (int x = 0; x < w; x++) {
                    float r = 0, g = 0, b = 0;
                    for (int k = -radius; k <= radius; k++) {
                        int sy = Math.max(0, Math.min(h - 1, y + k));
                        int px = pixels[(sy - readStart) * w + x];
                        float f = kernel[k + radius];
                        r += f * ((px >> 16) & 0xFF);
                        g += f * ((px >> 8) & 0xFF);
                        b += f * (px & 0xFF);
                    }
                    outPixels[(y - bandStart) * w + x] = 0xFF000000 | ((int) r << 16) | ((int) g << 8) | (int) b;
                }
            }
            result.setRGB(0, bandStart, w, bandH, outPixels, 0, w);
        }

        return result;
    }

    /**
     * Builds a searchable PDF with MRC-like foreground mask overlay.
     *
     * When foregroundMasks is provided, each mask is CCITT G4 compressed
     * and drawn as a PDF ImageMask stencil in black on top of the background.
     * This reproduces the original text pixels at full sharpness, while
     * the background layer carries the cleaned (de-speckled) page image.
     *
     * Backgrounds and foreground masks are consumed lazily via Iterator
     * so callers can supply images one at a time without holding all
     * page bitmaps in memory at once.
     *
     * @param sourcePdf       Original PDF (for per-page media boxes).
     * @param backgrounds     Per-page cleaned background images.
     * @param foregroundMasks Per-page binary foreground masks (TYPE_BYTE_BINARY,
     *                        black=text, white=background), or null to skip.
     * @param ocrResults      Per-page OCR results.
     * @param usePdfa         Enable PDF/A-2b output with XMP metadata.
     * @return A new PDDocument with foreground mask overlay and searchable text.
     */
    public PDDocument assemble(File sourcePdf,
                               Iterator<BufferedImage> backgrounds,
                               Iterator<BufferedImage> foregroundMasks,
                               List<PageResult> ocrResults,
                               boolean usePdfa) throws IOException {
        return assemble(sourcePdf, backgrounds, foregroundMasks, null, ocrResults, usePdfa);
    }

    /**
     * Assembles a searchable PDF with MRC-like foreground overlay and an
     * optional true-color foreground plane.
     *
     * @param sourcePdf        Original PDF (for per-page media boxes).
     * @param backgrounds      Per-page cleaned background images.
     * @param foregroundMasks  Per-page binary foreground masks (TYPE_BYTE_BINARY,
     *                         black=text, white=background), or null to skip.
     * @param foregroundColors Per-page original (color) page images used as the
     *                         foreground color plane when foreground color mode is
     *                         enabled, or null to keep the black stencil.
     * @param ocrResults       Per-page OCR results.
     * @param usePdfa          Enable PDF/A-2b output with XMP metadata.
     * @return A new PDDocument with foreground mask overlay and searchable text.
     */
    public PDDocument assemble(File sourcePdf,
                               Iterator<BufferedImage> backgrounds,
                               Iterator<BufferedImage> foregroundMasks,
                               Iterator<BufferedImage> foregroundColors,
                               List<PageResult> ocrResults,
                               boolean usePdfa) throws IOException {
        PDDocument output = new PDDocument();
        List<PDPage> outPages = new java.util.ArrayList<>();

        try (PDDocument source = Loader.loadPDF(sourcePdf)) {
            int pageCount = source.getNumberOfPages();
            for (int i = 0; i < pageCount; i++) {
                BufferedImage bg = backgrounds.next();
                BufferedImage fg = foregroundMasks != null && foregroundMasks.hasNext() ? foregroundMasks.next() : null;
                BufferedImage fgColor = foregroundColors != null && foregroundColors.hasNext() ? foregroundColors.next() : null;
                PDPage page = addPage(output, source, i, bg, fg, fgColor, ocrResults.get(i));
                outPages.add(page);
            }
            finishAssembly(output, source, outPages, usePdfa);
        }

        return output;
    }

    /**
     * Convenience overload accepting Lists.  Each list is converted to an
     * iterator so the semantics are identical to the Iterator-based version.
     */
    public PDDocument assemble(File sourcePdf,
                               List<BufferedImage> backgrounds,
                               List<BufferedImage> foregroundMasks,
                               List<PageResult> ocrResults,
                               boolean usePdfa) throws IOException {
        return assemble(sourcePdf,
            backgrounds.iterator(),
            foregroundMasks != null ? foregroundMasks.iterator() : null,
            null,
            ocrResults, usePdfa);
    }

    /**
     * Convenience overload accepting Lists for the foreground color plane too.
     */
    public PDDocument assemble(File sourcePdf,
                               List<BufferedImage> backgrounds,
                               List<BufferedImage> foregroundMasks,
                               List<BufferedImage> foregroundColors,
                               List<PageResult> ocrResults,
                               boolean usePdfa) throws IOException {
        return assemble(sourcePdf,
            backgrounds.iterator(),
            foregroundMasks != null ? foregroundMasks.iterator() : null,
            foregroundColors != null ? foregroundColors.iterator() : null,
            ocrResults, usePdfa);
    }

    /**
     * Renders one page into the output document.  Call repeatedly for
     * each page of the source, then call {@link #finishAssembly}.
     *
     * @param output        The output PDDocument being built.
     * @param source        The source PDDocument (already loaded).
     * @param pageIndex     0-based page index in source.
     * @param background    Cleaned background image for this page.
     * @param foregroundMask Binary mask or null to skip the stencil layer.
     * @param ocr           OCR result for this page.
     * @return The newly created PDPage added to output.
     */
    public PDPage addPage(PDDocument output, PDDocument source, int pageIndex,
                          BufferedImage background, BufferedImage foregroundMask,
                          PageResult ocr) throws IOException {
        return addPage(output, source, pageIndex, background, foregroundMask, null, ocr);
    }

    /**
     * Renders one page into the output document.  Call repeatedly for
     * each page of the source, then call {@link #finishAssembly}.
     *
     * @param output          The output PDDocument being built.
     * @param source          The source PDDocument (already loaded).
     * @param pageIndex       0-based page index in source.
     * @param background      Cleaned background image for this page.
     * @param foregroundMask  Binary mask or null to skip the stencil layer.
     * @param foregroundColor Original (color) page image used for the foreground
     *                        color plane when foreground color mode is enabled,
     *                        or null to keep the black stencil.
     * @param ocr             OCR result for this page.
     * @return The newly created PDPage added to output.
     */
    public PDPage addPage(PDDocument output, PDDocument source, int pageIndex,
                          BufferedImage background, BufferedImage foregroundMask,
                          BufferedImage foregroundColor,
                          PageResult ocr) throws IOException {
        PDPage sourcePage = source.getPage(pageIndex);
        PDRectangle cropBox = sourcePage.getCropBox();
        float pageW = cropBox.getWidth();
        float pageH = cropBox.getHeight();
        if (sourcePage.getRotation() % 180 != 0) {
            float tmp = pageW;
            pageW = pageH;
            pageH = tmp;
        }

        PDPage outPage = new PDPage(new PDRectangle(pageW, pageH));
        output.addPage(outPage);

        try (PDPageContentStream cs = new PDPageContentStream(output, outPage)) {
            boolean hasMask = foregroundMask != null;
            PDImageXObject bgXObject = encodeBackgroundJpeg(output, background, bgJpegQuality, hasMask);
            cs.drawImage(bgXObject, 0, 0, pageW, pageH);

            drawForegroundLayers(cs, output, foregroundMask, foregroundColor, pageW, pageH);

            PDFont pageFont = resolveFont(output);
            writeTextLayer(cs, ocr, pageW, pageH, pageFont);
        }

        return outPage;
    }

    /**
     * Adds a page using pre-encoded JPEG byte arrays for background and
     * foreground color, eliminating the intermediate BMP disk round-trip.
     */
    public PDPage addPage(PDDocument output, PDDocument source, int pageIndex,
                          byte[] bgJpegBytes, byte[] fgColorJpegBytes,
                          BufferedImage foregroundMask, PageResult ocr) throws IOException {
        PDPage sourcePage = source.getPage(pageIndex);
        PDRectangle cropBox = sourcePage.getCropBox();
        float pageW = cropBox.getWidth();
        float pageH = cropBox.getHeight();
        if (sourcePage.getRotation() % 180 != 0) {
            float tmp = pageW;
            pageW = pageH;
            pageH = tmp;
        }

        PDPage outPage = new PDPage(new PDRectangle(pageW, pageH));
        output.addPage(outPage);

        try (PDPageContentStream cs = new PDPageContentStream(output, outPage)) {
            PDImageXObject bgXObject = PDImageXObject.createFromByteArray(output, bgJpegBytes, "background");
            cs.drawImage(bgXObject, 0, 0, pageW, pageH);

            if (fgColorJpegBytes != null && foregroundColorEnabled) {
                PDImageXObject maskXObject;
                if (compressor != null) {
                    JBIG2Compressor.CompressionResult result = compressor.compress(foregroundMask);
                    if (result.isJbig2()) {
                        maskXObject = createJbig2SmaskXObject(output, result);
                    } else {
                        maskXObject = CCITTFactory.createFromImage(output, foregroundMask);
                    }
                } else {
                    maskXObject = CCITTFactory.createFromImage(output, foregroundMask);
                }
                maskXObject.getCOSObject().setBoolean(COSName.IMAGE_MASK, false);
                maskXObject.getCOSObject().setItem(COSName.COLORSPACE, COSName.DEVICEGRAY);
                maskXObject.getCOSObject().setItem(COSName.DECODE, maskDecode());

                PDImageXObject fgColorXObject = PDImageXObject.createFromByteArray(output, fgColorJpegBytes, "foreground-color");
                fgColorXObject.getCOSObject().setItem(COSName.SMASK, maskXObject.getCOSObject());
                cs.drawImage(fgColorXObject, 0, 0, pageW, pageH);
            } else if (foregroundMask != null) {
                PDImageXObject fgImage;
                if (compressor != null) {
                    JBIG2Compressor.CompressionResult result = compressor.compress(foregroundMask);
                    if (result.isJbig2()) {
                        fgImage = createJbig2ImageXObject(output, result);
                    } else {
                        fgImage = CCITTFactory.createFromImage(output, foregroundMask);
                    }
                } else {
                    fgImage = CCITTFactory.createFromImage(output, foregroundMask);
                }
                fgImage.getCOSObject().setBoolean(COSName.IMAGE_MASK, true);
                fgImage.getCOSObject().removeItem(COSName.COLORSPACE);
                cs.setNonStrokingColor(0f, 0f, 0f);
                cs.drawImage(fgImage, 0, 0, pageW, pageH);
            }

PDFont pageFont = resolveFont(output);
            writeTextLayer(cs, ocr, pageW, pageH, pageFont);
        }

        return outPage;
    }

    /**
     * Carries a source page into the output untouched: its own content stream,
     * fonts and images, with no rasterisation and no re-encoded text layer.
     * Used for digital-native pages, where rendering could only lose what is
     * already there.
     *
     * <p>{@link PDDocument#importPage} shallow-copies the page's own entries, so
     * a page that owns its {@code /Resources} comes through as it is. A page
     * that <em>inherits</em> them from its {@code /Pages} parent does not: the
     * page dictionary has no such entry to copy, and the content stream would
     * then name fonts and images nothing resolves (PDFBox's own javadoc calls
     * this out and asks callers to restore them).
     *
     * @param output    The output PDDocument being built.
     * @param source    The source PDDocument (already loaded).
     * @param pageIndex 0-based page index in source.
     * @return The imported page, added to output.
     */
    public PDPage importOriginalPage(PDDocument output, PDDocument source, int pageIndex)
            throws IOException {
        PDPage srcPage = source.getPage(pageIndex);
        PDPage imported = output.importPage(srcPage);
        if (!srcPage.getCOSObject().containsKey(COSName.RESOURCES)) {
            PDResources resources = srcPage.getResources();
            if (resources != null) {
                imported.setResources(resources);
            }
        }
        return imported;
    }

    /**
     * Renders one page with the fidelity-preserving (non-MRC) path: the
     * original page image as the only visual layer at full resolution and
     * higher JPEG quality, no foreground mask.  Used when the per-page text
     * quality gate decides MRC compression would degrade the page.
     *
     * @param output    The output PDDocument being built.
     * @param source    The source PDDocument (already loaded).
     * @param pageIndex 0-based page index in source.
     * @param background Original (un-segmented) page image.
     * @param ocr       OCR result for this page.
     * @return The newly created PDPage added to output.
     */
    public PDPage addPageFidelity(PDDocument output, PDDocument source, int pageIndex,
                                  BufferedImage background, PageResult ocr) throws IOException {
        PDPage sourcePage = source.getPage(pageIndex);
        PDRectangle cropBox = sourcePage.getCropBox();
        float pageW = cropBox.getWidth();
        float pageH = cropBox.getHeight();
        if (sourcePage.getRotation() % 180 != 0) {
            float tmp = pageW;
            pageW = pageH;
            pageH = tmp;
        }

        PDPage outPage = new PDPage(new PDRectangle(pageW, pageH));
        output.addPage(outPage);

        try (PDPageContentStream cs = new PDPageContentStream(output, outPage)) {
            // No foreground mask: hasMask=false keeps the full-resolution
            // background without downsampling/smoothing, encoded at the higher
            // fidelity JPEG quality.
            PDImageXObject bgXObject = encodeBackgroundJpeg(output, background, fidelityJpegQuality, false);
            cs.drawImage(bgXObject, 0, 0, pageW, pageH);

            PDFont pageFont = resolveFont(output);
            writeTextLayer(cs, ocr, pageW, pageH, pageFont);
        }

        return outPage;
    }

    private void drawForegroundLayers(PDPageContentStream cs, PDDocument output,
                                       BufferedImage foregroundMask, BufferedImage foregroundColor,
                                       float pageW, float pageH) throws IOException {
        if (foregroundMask == null) return;
        if (foregroundColorEnabled && foregroundColor != null) {
            PDImageXObject maskXObject;
            if (compressor != null) {
                JBIG2Compressor.CompressionResult result = compressor.compress(foregroundMask);
                if (result.isJbig2()) {
                    maskXObject = createJbig2SmaskXObject(output, result);
                } else {
                    maskXObject = CCITTFactory.createFromImage(output, foregroundMask);
                }
            } else {
                maskXObject = CCITTFactory.createFromImage(output, foregroundMask);
            }
            maskXObject.getCOSObject().setBoolean(COSName.IMAGE_MASK, false);
            maskXObject.getCOSObject().setItem(COSName.COLORSPACE, COSName.DEVICEGRAY);
            maskXObject.getCOSObject().setItem(COSName.DECODE, maskDecode());

            PDImageXObject fgColorXObject = encodeForegroundColorJpeg(output, foregroundColor);
            fgColorXObject.getCOSObject().setItem(COSName.SMASK, maskXObject.getCOSObject());
            cs.drawImage(fgColorXObject, 0, 0, pageW, pageH);
        } else {
            PDImageXObject fgImage;
            if (compressor != null) {
                JBIG2Compressor.CompressionResult result = compressor.compress(foregroundMask);
                if (result.isJbig2()) {
                    fgImage = createJbig2ImageXObject(output, result);
                } else {
                    fgImage = CCITTFactory.createFromImage(output, foregroundMask);
                }
            } else {
                fgImage = CCITTFactory.createFromImage(output, foregroundMask);
            }
            fgImage.getCOSObject().setBoolean(COSName.IMAGE_MASK, true);
            fgImage.getCOSObject().removeItem(COSName.COLORSPACE);
            cs.setNonStrokingColor(0f, 0f, 0f);
            cs.drawImage(fgImage, 0, 0, pageW, pageH);
        }
    }

    /**
     * Copies metadata from the source and optionally adds PDF/A-2b info.
     * Must be called after all {@link #addPage} calls are complete.
     *
     * @return Counts of copied metadata elements, or null if no source was provided.
     */
    public MetadataPreserver.PreserveResult finishAssembly(PDDocument output, PDDocument source,
                               List<PDPage> outPages, boolean usePdfa) throws IOException {
        MetadataPreserver.PreserveResult preserved = preserver.preserve(source, output, outPages);
        if (usePdfa) {
            PdfaWriter.addPdfaMetadata(output);
        }
        if (producer != null) {
            PDDocumentInformation info = output.getDocumentInformation();
            String existing = info != null ? info.getProducer() : null;
            String newProducer = (existing != null && !existing.isBlank())
                    ? existing + " -> " + producer
                    : producer;
            if (info == null) {
                info = new PDDocumentInformation();
                output.setDocumentInformation(info);
            }
            info.setProducer(newProducer);
        }
        dynamicFont = null;
        jbig2GlobalStream = null;
        glyphEncodeCache.clear();
        stringWidthCache.clear();
        if (skippedGlyphCount > 0) {
            System.out.printf("  Warning: %d characters dropped from invisible text layer — unsupported glyphs for font.%n", skippedGlyphCount);
            System.out.println("  Set pdf.fontPath in settings.jsonc to a CJK TTF/OTF file (e.g., NotoSansCJKsc-Regular.otf).");
            skippedGlyphCount = 0;
        }
        return preserved;
    }

    private PDImageXObject createJbig2ImageXObject(PDDocument doc, JBIG2Compressor.CompressionResult result) throws IOException {
        PDImageXObject img = new PDImageXObject(doc);
        img.setWidth(result.getWidth());
        img.setHeight(result.getHeight());
        img.setBitsPerComponent(1);
        img.setStencil(true);
        try (OutputStream os = img.getStream().createOutputStream()) {
            os.write(result.getData());
        }
        img.getCOSObject().setItem(COSName.FILTER, COSName.JBIG2_DECODE);
        return img;
    }

    private PDImageXObject createJbig2ImageXObject(PDDocument doc,
                                                    byte[] combinedData,
                                                    int width,
                                                    int height) throws IOException {
        PDImageXObject img = new PDImageXObject(doc);
        img.setWidth(width);
        img.setHeight(height);
        img.setBitsPerComponent(1);
        img.setStencil(true);
        try (OutputStream os = img.getStream().createOutputStream()) {
            os.write(combinedData);
        }
        img.getCOSObject().setItem(COSName.FILTER, COSName.JBIG2_DECODE);
        return img;
    }

    /**
     * Creates a 1-bit DeviceGray JBIG2 XObject for use as a soft mask (SMask),
     * instead of the black ImageMask stencil. Text samples decode to black (0),
     * so a Decode [1 0] array inverts them: text becomes white (255) = opaque
     * alpha, background becomes black (0) = transparent.
     */
    private PDImageXObject createJbig2SmaskXObject(PDDocument doc, JBIG2Compressor.CompressionResult result) throws IOException {
        PDImageXObject img = new PDImageXObject(doc);
        img.setWidth(result.getWidth());
        img.setHeight(result.getHeight());
        img.setBitsPerComponent(1);
        img.setStencil(false);
        img.getCOSObject().setItem(COSName.COLORSPACE, COSName.DEVICEGRAY);
        img.getCOSObject().setItem(COSName.DECODE, maskDecode());
        try (OutputStream os = img.getStream().createOutputStream()) {
            os.write(result.getData());
        }
        img.getCOSObject().setItem(COSName.FILTER, COSName.JBIG2_DECODE);
        return img;
    }

    private PDImageXObject createJbig2SmaskXObject(PDDocument doc,
                                                    byte[] combinedData,
                                                    int width,
                                                    int height) throws IOException {
        PDImageXObject img = new PDImageXObject(doc);
        img.setWidth(width);
        img.setHeight(height);
        img.setBitsPerComponent(1);
        img.setStencil(false);
        img.getCOSObject().setItem(COSName.COLORSPACE, COSName.DEVICEGRAY);
        img.getCOSObject().setItem(COSName.DECODE, maskDecode());
        try (OutputStream os = img.getStream().createOutputStream()) {
            os.write(combinedData);
        }
        img.getCOSObject().setItem(COSName.FILTER, COSName.JBIG2_DECODE);
        return img;
    }

    /**
     * Decode array [1 0] for a 1-bit soft-mask XObject: inverts the bilevel
     * samples so text (sample 0) maps to opaque white and background (sample 1)
     * maps to transparent black.
     */
    private static org.apache.pdfbox.cos.COSArray maskDecode() {
        org.apache.pdfbox.cos.COSArray decode = new org.apache.pdfbox.cos.COSArray();
        decode.add(org.apache.pdfbox.cos.COSInteger.get(1));
        decode.add(org.apache.pdfbox.cos.COSInteger.get(0));
        return decode;
    }

    /**
     * Adds one page to the output document using pre-compressed JBIG2
     * foreground data with a shared global symbol dictionary.
     */
    public PDPage addPageJbig2(PDDocument output, PDDocument source, int pageIndex,
                               BufferedImage background,
                               byte[] jbig2PageData, byte[] jbig2GlobalSym,
                               int fgWidth, int fgHeight,
                               PageResult ocr) throws IOException {
        return addPageJbig2(output, source, pageIndex, background, jbig2PageData, jbig2GlobalSym,
                fgWidth, fgHeight, null, ocr);
    }

    /**
     * Adds one page using pre-compressed JBIG2 foreground data, optionally with
     * a true-color foreground plane (the JBIG2 mask then acts as a soft mask).
     */
    public PDPage addPageJbig2(PDDocument output, PDDocument source, int pageIndex,
                               BufferedImage background,
                               byte[] jbig2PageData, byte[] jbig2GlobalSym,
                               int fgWidth, int fgHeight,
                               BufferedImage foregroundColor,
                               PageResult ocr) throws IOException {
        PDPage sourcePage = source.getPage(pageIndex);
        PDRectangle cropBox = sourcePage.getCropBox();
        float pageW = cropBox.getWidth();
        float pageH = cropBox.getHeight();
        // Rotated (90/270) pages render in display orientation with swapped
        // width/height; use those display dimensions so the pre-rotated
        // background is drawn undistorted and text coordinates stay aligned.
        if (sourcePage.getRotation() % 180 != 0) {
            float tmp = pageW;
            pageW = pageH;
            pageH = tmp;
        }

        PDPage outPage = new PDPage(new PDRectangle(pageW, pageH));
        output.addPage(outPage);

        try (PDPageContentStream cs = new PDPageContentStream(output, outPage)) {
            // Layer 1: background image — lower quality JPEG is safe because the JBIG2
            // foreground mask preserves text pixels at full sharpness.
            PDImageXObject bgXObject = encodeBackgroundJpeg(output, background, bgJpegQuality, true);
            cs.drawImage(bgXObject, 0, 0, pageW, pageH);

            // Layer 2: JBIG2 foreground — store global symbol dictionary
            // as a separate stream and reference it via /JBIG2Globals in decode parms.
            // In foreground-color mode the JBIG2 mask is used as a soft mask over a
            // color plane; otherwise it is a black ImageMask stencil.
            if (jbig2PageData != null && jbig2GlobalSym != null) {
                if (jbig2GlobalStream == null) {
                    jbig2GlobalStream = new PDStream(output);
                    try (OutputStream os = jbig2GlobalStream.createOutputStream()) {
                        os.write(jbig2GlobalSym);
                    }
                }
                COSDictionary decodeParms = new COSDictionary();
                decodeParms.setItem(COSName.JBIG2_GLOBALS, jbig2GlobalStream);

                if (foregroundColorEnabled && foregroundColor != null) {
                    PDImageXObject maskXObject = createJbig2SmaskXObject(output,
                            jbig2PageData, fgWidth, fgHeight);
                    maskXObject.getCOSObject().setItem(COSName.DECODE_PARMS, decodeParms);
                    maskXObject.getCOSObject().setBoolean(COSName.IMAGE_MASK, false);
                    maskXObject.getCOSObject().setItem(COSName.COLORSPACE, COSName.DEVICEGRAY);

                    PDImageXObject fgColorXObject = encodeForegroundColorJpeg(output, foregroundColor);
                    fgColorXObject.getCOSObject().setItem(COSName.SMASK, maskXObject.getCOSObject());
                    cs.drawImage(fgColorXObject, 0, 0, pageW, pageH);
                } else {
                    PDImageXObject fgImage = createJbig2ImageXObject(output,
                            jbig2PageData, fgWidth, fgHeight);
                    fgImage.getCOSObject().setItem(COSName.DECODE_PARMS, decodeParms);
                    fgImage.getCOSObject().setBoolean(COSName.IMAGE_MASK, true);
                    fgImage.getCOSObject().removeItem(COSName.COLORSPACE);
                    cs.setNonStrokingColor(0f, 0f, 0f);
                    cs.drawImage(fgImage, 0, 0, pageW, pageH);
                }
            }

            // Layer 3: invisible OCR text
            PDFont pageFont = resolveFont(output);
            writeTextLayer(cs, ocr, pageW, pageH, pageFont);
        }

        return outPage;
    }

    /**
     * Adds a page using pre-compressed JBIG2 foreground data with pre-encoded
     * JPEG byte arrays for background and foreground color (no disk round-trip).
     */
    public PDPage addPageJbig2(PDDocument output, PDDocument source, int pageIndex,
                               byte[] bgJpegBytes, byte[] fgColorJpegBytes,
                               byte[] jbig2PageData, byte[] jbig2GlobalSym,
                               int fgWidth, int fgHeight,
                               PageResult ocr) throws IOException {
        PDPage sourcePage = source.getPage(pageIndex);
        PDRectangle cropBox = sourcePage.getCropBox();
        float pageW = cropBox.getWidth();
        float pageH = cropBox.getHeight();
        if (sourcePage.getRotation() % 180 != 0) {
            float tmp = pageW;
            pageW = pageH;
            pageH = tmp;
        }

        PDPage outPage = new PDPage(new PDRectangle(pageW, pageH));
        output.addPage(outPage);

        try (PDPageContentStream cs = new PDPageContentStream(output, outPage)) {
            PDImageXObject bgXObject = PDImageXObject.createFromByteArray(output, bgJpegBytes, "background");
            cs.drawImage(bgXObject, 0, 0, pageW, pageH);

            if (jbig2PageData != null && jbig2GlobalSym != null) {
                if (jbig2GlobalStream == null) {
                    jbig2GlobalStream = new PDStream(output);
                    try (OutputStream os = jbig2GlobalStream.createOutputStream()) {
                        os.write(jbig2GlobalSym);
                    }
                }
                COSDictionary decodeParms = new COSDictionary();
                decodeParms.setItem(COSName.JBIG2_GLOBALS, jbig2GlobalStream);

                if (foregroundColorEnabled && fgColorJpegBytes != null) {
                    PDImageXObject maskXObject = createJbig2SmaskXObject(output,
                            jbig2PageData, fgWidth, fgHeight);
                    maskXObject.getCOSObject().setItem(COSName.DECODE_PARMS, decodeParms);
                    maskXObject.getCOSObject().setBoolean(COSName.IMAGE_MASK, false);
                    maskXObject.getCOSObject().setItem(COSName.COLORSPACE, COSName.DEVICEGRAY);

                    PDImageXObject fgColorXObject = PDImageXObject.createFromByteArray(output, fgColorJpegBytes, "foreground-color");
                    fgColorXObject.getCOSObject().setItem(COSName.SMASK, maskXObject.getCOSObject());
                    cs.drawImage(fgColorXObject, 0, 0, pageW, pageH);
                } else {
                    PDImageXObject fgImage = createJbig2ImageXObject(output,
                            jbig2PageData, fgWidth, fgHeight);
                    fgImage.getCOSObject().setItem(COSName.DECODE_PARMS, decodeParms);
                    fgImage.getCOSObject().setBoolean(COSName.IMAGE_MASK, true);
                    fgImage.getCOSObject().removeItem(COSName.COLORSPACE);
                    cs.setNonStrokingColor(0f, 0f, 0f);
                    cs.drawImage(fgImage, 0, 0, pageW, pageH);
                }
            }

            PDFont pageFont = resolveFont(output);
            writeTextLayer(cs, ocr, pageW, pageH, pageFont);
        }

        return outPage;
    }

    /**
     * Draws the invisible, searchable text layer for a page.
     *
     * Each word is stretched horizontally to fill its detected bounding box, so
     * the extracted text stays aligned with the visual text in the background.
     * Characters the current font cannot encode are dropped individually rather
     * than skipping the whole word, so a single unsupported glyph (emoji, rare
     * symbol) no longer removes the rest of the word from the searchable layer.
     */
    private void writeTextLayer(PDPageContentStream cs, PageResult ocr,
                                float pageW, float pageH, PDFont pageFont) throws IOException {
        float scaleX = pageW / ocr.getWidth();
        float scaleY = pageH / ocr.getHeight();
        cs.beginText();
        cs.setFont(pageFont, minFontSize);
        cs.setRenderingMode(RenderingMode.NEITHER);

        for (TextBlock tb : ocr.getTextBlocks()) {
            float x = tb.getBbox().x * scaleX;
            float y = pageH - (tb.getBbox().y + tb.getBbox().height * baselineRatio(tb)) * scaleY;
            float fontSize = Math.max(tb.getBbox().height * scaleY, minFontSize);

            String safeWord = filterSupportedChars(pageFont, tb.getWord());

            // Word-level scaling: uniform horizontal stretch to fill bbox.
            // The stretch is folded into the font size (fontSize * sx) instead
            // of a text-matrix horizontal scale so that any viewer — whether or
            // not it applies text-matrix scaling when hit-testing — computes the
            // same word boxes. The rendered advance widths are identical.
            Float cachedWidth = stringWidthCache.get(safeWord);
            float naturalWidth;
            if (cachedWidth != null) {
                naturalWidth = cachedWidth * fontSize;
            } else {
                try {
                    float widthAtPt = pageFont.getStringWidth(safeWord) / 1000f;
                    naturalWidth = widthAtPt * fontSize;
                    stringWidthCache.put(safeWord, widthAtPt);
                } catch (IllegalArgumentException e) {
                    naturalWidth = safeWord.length() * fontSize * 0.5f;
                }
            }
            float targetWidth = tb.getBbox().width * scaleX;
            float sx = naturalWidth > 0 ? targetWidth / naturalWidth : 1.0f;
            cs.setFont(pageFont, fontSize * sx);
            cs.setTextMatrix(Matrix.getTranslateInstance(x, y));
            if (!safeWord.isEmpty()) {
                try {
                    cs.showText(safeWord);
                } catch (Exception e) {
                    skippedGlyphCount++;
                }
            }
        }
        cs.endText();
    }

    /**
     * Returns the characters of {@code word} that {@code font} can encode,
     * dropping the rest. Unsupported characters are counted in
     * {@link #skippedGlyphCount}.
     */
    private String filterSupportedChars(PDFont font, String word) {
        StringBuilder sb = new StringBuilder(word.length());
        int fontKey = System.identityHashCode(font);
        for (int i = 0; i < word.length(); i++) {
            String ch = word.substring(i, i + 1);
            int cacheKey = fontKey ^ ch.hashCode();
            Boolean cached = glyphEncodeCache.get(cacheKey);
            if (cached != null) {
                if (cached) sb.append(ch);
                else skippedGlyphCount++;
            } else {
                try {
                    font.encode(ch);
                    glyphEncodeCache.put(cacheKey, Boolean.TRUE);
                    sb.append(ch);
                } catch (Exception e) {
                    glyphEncodeCache.put(cacheKey, Boolean.FALSE);
                    skippedGlyphCount++;
                }
            }
        }
        return sb.toString();
    }

    private static float baselineRatio(TextBlock tb) {
        List<float[]> positions = tb.getCharPositions();
        if (positions != null && !positions.isEmpty()) {
            float[] baselines = new float[positions.size()];
            for (int i = 0; i < positions.size(); i++) {
                baselines[i] = positions.get(i)[1];
            }
            Arrays.sort(baselines);
            float median = baselines[baselines.length / 2];
            // bbox.y is the topmost baseline in pixels; place the drawn baseline
            // at the median baseline so uniform lines land exactly on the source
            // baseline (ratio ~0) and words with a raised/dropped glyph stay on
            // their main baseline.
            float ratio = (median - tb.getBbox().y) / (float) tb.getBbox().height;
            return Math.max(0f, Math.min(1.0f, ratio));
        }
        String word = tb.getWord();
        for (int i = 0; i < word.length(); i++) {
            char cp = word.charAt(i);
            if ((cp >= 0x4E00 && cp <= 0x9FFF)
                    || (cp >= 0x3040 && cp <= 0x309F)
                    || (cp >= 0x30A0 && cp <= 0x30FF)
                    || (cp >= 0xAC00 && cp <= 0xD7AF)
                    || (cp >= 0x3400 && cp <= 0x4DBF)) {
                return 0.85f;
            }
        }
        return 0.75f;
    }

    /**
     * Resolves the font for the invisible text layer.
     * Priority: explicit fontFile > auto-detected CJK font > default PDType1Font.
     * Auto-detect is only attempted if a CJK font is explicitly requested
     * via fontFile being set.
     */
    private PDFont resolveFont(PDDocument output) {
        if (fontFile != null && fontFile.exists()) {
            if (dynamicFont == null) {
                dynamicFont = FontResolver.loadFont(output, fontFile);
            }
            if (dynamicFont != null) return dynamicFont;
        }
        return font;
    }

    /**
     * Finds a CJK font suitable for the invisible text layer.
     * Priority: bundled font (deps/fonts/) > system font.
     * Returns the first existing font file, or null if none found.
     */
    public static File findCjkFont() {
        // 1. Bundled font from bootstrap (Noto Sans SC, SIL OFL 1.1)
        File bundled = new File("deps/fonts/NotoSansSC-Regular.ttf");
        if (bundled.exists() && bundled.canRead()) return bundled;

        // 2. System fonts
        com.mrcpdf.util.PlatformUtils.Os os = com.mrcpdf.util.PlatformUtils.detectOs();
        String[][] candidates;
        switch (os) {
            case MAC:
                candidates = new String[][]{
                    {"/System/Library/Fonts/PingFang.ttc"},
                    {"/System/Library/Fonts/STHeiti Light.ttc"},
                    {"/Library/Fonts/Arial Unicode.ttf"},

                };
                break;
            case WINDOWS:
                candidates = new String[][]{
                    {"C:\\Windows\\Fonts\\msyh.ttc"},
                    {"C:\\Windows\\Fonts\\msyhbd.ttc"},
                    {"C:\\Windows\\Fonts\\simsun.ttc"},
                    {"C:\\Windows\\Fonts\\simhei.ttf"},
                };
                break;
            default: // LINUX
                candidates = new String[][]{
                    {"/usr/share/fonts/truetype/droid/DroidSansFallbackFull.ttf"},
                    {"/usr/share/fonts/wqy-microhei/wqy-microhei.ttc"},
                    {"/usr/share/fonts/wqy-zenhei/wqy-zenhei.ttc"},
                    {"/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc"},
                    {"/usr/share/fonts/truetype/arphic/uming.ttc"},
                    {"/usr/share/fonts/truetype/arphic/ukai.ttc"},
                };
                break;
        }
        for (String[] path : candidates) {
            File f = new File(path[0]);
            if (f.exists() && f.canRead()) {
                return f;
            }
        }
        return null;
    }

}
