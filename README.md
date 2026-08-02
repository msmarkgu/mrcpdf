# mrcpdf

MRC PDF compression — a self-contained CLI that shrinks any PDF while keeping it **visually lossless** and fully functional. Output preserves the original text (as a searchable invisible layer), bookmarks, comments, links, and embedded attachments.

**Input**: Any PDF<br>
**Output**: MRC-compressed PDF ([Mixed Raster Content](#mixed-raster-content-mrc-compression)) — typically 3–10× smaller on scanned documents

### Quick Start
```bash
git clone https://github.com/msmarkgu/mrcpdf.git
cd mrcpdf
./bootstrap.sh        # installs JDK 21, Gradle, jbig2enc, CJK font (~120 MB)
./run.sh input.pdf -o output.pdf
```

---

## Why mrcpdf

A pure-MRC PDF compressor split out of the [TrulyFreeOCR](https://github.com/msmarkgu/TrulyFreeOCR) project (OCR removed — no Tesseract, no PaddleOCR, no GPU, no cloud):

- **Visually lossless** — MRC splits each page into a background JPEG (downsampled + smoothed) and a razor-sharp JBIG2/CCITT foreground mask, so text stays pixel-crisp while the background compresses heavily.
- **Preserves content** — existing text is re-embedded as an invisible searchable layer; bookmarks, annotations, and embedded attachments are deep-copied into the output.
- **Business-friendly license** — Apache 2.0, no disclosure obligations. All runtime dependencies are permissively licensed.
- **Self-contained** — single fat JAR + `bootstrap.sh`/`bootstrap.bat`. JDK, Gradle, and the JBIG2 native binary are all project-local. No sudo, no Python, no system deps. Copy the folder and it runs anywhere.
- **No cloud / no GPU** — CPU-only, fully offline, zero data leaves the machine.

---

## Getting Started

### Installation

**Prerequisites:** `git`, `curl`, `tar`, `unzip`, and `bash` (or a terminal emulator on Windows).

**No admin rights needed** — every dependency is downloaded into project subdirectories and stays there. The bootstrap script works on Linux, macOS, and Windows.

1. Clone the repository:
   ```bash
   git clone https://github.com/msmarkgu/mrcpdf.git
   cd mrcpdf
   ```

2. Run the bootstrap script for your platform:
   ```bash
   # Linux / macOS
   ./bootstrap.sh

   # Windows (PowerShell or Command Prompt)
   bootstrap.bat
   ```

<details>
<summary>What gets installed (4 components, all under <code>deps/</code>)</summary>

- OpenJDK 21 LTS → `deps/jdk/`
- Gradle 8.0.1 → `deps/gradle/`
- jbig2enc for JBIG2 foreground compression → `deps/jbig2enc/$OS/`
- Noto Sans SC CJK font (SIL OFL 1.1) → `deps/fonts/`

</details>

### Usage

```bash
./run.sh input.pdf -o output.pdf
```

Build the fat JAR manually (not needed if you use `run.sh`):
```bash
./gradlew build          # produces build/mrcpdf.jar (skips tests for speed)
./deps/jdk/bin/java -jar build/mrcpdf.jar input.pdf -o output.pdf
```

### CLI Options

| Option | Description |
|--------|-------------|
| `input.pdf` | Input PDF file (positional). |
| `-o, --output <file>` | Output PDF path (default `output.pdf`). |
| `--dpi <n>` | Rendering DPI for page images (default 300). |
| `--bg-scale <0.1-1.0>` | Background downscale factor (default 0.33). |
| `--jpeg-quality <0.1-1.0>` | Background JPEG quality when a mask is present (default 0.50). |
| `--pdfa` | Enable PDF/A-2b output (XMP metadata, sRGB OutputIntent). |
| `--threads <n>` | Worker threads for page prep (default: available processors). |
| `--settings <file>` | Path to `settings.jsonc` (default `./settings.jsonc`). |

The jbig2enc binary and CJK font bundled in `deps/` are used automatically; all defaults are configurable via [`settings.jsonc`](settings.jsonc), and CLI flags take precedence.

---

## Mixed Raster Content (MRC) Compression

Each page is re-encoded as three layers:

1. **Background** — the cleaned page image: downsampled (3× by default), mildly blurred, and JPEG-compressed at 4:2:0 chroma subsampling. Text regions are inpainted away so the JPEG carries almost no text detail.
2. **Foreground mask** — a binary stencil of the text pixels, compressed with **JBIG2** (shared symbol dictionary across all pages) or CCITT G4 fallback. Drawn as a PDF ImageMask in black, it restores text at full resolution.
3. **Text layer** — the source PDF's existing text, re-positioned word-by-word as invisible (`RenderingMode.NEITHER`) searchable text.

Because the mask supplies the sharp text and the background supplies the smooth content, file size drops dramatically with no visible quality loss.

<details>
<summary>How JBIG2 compression works</summary>

JBIG2 (ISO/IEC 14492) is a lossy bitonal compression standard built for scanned text. mrcpdf feeds the binary foreground mask to the bundled `jbig2enc` binary (via `JBIG2Compressor`).

- **Symbol modeling** — the encoder extracts each connected black component (a character, or pieces of merged characters) and clusters them into a **symbol dictionary** of distinct glyph shapes. A page with thousands of characters typically reduces to a few dozen unique symbols.
- **Symbol matching** — each region is then encoded as a reference to the closest dictionary symbol (by ID + position offset), a **refinement** (deltas from a similar symbol), or raw pattern bits when no match is good enough.
- **Shared dictionary across pages** — one jbig2enc invocation over all pages (`-p -s` flags, configurable via `jbig2enc.flags` in settings.jsonc) builds a single **global symbol dictionary** reused by every page. Forms and reports that reuse the same glyphs shrink dramatically because each symbol is stored only once.
- **Adaptive arithmetic coding** — symbol IDs, offsets, and refinement bits are entropy-coded with a context-adaptive arithmetic coder that learns local probability models, squeezing near the theoretical limit.
- **Lossiness** — near-identical symbols are treated as identical, so the output is visually lossless but not bit-identical: slightly different instantiations of a glyph may be swapped for one canonical shape.

The compressed stream (global symbol segment + per-page segments) is embedded as a PDF ImageMask and drawn in black over the JPEG background, restoring razor-sharp text at full resolution.

If `jbig2enc` is unavailable or fails, mrcpdf falls back to **CCITT G4** (fax) compression — the same bitonal idea but with no symbol modeling, so output is larger.

</details>

<br>

> Note: mrcpdf re-embeds text extracted from the source PDF. If the source has no text layer (e.g. a raw scan), pages are still MRC-compressed but carry no invisible text — there is no OCR.

---

## What is preserved

| Element | Status |
|---------|--------|
| Document info (title, author, subject, keywords, creator, producer) | ✅ Preserved |
| Bookmarks / outlines | ✅ Preserved (deep copy) |
| Annotations (comments, underlines, …) | ✅ Preserved (deep copy) |
| Embedded attachments | ✅ Preserved (deep copy) |
| XMP metadata | ✅ Preserved |
| Page size / orientation | ✅ Preserved (per crop box) |

### Known limitations
- The invisible text layer is re-rendered with a single font (the Standard 14 font, or a TTF/OTF configured via `pdf.fontPath` in settings.jsonc). Original font family, size styling, and color are not reproduced in the hidden layer — the *visual* layer (background + mask) is unaffected.
- Annotation destinations and named destinations are kept but not remapped to the new document; page-number-based links (bookmarks pointing to pages in order) generally still work.

---

## Development

```bash
./gradlew build          # compile + build build/mrcpdf.jar (skips tests)
./gradlew test           # run tests only
./gradlew generateTestPdfs   # regenerate the tests/*.pdf fixtures
```

- Source: `src/main/java/com/mrcpdf/`
- Tests: `src/test/java/com/mrcpdf/` (JUnit 5; requires Java 21)
- Performance/compression analysis: [`docs/MRC-compression-effect.md`](docs/MRC-compression-effect.md), [`docs/report-mrc.md`](docs/report-mrc.md)
- Design notes: [`docs/mrc-only-plan.md`](docs/mrc-only-plan.md), [`docs/bookmarks-hyperlinks-attachments.md`](docs/bookmarks-hyperlinks-attachments.md)
- Test runs (sample CLI output, compression results): [`docs/test-runs.md`](docs/test-runs.md)

### Benchmarking

```bash
./scripts/benchmark_mrc.sh                 # all real-world PDFs
./scripts/benchmark_mrc.sh simple-text.pdf # a single file
```

---

## License & Dependencies

Licensed under the **Apache License, Version 2.0**. See [LICENSE](LICENSE) and [NOTICE.md](NOTICE.md) for third-party attribution.

Runtime dependencies: Apache PDFBox (Apache 2.0), picocli (Apache 2.0), jbig2enc (Apache 2.0), jai-imageio-jpeg2000, OpenJDK 21 (GPLv2 + Classpath Exception), Noto Sans SC font (SIL OFL 1.1).
