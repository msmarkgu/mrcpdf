package com.mrcpdf.model;

import java.awt.image.BufferedImage;
import java.util.List;

/**
 * Immutable result of page processing for a single page.
 *
 * pageNumber  0-based page index within the document.
 * width       Page image width  in pixels (from the rendered BufferedImage).
 * height      Page image height in pixels.
 * textBlocks  List of extracted words with bounding boxes and confidences.
 * bgJpeg      Pre-encoded JPEG bytes of the cleaned background layer (null when unused).
 * fgColorJpeg Pre-encoded JPEG bytes of the foreground color plane (null when unused).
 * mask        1-bit foreground mask image for JBIG2 compression.
 */
public class PageResult {

    private final int pageNumber;
    private final int width;
    private final int height;
    private final List<TextBlock> textBlocks;
    private final byte[] bgJpeg;
    private final byte[] fgColorJpeg;
    private final BufferedImage mask;

    public PageResult(int pageNumber, int width, int height, List<TextBlock> textBlocks) {
        this(pageNumber, width, height, textBlocks, null, null, null);
    }

    public PageResult(int pageNumber, int width, int height, List<TextBlock> textBlocks,
                      byte[] bgJpeg, byte[] fgColorJpeg, BufferedImage mask) {
        this.pageNumber = pageNumber;
        this.width = width;
        this.height = height;
        this.textBlocks = textBlocks;
        this.bgJpeg = bgJpeg;
        this.fgColorJpeg = fgColorJpeg;
        this.mask = mask;
    }

    public int getPageNumber() { return pageNumber; }
    public int getWidth() { return width; }
    public int getHeight() { return height; }
    public List<TextBlock> getTextBlocks() { return textBlocks; }
    public byte[] getBgJpeg() { return bgJpeg; }
    public byte[] getFgColorJpeg() { return fgColorJpeg; }
    public BufferedImage getMask() { return mask; }
}
