# When NOT to use MRC

A companion to [`mrc-compression-explained.md`](mrc-compression-explained.md). MRC is great for
typical scanned / text-heavy documents, but it is not always the right answer.
This note covers the cases where **turning MRC off is better** and how to do it.

---

## 1. What the "off" path actually is

`pipeline.mrc.enabled: false` (in `settings.jsonc`) does **not** mean "don't
compress". It skips the MRC-specific machinery and re-encodes each page as a
**single high-quality JPEG** plus the invisible text layer:

| | MRC **on** | MRC **off** |
|--|-----------|-------------|
| Segmentation (mask + inpaint) | ✔ | ✘ skipped |
| JBIG2 batch (shared symbol dict) | ✔ | ✘ skipped |
| Foreground mask object | binary stencil / SMask | none |
| Background | JPEG ~0.50, 3× downscaled, blurred | JPEG **0.85, no downscale, no blur** |
| Text layer | invisible (searchable) | still present |

See `MrcPdf.java` (branch on `useMrc`) and `PDFAssembler.encodeBackgroundJpeg`
(`hasMask == false` → quality 0.85, no scaling).

So **off** ≈ "normalize the PDF into full-res, moderate-quality JPEGs + a text
layer". The output is a real, intact PDF — just *larger* than MRC output.

---

## 2. When MRC does not pay off

### 2.1 Photo- and graphics-heavy documents

MRC's win comes from separating *text* from *background*. If a page is mostly
photos, diagrams, or gradients (little or no high-contrast 1-bit text), the
mask captures almost nothing:

- You still run the whole **O(width × height) segmentation** (Sauvola integral
  images, inpainting, connected-component labeling) and a **jbig2enc
  subprocess** — for a mask that is nearly blank.
- The mask adds a layer object but doesn't reduce the background much, so the
  file is not meaningfully smaller — and you paid extra CPU for nothing.
- A photo is *exactly* what a lossy single JPEG handles best; the downscaled,
  blurred MRC background can also wander off quality on fine image detail.

### 2.2 Speed-sensitive bulk jobs

Per page, MRC runs the most expensive steps:

- background normalization + thresholding (`backgroundNormalize`,
  `sauvolaThreshold` with integral images),
- connected-component filtering (`filterLargeComponents`, union-find),
- inpainting (two-pass distance transform, `inpaintBackground`),
- a `jbig2enc` process invocation for the batch.

Turning MRC off collapses this to render → JPEG-encode. For large corpora where
wall-clock time matters and quality is "good enough", off is substantially
faster.

### 2.3 Downstream compatibility

MRC output carries per-page `ImageMask` stencils (or `/SMask` color planes).
Some viewers, print workflows, and middle-ware **mishandle masked images** —
wrongly clipping or ignoring the mask. A single-JPEG-per-page PDF is maximally
compatible:

- one image per page, no mask object,
- no soft masks (`/SMask`), which also sidesteps the PDF/A soft-mask
  restriction,
- simplest possible output to feed into other tools or to merge/extract pages.

### 2.4 Quality-critical or hard-to-segment scans

The foreground mask is **1 bit**, so its sharpness is capped by how well
thresholding separates text. On low-contrast, faded, or noisy scans,
Sauvola/Otsu can:

- break thin strokes (masked text looks *thinner* than the original),
- over-segment and put background speckle into the mask,
- leave faint text that should be visible.

In those cases a **0.85-quality, full-resolution JPEG background** preserves
every tone and edge — no mask to mangle the reading experience. Use MRC-off as
a quality safety valve when segmentation demonstrably hurts.

### 2.5 Benchmarking and validation

The repo ships `scripts/benchmark_mrc.sh`. Comparing on vs off on a given
corpus confirms **that MRC actually helps** for it (size win, acceptable
presence of a mask layer). If on/off sizes are within noise, the document isn't
MRC-friendly and you may just leave it off.

---

## 3. How to switch it off today

Edit `settings.jsonc` → set:

```jsonc
"pipeline.mrc.enabled": false
```

> ⚠️ Currently there is **no `--no-mrc` CLI flag**. Only `--settings <file>`
> (a custom settings file) or editing the bundled `settings.jsonc` can disable
> MRC. Everything else — `--dpi`, `--bg-scale`, `--fg-color`, `--threads`,
> `--pdfa` — has a CLI override; MRC is the exception.

---

## 4. Quick decision guide

**Use MRC *off* if …**
- pages are mostly photos / graphics / gradients (little 1-bit text),
- time matters more than size (bulk conversion),
- downstream tools struggle with masked images,
- the source scan is low-quality and segmentation destroys faint text,
- benchmarking shows no size benefit for the corpus.

**Keep MRC *on* if …**
- pages are text-on-background (scanned documents, reports, forms),
- file size is the priority,
- the source segments cleanly (text survives thresholding intact),
- you want the shared JBIG2 symbol dictionary to crush repeated glyphs.

When in doubt, **benchmark on/off** on the actual files.