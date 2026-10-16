package com.mrcpdf.quality.training;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;

/**
 * JPEG embedding that matches the production chain ({@code ImageEncoder} +
 * {@code PDImageXObject.createFromByteArray}) instead of
 * {@code JPEGFactory}, which darkens {@code TYPE_BYTE_GRAY} sources (a known
 * PDFBox quirk: a 200-gray constant embeds and re-renders as ~147).  The
 * synthetic corpus must survive the same raster -> JPEG -> PDF -> render
 * round-trip as real scanned fixtures, so it uses the same codec here.
 */
final class JpegCodec {

    private JpegCodec() {
    }

    static byte[] encode(BufferedImage image, float quality) throws IOException {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("JPEG").next();
        try {
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(quality);
            param.setProgressiveMode(ImageWriteParam.MODE_DEFAULT);
            ByteArrayOutputStream baos = new ByteArrayOutputStream(8192);
            try (MemoryCacheImageOutputStream mcios = new MemoryCacheImageOutputStream(baos)) {
                writer.setOutput(mcios);
                writer.write(null, new IIOImage(image, null, null), param);
            }
            return baos.toByteArray();
        } finally {
            writer.dispose();
        }
    }

    static void embed(PDDocument doc, PDPageContentStream cs, BufferedImage image,
                      float quality, double x, double y, double w, double h) throws IOException {
        embedBytes(doc, cs, encode(image, quality), x, y, w, h);
    }

    /**
     * Encodes once and exposes both the exact bytes to embed and the pixels a
     * renderer will draw from them.
     *
     * <p>Encoding is lossy, so the delivered page is the <em>decoded</em> stream,
     * not the raster handed to the encoder. Anything describing the delivered page
     * (label, audit, features) must therefore read {@link #decoded()}, while
     * {@link #jpeg()} is what goes into the PDF. Round-tripping first and encoding
     * again would score a different image from the one shipped, applying JPEG
     * twice; keeping the two in one record makes that mismatch unrepresentable.
     */
    static FinalImage prepare(BufferedImage image, float quality) throws IOException {
        byte[] jpeg = encode(image, quality);
        try (java.io.ByteArrayInputStream in = new java.io.ByteArrayInputStream(jpeg)) {
            BufferedImage decoded = javax.imageio.ImageIO.read(in);
            if (decoded == null) {
                throw new IOException("JPEG round-trip produced no image ("
                        + jpeg.length + " bytes)");
            }
            return new FinalImage(jpeg, decoded);
        }
    }

    /** The JPEG stream to embed together with the pixels it decodes to. */
    record FinalImage(byte[] jpeg, BufferedImage decoded) {
    }

    static void embedBytes(PDDocument doc, PDPageContentStream cs, byte[] jpeg,
                           double x, double y, double w, double h) throws IOException {
        PDImageXObject img = PDImageXObject.createFromByteArray(doc, jpeg, "page");
        cs.drawImage(img, (float) x, (float) y, (float) w, (float) h);
    }
}
