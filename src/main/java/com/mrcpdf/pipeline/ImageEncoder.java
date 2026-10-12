package com.mrcpdf.pipeline;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.awt.image.WritableRaster;
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
import org.w3c.dom.NodeList;

public final class ImageEncoder {

  /**
   * How far the red, green and blue channels of one pixel may drift apart and
   * still count as neutral. A scan's white point leaves a channel or two of
   * noise behind; a real tint is far wider than this.
   */
  private static final int GRAY_TOLERANCE = 8;

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
      // Sigma is configured in original-resolution pixels, but the blur runs on
      // the downscaled image: one source pixel covers 1/bgScale destination
      // pixels, so rescale by multiplying.  Dividing (the previous code) scaled
      // sigma by ~1/bgScale^2 — a ~9x over-blur at the default 0.33 scale — so
      // the "mild smoothing" knob was applying a heavy soften.  Only rescale
      // when the downscale actually happened; without one the sigma is already
      // in the target domain.
      float sigma = bgScale < 1.0 ? bgSmoothSigma * (float) bgScale : bgSmoothSigma;
      toEncode = PDFAssembler.gaussianBlur(toEncode, sigma);
    }

    // A grayscale page -- a black-and-white scan rendered to RGB, the common
    // case -- costs a third of the bytes as a 1-component JPEG, and the two
    // dropped chroma planes carry no information.  Detect on the image that is
    // actually encoded, after any downscale and blur.
    BufferedImage gray = toGrayscaleOrNull(toEncode);
    if (gray != null) {
      return encodeJpeg(gray, quality, 1, 1);
    }
    return encodeJpeg(toEncode, quality, 2, 2);
  }

  /**
   * A 1-band copy of {@code image} when every pixel is neutral, else null.
   *
   * <p>Detection and conversion run in one pass, and a single colored pixel
   * abandons the copy immediately, so a color page pays almost nothing and a
   * gray page is touched once.  The sample written is the channel average (the
   * channels already agree within {@link #GRAY_TOLERANCE}), which is what a
   * viewer displays for a DeviceGray image -- converting through Java2D's
   * linear-gray color model would instead store a gamma-shifted value.
   */
  static BufferedImage toGrayscaleOrNull(BufferedImage image) {
    int w = image.getWidth();
    int h = image.getHeight();
    BufferedImage gray = new BufferedImage(w, h, BufferedImage.TYPE_BYTE_GRAY);
    WritableRaster raster = gray.getRaster();
    for (int y = 0; y < h; y++) {
      for (int x = 0; x < w; x++) {
        int rgb = image.getRGB(x, y);
        int r = (rgb >> 16) & 0xFF;
        int g = (rgb >> 8) & 0xFF;
        int b = rgb & 0xFF;
        if (Math.abs(r - g) > GRAY_TOLERANCE || Math.abs(g - b) > GRAY_TOLERANCE) {
          return null;
        }
        raster.setSample(x, y, 0, (r + g + b) / 3);
      }
    }
    return gray;
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
              // The SOF element lists one <componentSpec> per JPEG component,
              // positionally (index 0 = luma).  Re-traversal by position rather
              // than by a "componentId" attribute: the old code iterated the
              // marker sequence's children (dqt/dht/sof/sos), every iteration
              // threw on the missing attribute and was swallowed, so the
              // requested subsampling was never actually applied.
              NodeList comps = sof.getElementsByTagName("componentSpec");
              for (int ci = 0; ci < comps.getLength(); ci++) {
                Element comp = (Element) comps.item(ci);
                // A single-component (grayscale) JPEG has no chroma to
                // subsample against, so its lone luma plane is always 1x1;
                // writing 2x2 there produces a stream readers disagree on.
                if (ci == 0 && comps.getLength() >= 2) {
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
