package com.mrcpdf.pipeline;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.apache.fontbox.ttf.TrueTypeCollection;
import org.apache.fontbox.ttf.TrueTypeFont;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.PDDocumentCatalog;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDMetadata;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.color.PDOutputIntent;
import org.apache.pdfbox.pdmodel.graphics.image.CCITTFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdmodel.graphics.state.RenderingMode;
import org.apache.pdfbox.util.Matrix;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

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
    private String producer;
    private int skippedGlyphCount;
    private PDStream jbig2GlobalStream;
    private boolean foregroundColorEnabled;
    private double fgScale;
    private float fgJpegQuality;

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

    private PDImageXObject encodeBackgroundJpeg(PDDocument doc, BufferedImage image, float quality, boolean hasMask) throws IOException {
        BufferedImage toEncode = image;

        // Step 1: Downsample background (text sharpness preserved by mask).
        // Do this FIRST so subsequent Gaussian blur runs at reduced resolution (~9x fewer pixels).
        if (hasMask && backgroundScale < 1.0) {
            int newW = Math.max(1, (int) Math.round(image.getWidth() * backgroundScale));
            int newH = Math.max(1, (int) Math.round(image.getHeight() * backgroundScale));
            BufferedImage scaled = new BufferedImage(newW, newH, BufferedImage.TYPE_3BYTE_BGR);
            Graphics2D g = scaled.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(image, 0, 0, newW, newH, null);
            g.dispose();
            toEncode = scaled;
        }

        // Step 2: Pre-encode smoothing (reduces JPEG artifacts, improves compression)
        // Scale sigma inversely with backgroundScale so blur is proportional to original resolution.
        if (hasMask && bgSmoothSigma > 0f) {
            float effectiveSigma = bgSmoothSigma / (float) backgroundScale;
            toEncode = gaussianBlur(toEncode, effectiveSigma);
        }

        // Step 3: Encode as JPEG with 4:2:0 chroma subsampling + progressive
        // Using ImageWriter directly instead of JPEGFactory for chroma control
        return encodeJpeg(doc, toEncode, quality, 2, 2);
    }

    /**
     * Encodes the foreground color plane: a downsampled (fgScale), lossy copy of
     * the original page, at 4:4:4 chroma to keep colored text edges sharp. No
     * Gaussian smoothing is applied — this layer is clipped by the soft mask to
     * the exact text shapes, so pre-blur would soften the colors.
     */
    private PDImageXObject encodeForegroundColorJpeg(PDDocument doc, BufferedImage image) throws IOException {
        BufferedImage toEncode = image;
        if (fgScale < 1.0) {
            int newW = Math.max(1, (int) Math.round(image.getWidth() * fgScale));
            int newH = Math.max(1, (int) Math.round(image.getHeight() * fgScale));
            BufferedImage scaled = new BufferedImage(newW, newH, BufferedImage.TYPE_3BYTE_BGR);
            Graphics2D g = scaled.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(image, 0, 0, newW, newH, null);
            g.dispose();
            toEncode = scaled;
        }
        return encodeJpeg(doc, toEncode, fgJpegQuality, 1, 1);
    }

    /**
     * JPEG-encodes an image via a direct ImageWriter so chroma subsampling can
     * be controlled. {@code hSample}/{@code vSample} (1 or 2) set the luma
     * sampling factors; 2/2 gives 4:2:0 (background), 1/1 gives 4:4:4 (color
     * foreground). Component IDs are bounds-checked so a nonstandard writer tree
     * can't corrupt the encoding; on any unexpected metadata we fall back to the
     * writer's default subsampling.
     */
    private PDImageXObject encodeJpeg(PDDocument doc, BufferedImage image, float quality,
                                      int hSample, int vSample) throws IOException {
        ImageWriter writer = null;
        try {
            writer = ImageIO.getImageWritersByFormatName("JPEG").next();
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(quality);
            param.setProgressiveMode(ImageWriteParam.MODE_DEFAULT);

            ImageTypeSpecifier type = ImageTypeSpecifier.createFromRenderedImage(image);
            IIOMetadata meta = writer.getDefaultImageMetadata(type, param);
            if (meta != null && !meta.isReadOnly()) {
                try {
                    Element tree = (Element) meta.getAsTree("javax_imageio_jpeg_image_1.0");
                    NodeList markers = tree.getElementsByTagName("markerSequence");
                    if (markers.getLength() > 0) {
                        Element markerSeq = (Element) markers.item(0);
                        NodeList sofList = markerSeq.getElementsByTagName("sof");
                        if (sofList.getLength() > 0) {
                            Element sof = (Element) sofList.item(0);
                            int numComponents;
                            try {
                                numComponents = Integer.parseInt(sof.getAttribute("numFrameComponents"));
                            } catch (NumberFormatException e) {
                                numComponents = 4; // sane default; loop still bounds-checks IDs
                            }
                            Element marker = (Element) sof.getParentNode();
                            NodeList childNodes = marker.getChildNodes();
                            for (int ci = 0; ci < childNodes.getLength(); ci++) {
                                Node child = childNodes.item(ci);
                                if (child.getNodeType() != Node.ELEMENT_NODE) continue;
                                Element comp = (Element) child;
                                int compId;
                                try {
                                    compId = Integer.parseInt(comp.getAttribute("componentId"));
                                } catch (NumberFormatException e) {
                                    continue;
                                }
                                if (compId < 0 || compId >= numComponents) continue;
                                if (compId == 0) {
                                    comp.setAttribute("HsamplingFactor", Integer.toString(hSample));
                                    comp.setAttribute("VsamplingFactor", Integer.toString(vSample));
                                } else {
                                    comp.setAttribute("HsamplingFactor", "1");
                                    comp.setAttribute("VsamplingFactor", "1");
                                }
                            }
                        }
                    }
                    meta.setFromTree("javax_imageio_jpeg_image_1.0", tree);
                } catch (Exception e) {
                    // Unexpected metadata tree — keep the writer's default subsampling.
                }
            }

            ByteArrayOutputStream baos = new ByteArrayOutputStream(8192);
            try (MemoryCacheImageOutputStream mcios = new MemoryCacheImageOutputStream(baos)) {
                writer.setOutput(mcios);
                writer.write(null, new IIOImage(image, null, meta), param);
            }

            return PDImageXObject.createFromByteArray(doc, baos.toByteArray(), "image");
        } finally {
            if (writer != null) writer.dispose();
        }
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
        // Use crop box dimensions so the output page matches the visible area
        // that PDFRenderer.renderImageWithDPI() renders.  When the source crop
        // box differs from the media box this keeps text coordinates aligned.
        PDRectangle cropBox = sourcePage.getCropBox();
        float pageW = cropBox.getWidth();
        float pageH = cropBox.getHeight();
        // The background image is rendered in display orientation, which swaps
        // width and height for rotated (90/270) pages.  Match those display
        // dimensions so the background is drawn undistorted and the text-layer
        // scale factors stay consistent.  The output page keeps /Rotate 0
        // because the background is already pre-rotated.
        if (sourcePage.getRotation() % 180 != 0) {
            float tmp = pageW;
            pageW = pageH;
            pageH = tmp;
        }

        // Create page with [0,0,pageW,pageH] so user space origin aligns
        // with the crop-box-relative coordinates from text extraction.
        PDPage outPage = new PDPage(new PDRectangle(pageW, pageH));
        output.addPage(outPage);

        try (PDPageContentStream cs = new PDPageContentStream(output, outPage)) {
            // Layer 1: background image
            // When a foreground mask is present, the background can be lossy JPEG
            // because the foreground stencil preserves text pixels at full sharpness.
            // When no mask exists, the background is the only visual layer, so JPEG
            // at a moderate quality is used directly.
            boolean hasMask = foregroundMask != null;
            float bgQuality = hasMask ? bgJpegQuality : 0.85f;
            PDImageXObject bgXObject = encodeBackgroundJpeg(output, background, bgQuality, hasMask);
            cs.drawImage(bgXObject, 0, 0, pageW, pageH);

            // Layer 2: foreground mask as CCITT G4 stencil overlay (JBIG2 when available)
            // or, in foreground-color mode, as a soft mask (SMask) over a color plane.
            if (foregroundMask != null) {
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
                    // 1-bit DeviceGray mask: text samples decode to black (0), so
                    // Decode [1 0] inverts them to white = opaque alpha.
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

            // Layer 3: invisible OCR text
            PDFont pageFont = resolveFont(output);
            writeTextLayer(cs, ocr, pageW, pageH, pageFont);
        }

        return outPage;
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
            addPdfaMetadata(output);
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
            float naturalWidth;
            try {
                naturalWidth = pageFont.getStringWidth(safeWord) / 1000f * fontSize;
            } catch (IllegalArgumentException e) {
                naturalWidth = safeWord.length() * fontSize * 0.5f;
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
        for (int i = 0; i < word.length(); i++) {
            String ch = word.substring(i, i + 1);
            try {
                font.encode(ch);
                sb.append(ch);
            } catch (Exception e) {
                skippedGlyphCount++;
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
        // Explicit font path (set via pdf.fontPath or pdf.pdfa.fontPath)
        if (fontFile != null && fontFile.exists()) {
            if (dynamicFont == null) {
                dynamicFont = loadFont(output, fontFile);
            }
            if (dynamicFont != null) return dynamicFont;
        }
        return font;
    }

    /**
     * Loads a TTF/OTF/TTC font file as a PDType0Font.
     * Handles TrueType Collection (.ttc) files by loading the first font.
     */
    private static PDFont loadFont(PDDocument output, File file) {
        try {
            String name = file.getName().toLowerCase();
            if (name.endsWith(".ttc")) {
                try (TrueTypeCollection ttc = new TrueTypeCollection(file)) {
                    // Get the first font in the collection
                    final TrueTypeFont[] firstFont = new TrueTypeFont[1];
                    ttc.processAllFonts(font -> {
                        if (firstFont[0] == null) firstFont[0] = font;
                    });
                    if (firstFont[0] != null) {
                        return PDType0Font.load(output, firstFont[0], true);
                    }
                }
            } else {
                return PDType0Font.load(output, file);
            }
        } catch (IOException e) {
            System.out.printf("  Warning: could not load font '%s' — may be OTF/CFF (unsupported). Using built-in Helvetica instead.%n", file.getName());
        }
        return null;
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

    private void addPdfaMetadata(PDDocument doc) throws IOException {
        PDDocumentCatalog catalog = doc.getDocumentCatalog();

        // Merge PDF/A identification into existing XMP metadata,
        // preserving source document metadata (author, title, etc.).
        PDMetadata existingMeta = catalog.getMetadata();
        if (existingMeta != null) {
            try {
                byte[] existingBytes;
                try (InputStream is = existingMeta.createInputStream()) {
                    existingBytes = is.readAllBytes();
                }
                String xmpStr = new String(existingBytes, StandardCharsets.UTF_8);

                int xmpStart = xmpStr.indexOf("<x:xmpmeta");
                int xmpEnd = xmpStr.lastIndexOf("</x:xmpmeta>");
                if (xmpStart >= 0 && xmpEnd > xmpStart) {
                    String rawXml = xmpStr.substring(xmpStart, xmpEnd + "</x:xmpmeta>".length());

                    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
                    factory.setNamespaceAware(true);
                    DocumentBuilder builder = factory.newDocumentBuilder();
                    Document xmlDoc = builder.parse(
                            new ByteArrayInputStream(rawXml.getBytes(StandardCharsets.UTF_8)));

                    NodeList descList = xmlDoc.getElementsByTagNameNS(
                            "http://www.w3.org/1999/02/22-rdf-syntax-ns#", "Description");
                    if (descList.getLength() > 0) {
                        Element desc = (Element) descList.item(0);
                        desc.setAttributeNS("http://www.w3.org/2000/xmlns/",
                                "xmlns:pdfaid", "http://www.aiim.org/pdfa/ns/id/");

                        NodeList partList = xmlDoc.getElementsByTagNameNS(
                                "http://www.aiim.org/pdfa/ns/id/", "part");
                        if (partList.getLength() == 0) {
                            Element partEl = xmlDoc.createElementNS(
                                    "http://www.aiim.org/pdfa/ns/id/", "pdfaid:part");
                            partEl.setTextContent("2");
                            desc.appendChild(partEl);
                        }

                        NodeList confList = xmlDoc.getElementsByTagNameNS(
                                "http://www.aiim.org/pdfa/ns/id/", "conformance");
                        if (confList.getLength() == 0) {
                            Element confEl = xmlDoc.createElementNS(
                                    "http://www.aiim.org/pdfa/ns/id/", "pdfaid:conformance");
                            confEl.setTextContent("B");
                            desc.appendChild(confEl);
                        }
                    }

                    TransformerFactory tf = TransformerFactory.newInstance();
                    Transformer transformer = tf.newTransformer();
                    transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
                    transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
                    StringWriter sw = new StringWriter();
                    transformer.transform(new DOMSource(xmlDoc), new StreamResult(sw));
                    String modifiedXml = sw.toString();

                    String wrapped = "<?xpacket begin=\"\uFEFF\" id=\"W5M0MpCehiHzreSzNTczkc9d\"?>\n"
                            + modifiedXml + "\n<?xpacket end=\"w\"?>";

                    PDMetadata metadata = new PDMetadata(doc);
                    metadata.importXMPMetadata(wrapped.getBytes(StandardCharsets.UTF_8));
                    catalog.setMetadata(metadata);
                }
            } catch (Exception e) {
                setStaticPdfaMetadata(catalog, doc);
            }
        } else {
            setStaticPdfaMetadata(catalog, doc);
        }

        // sRGB output intent
        try (InputStream srgbStream = getClass().getResourceAsStream("/sRGB Color Space Profile.icm")) {
            if (srgbStream != null) {
                PDOutputIntent intent = new PDOutputIntent(doc, srgbStream);
                intent.setInfo("sRGB IEC61966-2.1");
                intent.setOutputCondition("sRGB IEC61966-2.1");
                intent.setOutputConditionIdentifier("sRGB IEC61966-2.1");
                intent.setRegistryName("http://www.color.org");
                catalog.addOutputIntent(intent);
            }
        }
    }

    private void setStaticPdfaMetadata(PDDocumentCatalog catalog, PDDocument doc) throws IOException {
        String xmp = """
                <?xpacket begin="\\uFEFF" id="W5M0MpCehiHzreSzNTczkc9d"?>
                <x:xmpmeta xmlns:x="adobe:ns:meta/">
                  <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
                    <rdf:Description rdf:about=""
                      xmlns:pdfaid="http://www.aiim.org/pdfa/ns/id/">
                      <pdfaid:part>2</pdfaid:part>
                      <pdfaid:conformance>B</pdfaid:conformance>
                    </rdf:Description>
                    <rdf:Description rdf:about=""
                      xmlns:dc="http://purl.org/dc/elements/1.1/">
                      <dc:format>application/pdf</dc:format>
                    </rdf:Description>
                  </rdf:RDF>
                </x:xmpmeta>
                <?xpacket end="w"?>""".stripIndent();
        PDMetadata metadata = new PDMetadata(doc);
        metadata.importXMPMetadata(xmp.getBytes(StandardCharsets.UTF_8));
        catalog.setMetadata(metadata);
    }

}
