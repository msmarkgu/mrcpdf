# `--mrc-only` Implementation Plan

## Goal
Allow a searchable PDF (from a previous TrulyFreeOCR run without MRC) to be re-compressed with MRC **without re-running OCR**.

## What changes and why

| Component | Change | Reason |
|-----------|--------|--------|
| **`MrcPdf.java`** | Add `--mrc-only` CLI flag | Entry point |
| **`MrcPdf.java:call()`** | `mrcOnly → useMrc=true`, skip OCR, warn if input is an image | Image inputs have no existing text to extract |
| **`MrcPdf.java:processPages()`** | When `mrcOnly && srcWords > 0`: replace `ocrProvider.ocr()` with existing text extraction from source PDF. Still run segmentation+JBIG2 for MRC. | Keep all MRC work, swap out OCR for extraction |
| **`MrcPdf.java` (new method)** | `extractExistingText(PDDocument, pageCount, dpi) → List<PageResult>` | Uses a custom `PDFTextStripper` subclass to read text + positions from the source PDF, groups chars into words, converts PDF coords → pixel coords via `*(dpi/72)` |
| **`PDFAssembler.java`** | `addPage()` and `addPageJbig2()`: replaced `new PDPage(cropBox)` with `new PDPage(new PDRectangle(pageW, pageH))` to keep user-space origin at `(0,0)`. | Source pages may have non-zero crop-box origins; the old code created output pages with shifted origins, misaligning text coordinates. |
| **`JBIG2Compressor.java`** | **No change** | MRC-only path unaffected |
| **`ImageSegmenter.java`** | **No change** | MRC-only path unaffected |

## Architecture detail for text extraction

The custom `PDFTextStripper` subclass overrides `processTextPosition(TextPosition)` to accumulate characters, group them into word-level `TextBlock` entries by detecting inter-character gaps >50% of average char width, and convert PDF user-space coordinates to pixel coordinates:

```
pixelX = pdfX * dpi / 72
pixelY = pdfY * dpi / 72
```

(`processTextPosition()` returns Y in top-origin — 0 = page top — so `pixelY` is directly pixels from the top of the image. The Y-flip to bottom-origin happens once in `addPage()` / `addPageJbig2()`.)

Confidence is set to 100.0 (trivially — the text is known-good from the previous run).

`PageResult.width/height` = rendered image dimensions = `cropBox * dpi / 72` (PDFRenderer renders the crop box area).

## Text/JSON output behavior
When `--mrc-only`, skip txt/json output (already written in the previous run — Q1 decision).

## Edge cases
- **Image input with `--mrc-only`**: warn "no existing text to extract" and proceed with OCR as usual (MRC is still applied)
- **Non-searchable PDF with `--mrc-only`** (`srcWords == 0`): warn and fall back to OCR
- **`--mrc-only` + `--ocr-engine`**: compatible (Q2 decision — extraction is engine-independent)

## Files touched

| File | Lines added/changed |
|------|-------------------|
| `src/main/java/com/mrcpdf/MrcPdf.java` | ~50 new lines + `--mrc-only` flag + `TextPositionCollector` inner class |
| `src/main/java/com/mrcpdf/pipeline/PDFAssembler.java` | 2 lines: `new PDPage(cropBox)` → `new PDPage(new PDRectangle(pageW, pageH))` in `addPage()` and `addPageJbig2()` |

## What this does NOT do
- Extract text from a raw scanned PDF (no text layer exists) — OCR still runs
- Change the PDF/A, threading, or other unrelated features
- Modify the assembly pipeline or the `PageResult` model

## Bugs found during implementation

Three bounding-box alignment bugs were fixed in `--mrc-only` + existing OCR paths:

### 1. Y-flip double inversion

`processTextPosition()` returns Y in **top-origin** (0 = page top; Y = page height − user-space Y). The original `toBlock()` was applying `pageHeightPts − maxY` to convert back to bottom-origin, but `addPage()` / `addPageJbig2()` also flip during placement (`pageH − (bbox.y + bbox.height) * scaleY`), causing a double-flip.

**Fix:** `toBlock()` stores `topY = min(cp.y − cp.height)` (top of glyph in top-origin points) and `py = topY * scale` (pixels from top). No Y-flip in `toBlock()`; the flip happens once in the PDF assembler.

### 2. Output page user-space origin offset

`new PDPage(cropBox)` creates a page whose media box uses the crop box's raw PDF coordinates (e.g. `[72, 72, 612, 792]` for a 540×720 crop box offset by 72pt). This shifts user-space origin to `(72,72)`, so text placed at crop-box-relative coordinates ends up offset by the origin.

**Fix:** Replaced `new PDPage(cropBox)` with `new PDPage(new PDRectangle(pageW, pageH))` in `addPage()` and `addPageJbig2()`. The output page always has a clean `[0, 0, width, height]` user space.

### 3. Double origin subtraction in `toBlock()`

`processTextPosition()` already returns X relative to the crop-box lower-left corner. The original `toBlock()` was subtracting `originX` (crop-box `llx`) and `cropTopOffset` again, producing negative pixel coordinates for any non-zero origin.

**Fix:** Removed `originX` / `cropTopOffset` parameters from `toBlock()`, `buildWordBlocks()`, and `buildResults()`. Pixel conversion is now simply `px = processTextPosition.X * dpi / 72` and `py = processTextPosition.Y * dpi / 72`.

### Files changed beyond the plan

| File | Change |
|------|--------|
| `src/main/java/com/mrcpdf/MrcPdf.java` | Removed `originX`/`cropTopOffset` from `TextPositionCollector.toBlock()`, `buildWordBlocks()`, `buildResults()`. Simplified pixel conversion. |
| `src/main/java/com/mrcpdf/pipeline/PDFAssembler.java` | `addPage()` and `addPageJbig2()`: replaced `new PDPage(cropBox)` with `new PDPage(new PDRectangle(pageW, pageH))`. |

### Verification

- `BboxComparator` shows ±0.0pt X/Y deltas between OCR and MRC outputs (only font metrics differ, producing narrower/taller text boxes).
- No "NO MATCH" entries when both outputs use the same page dimensions.
- Works correctly for both `cropBox == mediaBox` (standard case) and `cropBox != mediaBox` cases.

### Remaining known limitation

On a 10‑page Sherlock Holmes book PDF (7.3 MB, 4292 words), **99.2 % of words** (3208/3235 matched entries) show Δ=(+0.0, ±0.0) for X. The remaining 27 entries have large X deltas (100–300 pt) — these are **not coordinate bugs** but BboxComparator false matches in a **two‑column layout**. The same word (e.g. "was") appears in both left and right columns at the same Y, and the comparator pairs the wrong instances. The invisible text layer in the MRC output is correctly positioned for every character.

---

## Text-position extraction bugs fixed (2026-07-23)

Two additional bugs were found and fixed in `TextPositionCollector`:

### 4. Uniform character width distribution

`processTextPosition()` decomposed multi-character `TextPosition` strings into individual character entries using **uniform spacing** (`perCharW = totalW / ch.length()`). Actual glyph positions depend on font metrics (e.g., 'H' is wider than 'i'), so uniform distribution produces incorrect x-coordinates.

**Fix:** Keep each `TextPosition` as one atomic `CharPos` entry. Use the bounding box PDFBox provides (`text.getX()`, `text.getWidth()`) rather than guessing per-character widths. Changed `CharPos` from `char c` to `String text`; `toBlock()` appends `cp.text` instead of `cp.c`.

### 5. Multi-baseline line sorting

`buildWordBlocks()` sorted characters by `(y, x)`, but Tesseract assigns slightly different y values to characters on the same visual line (e.g., y=322.2 vs y=324.1 for "START OF THE PROJECT GUTENEBERG"). The sort interleaves characters from different y-bands, so characters wrap back to the left side of the line mid-sort, creating **negative gaps**. Negative gaps never trigger word breaks, causing entire lines to merge into single words like "SherlockHolmes,ProjectGutenberg".

**Fix:** Two-pass approach:
1. **Cluster into visual lines** — sort by y, then merge characters within `prev.height * 0.5f` of the current line anchor.
2. **Split into words** — within each line, sort by x and split on gap > `max(prev.height * 0.3f, 2.0f)`.

### Verification

- `./gradlew build` passes (18/18 tests).
- `--mrc-only` output text matches source at **99.98%** (23,835 / 23,840 chars; 5-char difference on a special `**k START...` formatting line with three baselines).
- "Holmes" on page 4: **H starts at x=273.1** in both source and output — search highlighting is correct.
- Before fix: searching "Holmes" highlighted on "you". After fix: highlights on "Holmes".

### Files changed

| File | Change |
|------|--------|
| `src/main/java/com/mrcpdf/MrcPdf.java` | `processTextPosition()`: removed per-char splitting; `CharPos`: `char c` → `String text`; `toBlock()`: `cp.c` → `cp.text`; `buildWordBlocks()`: two-pass visual-line clustering + gap-based word splitting. |
