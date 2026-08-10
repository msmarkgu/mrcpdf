package com.mrcpdf.pipeline;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

public final class ImageEncoder {

  private ImageEncoder() {}

  public static byte[] encodeBackground(
      BufferedImage image, float quality, boolean hasMask, double bgScale, float bgSmoothSigma)
      throws IOException {
    BufferedImage toEncode = image;

    if (hasMask && bgScale < 1.0) {
      int newW = Math.max(1, (int) Math.round(image.getWidth() * bgScale));
      int newH = Math.max(1, (int) Math.round(image.getHeight() * bgScale));
      BufferedImage scaled = new BufferedImage(newW, newH, BufferedImage.TYPE_3BYTE_BGR);
      Graphics2D g = scaled.createGraphics();
      g.setRenderingHint(
          RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
      g.drawImage(image, 0, 0, newW, newH, null);
      g.dispose();
      toEncode = scaled;
    }

    if (hasMask && bgSmoothSigma > 0f) {
      float effectiveSigma = bgSmoothSigma / (float) bgScale;
      toEncode = PDFAssembler.gaussianBlur(toEncode, effectiveSigma);
    }

    return encodeJpeg(toEncode, quality, 2, 2);
  }

  public static byte[] encodeForegroundColor(BufferedImage image, float quality, double fgScale)
      throws IOException {
    BufferedImage toEncode = image;
    if (fgScale < 1.0) {
      int newW = Math.max(1, (int) Math.round(image.getWidth() * fgScale));
      int newH = Math.max(1, (int) Math.round(image.getHeight() * fgScale));
      BufferedImage scaled = new BufferedImage(newW, newH, BufferedImage.TYPE_3BYTE_BGR);
      Graphics2D g = scaled.createGraphics();
      g.setRenderingHint(
          RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
      g.drawImage(image, 0, 0, newW, newH, null);
      g.dispose();
      toEncode = scaled;
    }
    return encodeJpeg(toEncode, quality, 1, 1);
  }

  public static byte[] encodePng(BufferedImage image) throws IOException {
    ByteArrayOutputStream baos = new ByteArrayOutputStream(8192);
    ImageIO.write(image, "png", baos);
    return baos.toByteArray();
  }

  static byte[] encodeJpeg(BufferedImage image, float quality, int hSample, int vSample)
      throws IOException {
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
                numComponents = 4;
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
      return baos.toByteArray();
    } finally {
      if (writer != null) writer.dispose();
    }
  }
}
