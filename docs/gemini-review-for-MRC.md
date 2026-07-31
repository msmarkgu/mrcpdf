# Critical Review: MRC Compression Implementation (`PDFAssembler.java`)

After a thorough review of the Mixed Raster Content (MRC) implementation in `PDFAssembler.java`, several critical flaws, performance bottlenecks, and missed optimizations have been identified. While the high-level architecture (background layer + stencil mask + invisible text) is conceptually sound for MRC, the execution has significant issues.

## 1. JBIG2 Global Dictionary Duplication (Fatal Flaw for Compression)
In `addPageJbig2`, the implementation manually concatenates the `jbig2GlobalSym` byte array to the `jbig2PageData` for **every single page**:
```java
byte[] combined = new byte[jbig2GlobalSym.length + jbig2PageData.length];
System.arraycopy(jbig2GlobalSym, 0, combined, 0, jbig2GlobalSym.length);
System.arraycopy(jbig2PageData, 0, combined, jbig2GlobalSym.length, jbig2PageData.length);
```
* **The Issue:** The entire premise of JBIG2 for multi-page MRC is cross-page symbol reuse (the global dictionary). By embedding the global dictionary into every individual page's stream, this code completely negates the file size benefits of JBIG2. A 100-page PDF will store the global dictionary 100 times.
* **The Solution:** The comment mentions this is a hack "to avoid poppler JBIG2 decoder issues." However, PDFBox natively supports global dictionaries. The global stream should be written as an independent `PDStream` and referenced via the `/JBIG2Globals` key inside the `/DecodeParms` dictionary of each page's Image XObject.

## 2. Resource Leaks and Missing Chroma Subsampling in JPEG Encoding
In `encodeBackgroundJpeg`:
```java
ByteArrayOutputStream baos = new ByteArrayOutputStream(8192);
writer.setOutput(new MemoryCacheImageOutputStream(baos));
writer.write(null, new IIOImage(toEncode, null, null), param);
return PDImageXObject.createFromByteArray(doc, baos.toByteArray(), "background");
```
* **The Issue (Resource Leak/Corruption):** `MemoryCacheImageOutputStream` uses internal buffers and temporary files. It is never explicitly `close()`d or `flush()`ed before `baos.toByteArray()` is called. This can result in truncated JPEG files or memory leaks, as the final bytes may still reside in the stream's buffer. It must be closed in a `try-with-resources` block.
* **The Issue (False Advertising):** The comment claims to "Encode as JPEG with 4:2:0 chroma subsampling". However, standard Java `ImageWriter` configured with `MODE_EXPLICIT` only sets quality and progressive modes. It does not automatically downsample chroma channels to 4:2:0 unless explicit `IIOMetadata` manipulation is applied. The background layer will likely remain 4:4:4, wasting space.

## 3. Severe Memory Inefficiency in Gaussian Blur
The `gaussianBlur` method uses a separable blur, which is good, but its vertical pass implementation is highly destructive to the heap:
```java
int[] pixels = temp.getRGB(0, 0, w, h, null, 0, w);
int[] outPixels = new int[w * h];
```
* **The Issue:** For a standard 300 DPI page (e.g., 2500x3300), extracting the entire image into a 1D `int[]` array allocates ~33MB of continuous heap space per array (66MB total just for the arrays). Running this concurrently across multiple threads will cause massive GC spikes and likely trigger `OutOfMemoryError`s.
* **The Solution:** The vertical pass should be processed row-by-row or block-by-block, similar to how the horizontal pass uses `srcRow` and `dstRow`, rather than slurping the entire bitmap into RAM.

## 4. OOM Risk in Pipeline Architecture (`assemble` API)
The primary `assemble` method has the following signature:
```java
public PDDocument assemble(File sourcePdf,
                           List<BufferedImage> backgrounds,
                           List<BufferedImage> foregroundMasks,
                           List<PageResult> ocrResults,
                           boolean usePdfa)
```
* **The Issue:** Accepting `List<BufferedImage>` for backgrounds and foregrounds requires holding all uncompressed bitmaps for the entire document in memory simultaneously. For a 50-page document, this translates to hundreds of megabytes or gigabytes of RAM. 
* **The Solution:** A true streaming architecture should accept an `Iterator` or `Stream` of pages, processing, assembling, and discarding `BufferedImage` instances one by one.

## 5. Hardcoded Text Baseline Heuristic
In the invisible text layer generation:
```java
// Approximate baseline: ~80% down from top of bbox
float y = pageH - (tb.getBbox().y + tb.getBbox().height * 0.8f) * scaleY;
```
* **The Issue:** The 80% baseline estimation is a brittle heuristic. Depending on the mix of ascenders and descenders (especially with mixed Latin and CJK text), the invisible text will be misaligned vertically compared to the visual pixels. This results in janky, jumping highlight boxes when a user selects text in a PDF viewer.
* **The Solution:** If the OCR engine provides exact baselines, they should be used. Otherwise, bounding box bottoms should be aligned, or exact font metrics should be queried from the `PDFont` rather than blindly using 80%.

---

# Testing Plan: MRC Implementation Correctness

## Overview

The existing test suite covers unit-level MRC behavior (mask generation, compressor invocation, JBIG2 COS structure) but has significant gaps in **functional validation** (does the output PDF actually look correct? Is the compression effective?). Below are 7 new tests that close those gaps.

All tests are deterministic (no OCR variability — they pass synthetic `BufferedImage` and `TextBlock` objects directly to `PDFAssembler`), except for #7 which runs the full pipeline on real-world PDFs.

---

## Test 1 — Background Uses JPEG Encoding (not FlateDecode)

**File**: `PDFAssemblerRegressionTest`

**What**: When a foreground mask is present, the background image stream must be JPEG-encoded (filter `DCTDecode`), not losslessly compressed (filter `FlateDecode`). Lossy JPEG on the background is the core MRC space-saving technique.

**How**: Assemble a page with a foreground mask. Iterate XObjects — the one without `ImageMask=true` is the background. Assert its filter is `COSName.DCT_DECODE`.

**Method**: `mrcBackground_usesJpegEncoding()`

---

## Test 2 — Background Image Is Downscaled

**File**: `PDFAssemblerRegressionTest`

**What**: When `backgroundScale < 1.0` is set, the background image written into the PDF must have proportionally smaller pixel dimensions.

**How**: Set `backgroundScale=0.5`. Pass a 400x600 `BufferedImage` as the background. Find the background XObject in the output — assert `getWidth() == 200` and `getHeight() == 300`.

**Method**: `mrcBackground_isDownscaled()`

---

## Test 3 — Chroma Subsampling Is Actually 4:2:0

**File**: `PDFAssemblerRegressionTest`

**What**: The `encodeBackgroundJpeg` method claims 4:2:0 chroma subsampling. This test proves it by re-reading the JPEG metadata from the encoded stream.

**How**: Extract the raw JPEG bytes from the background stream. Use `javax.imageio.ImageReader` with `javax_imageio_jpeg_image_1.0` metadata format. Verify that the chroma components (componentId 1 and 2) have `HsamplingFactor=1` and `VsamplingFactor=1`, while the luma component (componentId 0) has `HsamplingFactor=2` and `VsamplingFactor=2`.

**Method**: `mrcBackground_chromaIs420()`

---

## Test 4 — MRC Reduces File Size vs Non-MRC

**File**: `PDFAssemblerRegressionTest`

**What**: For the same input, MRC output (with foreground mask) must be significantly smaller than non-MRC output (without mask). This validates the entire MRC value proposition.

**How**: Assemble the same source twice — once with a foreground mask (`hasMask=true`, `backgroundScale=0.33`) and once without (`foregroundMasks=null`). Save both to `tempDir`. Assert that the MRC file is less than 50% the size of the non-MRC file.

**Method**: `mrcCompression_reducesFileSize()`

---

## Test 5 — Rendered Pixel Correctness: Text Sharpness + Background Smoothness

**File**: `PDFAssemblerRegressionTest`

**What**: When rendered, the output PDF must have:
1. **Sharp text** at known coordinates (the foreground mask overlay reproduces text pixels at full sharpness).
2. **Smooth background** away from text (JPEG compression + Gaussian blur removes noise).

**How**: Create a 400x600 `BufferedImage` with a black text rectangle at a known position. Create a matching binary mask. Assemble with `backgroundScale=0.33` and `bgSmoothSigma=3.0`. Render the output page via `PDFRenderer.renderImageWithDPI(0, 72)`.

- At the text rectangle coordinates, sample the red channel — assert it is dark (< 32). The mask stencil renders sharp black regardless of the blurry background.
- At a background-only region far from text, compute the standard deviation of a 5x5 pixel neighborhood — assert it is low (< 10), proving the background was smoothed.

**Method**: `mrcForegroundMask_preservesTextSharpness()`

---

## Test 6 — JBIG2 Compresses Better Than CCITT G4

**File**: `PDFAssemblerRegressionTest`

**What**: JBIG2 with shared global dictionary should produce smaller output than CCITT G4 for multi-page documents, because symbol definitions are stored once and reused across pages.

**How**: Create a 2-page document where both pages have the same text (so JBIG2's symbol table is reused). Assemble twice:
1. With `compressor` set (JBIG2 path, assuming `jbig2enc` is available)
2. Without `compressor` (CCITT G4 fallback)

Save both to `tempDir`. Assert JBIG2 output is smaller than CCITT output. Guard with `assumeTrue(compressorAvailable)`.

**Method**: `jbig2_compressesBetterThanCcitt()`

---

## Test 7 — Real-World PDF Stress Test

**File**: `PipelineIntegrationTest`

**What**: The MRC pipeline must handle real-world documents without crashing and produce sensible output.

**How**: Iterate over all PDFs in `tests/test-files/real-world/` (9 files, 10–100 pages each). For each:
1. Run the full pipeline with MRC enabled (foreground masks, `backgroundScale=0.5`)
2. Assert no exception is thrown
3. Assert `output.getNumberOfPages()` matches the source
4. Assert text is extractable via `PDFTextStripper`
5. Assert output file size < 50 MB (sanity — real-world docs vary but should not explode)

**Method**: `mrcOnRealWorldDocs_doesNotCrash()`

---

## Summary

| # | Test | File | New method | Verifies |
|---|------|------|------------|----------|
| 1 | Background JPEG filter | `PDFAssemblerRegressionTest` | `mrcBackground_usesJpegEncoding` | Background is lossy JPEG, not lossless |
| 2 | Background downscale | `PDFAssemblerRegressionTest` | `mrcBackground_isDownscaled` | `backgroundScale` is applied |
| 3 | 4:2:0 chroma | `PDFAssemblerRegressionTest` | `mrcBackground_chromaIs420` | Chroma subsampling is real |
| 4 | MRC vs non-MRC size | `PDFAssemblerRegressionTest` | `mrcCompression_reducesFileSize` | MRC actually saves space |
| 5 | Rendered pixel check | `PDFAssemblerRegressionTest` | `mrcForegroundMask_preservesTextSharpness` | Visual quality: sharp text, smooth bg |
| 6 | JBIG2 vs CCITT | `PDFAssemblerRegressionTest` | `jbig2_compressesBetterThanCcitt` | JBIG2 beats CCITT on multi-page |
| 7 | Real-world stress | `PipelineIntegrationTest` | `mrcOnRealWorldDocs_doesNotCrash` | No crashes on diverse inputs |

No new dependencies. All use existing imports (`PDFRenderer`, `javax.imageio`, `COSName.DCT_DECODE`, `PDFStreamParser`).
