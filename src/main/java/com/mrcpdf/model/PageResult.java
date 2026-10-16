package com.mrcpdf.model;

import java.awt.image.BufferedImage;
import java.util.List;

import com.mrcpdf.quality.TextQualityScore;

/**
 * Immutable result of page processing for a single page.
 *
 * pageNumber  0-based page index within the document.
 * width       Page image width  in pixels (from the rendered BufferedImage).
 * height      Page image height in pixels.
 * textBlocks  List of recognised words with bounding boxes and confidences.
 * bgJpeg      Pre-encoded JPEG bytes of the cleaned background layer (null when unused).
 * fgColorJpeg Pre-encoded JPEG bytes of the foreground color plane (null when unused).
 * mask        1-bit foreground mask image for JBIG2 compression.
 * quality     Optional per-page text-quality score (null when not scored).
 * mrcSkip     True when the quality gate decided to skip MRC compression for
 *             this page so it keeps the fidelity-preserving full-res path.
 * passThrough True when the page is digital native and is carried into the
 *             output exactly as it came in -- no render, no OCR, no MRC.
 */
public class PageResult {

    private final int pageNumber;
    private final int width;
    private final int height;
    private final List<TextBlock> textBlocks;
    private final byte[] bgJpeg;
    private final byte[] fgColorJpeg;
    private final BufferedImage mask;
    private final TextQualityScore quality;
    private final boolean mrcSkip;
    private final boolean passThrough;

    public PageResult(int pageNumber, int width, int height, List<TextBlock> textBlocks) {
        this(pageNumber, width, height, textBlocks, null, null, null);
    }

    public PageResult(int pageNumber, int width, int height, List<TextBlock> textBlocks,
                      TextQualityScore quality, boolean mrcSkip) {
        this(pageNumber, width, height, textBlocks, null, null, null, quality, mrcSkip);
    }

    public PageResult(int pageNumber, int width, int height, List<TextBlock> textBlocks,
                      byte[] bgJpeg, byte[] fgColorJpeg, BufferedImage mask) {
        this(pageNumber, width, height, textBlocks, bgJpeg, fgColorJpeg, mask, null, false);
    }

    public PageResult(int pageNumber, int width, int height, List<TextBlock> textBlocks,
                      byte[] bgJpeg, byte[] fgColorJpeg, BufferedImage mask,
                      TextQualityScore quality, boolean mrcSkip) {
        this(pageNumber, width, height, textBlocks, bgJpeg, fgColorJpeg, mask, quality,
                mrcSkip, false);
    }

    public PageResult(int pageNumber, int width, int height, List<TextBlock> textBlocks,
                      byte[] bgJpeg, byte[] fgColorJpeg, BufferedImage mask,
                      TextQualityScore quality, boolean mrcSkip, boolean passThrough) {
        this.pageNumber = pageNumber;
        this.width = width;
        this.height = height;
        this.textBlocks = textBlocks;
        this.bgJpeg = bgJpeg;
        this.fgColorJpeg = fgColorJpeg;
        this.mask = mask;
        this.quality = quality;
        this.mrcSkip = mrcSkip;
        this.passThrough = passThrough;
    }

    public int getPageNumber() { return pageNumber; }
    public int getWidth() { return width; }
    public int getHeight() { return height; }
    public List<TextBlock> getTextBlocks() { return textBlocks; }
    public byte[] getBgJpeg() { return bgJpeg; }
    public byte[] getFgColorJpeg() { return fgColorJpeg; }
    public BufferedImage getMask() { return mask; }

    /** Per-page text-quality score, or null when quality gating is disabled. */
    public TextQualityScore getQuality() { return quality; }

    /** True when the quality gate routed this page to the full-res fidelity path. */
    public boolean isMrcSkip() { return mrcSkip; }

    /**
     * True when this page is digital native: it is copied into the output as it
     * came in, and was never rendered, scored or compressed.
     */
    public boolean isPassThrough() { return passThrough; }
}