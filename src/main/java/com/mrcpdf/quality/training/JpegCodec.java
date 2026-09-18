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
        PDImageXObject img = PDImageXObject.createFromByteArray(doc, encode(image, quality), "synthetic");
        cs.drawImage(img, (float) x, (float) y, (float) w, (float) h);
    }
}