# MRC Compression, Explained

This note explains how **[Mixed Raster Content (MRC)](https://en.wikipedia.org/wiki/Mixed_raster_content)** compression works, in plain
enough terms for a developer working on the codebase or integrating it. It maps
each concept to the actual mrcpdf implementation so you can jump from "what's
happening" to "where the code is".

---

## 1. The problem

A scanned page or a raster PDF is, to a computer, just a big rectangular grid of
colored pixels.

- At a useful resolution (300–600 DPI) a single page is **8–34 million pixels**.
- It is mostly **uniform background** (blank paper, margins, empty space).
- A small fraction of it is **text** — thin, high-contrast strokes.
- And there's often not a lot of *smooth variation* elsewhere (maybe a photo or a
  logo).

Two naive choices are both bad:

| Approach | Problem |
|----------|---------|
| Store the whole page as one high-quality JPEG | Huge. JPEG is lossy and **smears sharp edges** ("ringing" / halos around text), so text looks blurry. |
| Store the whole page as one 1-bit black-and-white image | Destroys all color and halftone detail (photos, highlights). |

MRC's insight: **don't treat the page as one image.** Break it into layers, pick
the best encoder for each layer, and stack the layers back together in the PDF.

---

## 2. The core idea: "Mixed Raster Content" (ITU-T T.44)

An MRC page is decomposed into up to three visual layers plus a text layer:

```
                ┌─────────────────────────────┐
                │ 4. Text layer (invisible,   │  ← selectable/searchable
                │    RenderingMode.NEITHER)   │
                ├─────────────────────────────┤
                │ 3. Foreground COLOR plane   │  ← optional (--fg-color),
                │    (downsampled original)   │    via a soft mask (SMask)
                ├─────────────────────────────┤
                │ 2. Foreground MASK (1 bit)  │  ← JBIG2 / CCITT G4
                │    black = text pixels      │    drawn as a stencil
                ├─────────────────────────────┤
                │ 1. Background (color,       │  ← JPEG (downscaled, blured,
                │    text erased)             │    low quality)
                └─────────────────────────────┘
```

Reader renders layers **bottom-to-top**; each upper layer only "paints" where it
has ink, so the composite looks like the original page but each layer was
compressed with the best tool available.

**Goal:** put smooth colored content in the *background*, put crisp 1-bit text in
the *foreground mask*, and let the PDF combine them so the file is small *and*
the text is razor-sharp.

---

## 3. Segmentation — the interesting algorithm

`ImageSegmenter.java` turns one full-color page into (a) a binary foreground
mask and (b) a cleaned background. This is where most of the real work lives.

```
            ARGB page
               │  getRGB() into a flat int[]
               ▼
          grayscale (BT.601 luma)                 toGrayscale()
               │
               ▼
   background normalization (correct lighting)    backgroundNormalize()
               │   per-tile 95th-pct background ~ bilinear surface
               ▼
            threshold                sauvolaThreshold()  (default, local)
            (text/background split)  otsuThreshold()     (global, optional)
               │
               ▼
   connected-component filter                     filterLargeComponents()
               │   drop big dark regions (photos/logos) from the mask
               ▼
            build binary mask                         → foregroundMask
               │
               ▼
       inpaint text out of the color image          inpaintBackground()
               ▼
          cleaned background                       → background
```

Steps in detail:

1. **Grayscale** — convert ARGB → one gray byte per pixel using ITU-R BT.601
   luma (`0.299R + 0.587G + 0.114B`), so later math is on one channel.
2. **Background normalization** — corrects uneven illumination (shadows,
   scanner vignetting, gradients) that would otherwise fool a global threshold.
   The page is divided into a grid of tiles; each tile's *background level* is
   estimated (the 95th percentile — bright, since text is dark); a smooth
   background surface is built by bilinear interpolation across the tile grid;
   then each pixel is stretched: `new = old * 255 / bgEstimate`. This "flattens"
   the lighting so a threshold works everywhere.
3. **Thresholding** — classify each pixel as *foreground* (dark = text) or
   *background* (bright). Two modes:
   - **Sauvola (default)** — a *local* per-pixel threshold computed from the mean
     and standard deviation of a small window around each pixel:
     `threshold = mean * (1 + k * (stddev / R - 1))`. It keeps faint text in
     uneven light. Uses **integral images** (sum + sum-of-squares) so any window
     size is O(width × height) total.
   - **Otsu** — one *global* threshold that maximizes inter-class variance. Fast,
     good only for clean, uniformly lit pages.
4. **Connected-component filter** — dark regions larger than a fraction of the
   page area/dimension are **photos/logos**, not text, so they're pulled *out* of
   the mask and left in the color background. Uses a single-pass union-find
   labeling.
5. **Build the mask** — a 1-bit `TYPE_BYTE_BINARY` image: black where foreground,
   white everywhere else. This becomes the JBIG2-encoded stencil.
6. **Inpaint** — erase text from the *color* background image by propagating
   surrounding background colors inward (a two-pass Manhattan distance transform).
   This matters: the background gets JPEG'd at low quality, and JPEG smears hard
   edges — if text strokes remained, you'd get ugly "ringing" halos around every
   character. Removing them keeps the background clean, and the sharp text lives
   only in the lossless JBIG2 mask.

---

## 4. Encoding each layer

### Background → JPEG

The cleaned color background is:
- **downscaled ~3×** (e.g. 300 DPI → ~100 DPI, `pipeline.mrc.backgroundScale`),
- **mildly blurred** (`pipeline.mrc.bgSmoothSigma`) to suppress JPEG artifacts,
- **JPEG-compressed** at quality ~0.50 with 4:2:0 chroma subsampling.

It doesn't need to be sharp because it carries no text — the mask supplies that.
So it can be honestly, heavily compressed, and that's most of the file size.

### Layer | Foreground mask → JBIG2 (or CCITT)

The 1-bit mask is burst into JBIG2 by the bundled `jbig2enc`. JBIG2's trick is a
**symbol dictionary**:

- **Symbol modeling** — the encoder extracts each connected black component (a
  character, or pieces of merged characters) and clusters them into a
  **symbol dictionary** of distinct glyph shapes. A page with thousands of
  characters typically reduces to a few dozen unique symbols.
- **Symbol matching** — each region is then encoded as a **reference to the
  closest symbol** (by ID + position offset), a **refinement** (deltas from a
  similar symbol), or raw pattern bits when no dictionary symbol is good enough.
- **Adaptive arithmetic coding** — the symbol IDs, offsets, and refinement bits
  are entropy-coded with a context-adaptive arithmetic coder that learns local
  probability models, squeezing near the theoretical limit.
- **Lossiness** — near-identical symbols are treated as identical, so the output
  is *visually* lossless but not bit-identical: slightly different instantiations
  of a glyph may be swapped for one canonical shape.

mrcpdf goes further: one `jbig2enc` invocation over **all pages** (`-p -s` in
`jbig2enc.flags`) builds a single **global symbol dictionary shared across every
page**. Forms and reports that repeat the same glyphs shrink dramatically, since
each symbol is stored only once.

If `jbig2enc` isn't available or fails, we fall back to **CCITT G4** (fax): same
1-bit idea but no symbol dictionary — works everywhere, larger output.

### Foreground color (opt-in `--fg-color`)

By default the mask is drawn as a *black* stencil (small, PDF/A-safe). With
`--fg-color`, the mask instead becomes a **soft mask (SMask)** over a
downsampled, low-quality JPEG copy of the original page, so the text pixels keep
their **original color** and edges stay sharp. Note: soft masks are **not
allowed in PDF/A-2b**, so this is disabled when `--pdfa` is set.

### Text layer

mrcpdf re-positions **the source PDF's existing text** word by word at its
original coordinates, drawn with `RenderingMode.NEITHER` (`Tr 3` — PDF text
rendering mode **3**, *neither fill nor stroke*): the glyphs are invisible on
screen and print but still occupy their layout space, so the text stays
**selectable and searchable**. There is no OCR — if the source has no text
(e.g. a raw scan), pages are still MRC-compressed but carry no invisible text
layer.

---

## 5. Reassembly: stacking layers in the PDF

`PDFAssembler` rebuilds each page:

1. Draw the **background** JPEG image.
2. Draw the **foreground mask** on top as a PDF `IMAGE_MASK` stencil, painted
   black (covers only the text pixels).
3. If `--fg-color`: instead draw the downsampled color plane masked by the
   foreground as an `/SMask`.
4. Draw the invisible text layer on top.

Two coordinate details worth knowing (`PDFAssembler` header):

- The images are at the render **DPI** (e.g. 300), but PDF **user space** is
  72 pt; the page image is scaled to cover exactly the page's media box:
  `scaleX = pageWidth_pts / imageWidth_px`.
- `Y` is **flipped**: image origin is top-left, PDF origin is bottom-left.

---

## 6. Why the file is so small

Per page, roughly:

| Layer | Size driver | Result |
|-------|-------------|--------|
| Background | downscaled + smoothed JPEG | the *only* big thing, still small |
| Foreground mask | JBIG2 symbol dict, right text | tiny |
| Foreground color (opt) | downscaled JPEG | small |
| Text layer | a few vectors | negligible |

A 300-DPI scan that is raw is many megabytes per page. Break it into a smooth
~100-DPI JPEG background plus a near-invisible JBIG2 stencil and the text/out
per page often lands in the **hundreds of kilobytes** with *no* visible quality
loss and fully selectable text.

---

## 7. Trade-offs & gotchas

- The mask is **1 bit**, so text sharpness is capped by thresholding quality.
  Sauvola keeps faint strokes; a bad threshold makes thin text break or fill in.
- If there's **no mask** (MRC off), the background JPEG must be higher quality
  (`~0.85` instead of `0.50`), because it has to carry the text itself.
- **No OCR**: searchable text comes only from the source PDF.
- `--fg-color` and `--pdfa` are mutually exclusive (PDF/A forbids soft masks);
  `--pdfa` silently disables the color layer.
- **Memory**: high DPI means big working arrays in segmentation (`W × H` for
  each of ~10 intermediate planes). See README "High DPI & memory" — 600 DPI
  needs a bigger `-Xmx` and/or fewer `--threads`.

---

## 8. Concept → code map

| Concept | Code |
|---------|------|
| MRC on/off, background scale, foreground color | `settings.jsonc` `pipeline.mrc.*` |
| Render page to BufferedImage at N DPI | `PageExtractor.renderPage()` / `PDFRenderer.renderImageWithDPI()` in `MrcPdf` |
| Grayscale / background normalize / threshold / CC filter / inpaint | `pipeline/ImageSegmenter.java` |
| Foreground mask + cleaned background | `model/SegmentedImage.java` |
| JBIG2 via `jbig2enc` (global dictionary) | `pipeline/JBIG2Compressor.java` |
| Reassemble layers + invisible text + SMask | `pipeline/PDFAssembler.java` |
| CLI: `--dpi`, `--bg-scale`, `--fg-color`, `--threads` | `MrcPdf.java` |

---

*Companion docs: [`when-not-to-mrc.md`](when-not-to-mrc.md); further notes in
`../notes/mrc-improvements.md` and `../notes/misalignment-issue.md`.*