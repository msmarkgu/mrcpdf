# mrcpdf

MRC PDF compression — a self-contained CLI that shrinks any PDF while keeping it **visually lossless** and fully functional. Output preserves the original text (as a searchable invisible layer), bookmarks, comments, links, and embedded attachments.

**Input**: Any PDF<br>
**Output**: MRC-compressed PDF ([Mixed Raster Content (MRC) compression](docs/mrc-compression-explained.md)) — typically 3–10× smaller on scanned documents

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
- **No OCR, no recognition errors** — searchable text is re-used from the source PDF and re-embedded, unlike OCR-based MRC pipelines (e.g. Internet Archive's `archive-pdf-tools`) that run Tesseract and layer in an OCR'd text layer.
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

- OpenJDK 21 LTS → `deps/jdk/` (Adoptium Temurin primary; falls back to `jdk.java.net/archive` if Adoptium is unreachable)
- Gradle 8.0.1 → `deps/gradle/`
- jbig2enc for JBIG2 foreground compression → `deps/jbig2enc/$OS/` (Linux: prebuilt Debian 11 binary using host leptonica; Windows: static MSVC v0.32)
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
| `--jpeg-quality <0.1-1.0>` | Background JPEG quality (default 0.50 with MRC on, 0.85 with MRC off). |
| `--pdfa` | Enable PDF/A-2b output (XMP metadata, sRGB OutputIntent). |
| `--fg-color` | Re-render the foreground in the source's true text colors via a soft-mask color layer. Off by default; not compatible with `--pdfa`. |
| `--threads <n>` | Worker threads for page prep (default: available processors). |
| `--settings <file>` | Path to `settings.jsonc` (default `./settings.jsonc`). |

The jbig2enc binary and CJK font bundled in `deps/` are used automatically; all defaults are configurable via [`settings.jsonc`](settings.jsonc), and CLI flags take precedence.

### High DPI & memory

Rendering memory scales with **DPI²**: going from 300 to 600 DPI quadruples the pixel count, so each page needs ~4× the memory. At 600 DPI a US Letter page is ≈ 5100×6600 px and needs roughly **1 GB per page in flight** (the pipeline keeps about `--threads` + 2 pages in memory at once).

Recommendations for high DPI:

| Setting | Linux / macOS | Windows |
|---------|---------------|---------|
| 300 DPI (default) | `./run.sh input.pdf -o out.pdf` | `run.bat input.pdf -o out.pdf` (2 GB default) |
| 600 DPI | `MRCPDF_HEAP=8g ./run.sh --dpi 600 --threads 2 input.pdf -o out.pdf` | `set MRCPDF_HEAP=8g` then `run.bat --dpi 600 --threads 2 input.pdf -o out.pdf` |

Rule of thumb: allow ~1–1.5 GB per page in flight and cap `--threads` (or `pipeline.maxThreads` in `settings.jsonc`) so `(threads + 2) × per-page` fits your RAM. The default heap is 2 GB (`MRCPDF_HEAP`) on Linux/mac, and 2 GB on Windows after the `run.bat` change.

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
- The invisible text layer is re-rendered with a single font (the Standard 14 font, or a TTF/OTF configured via `pdf.fontPath` in settings.jsonc). Original font family and size styling are not reproduced in the hidden layer — the *visual* layer (background + mask) is unaffected. Text color is preserved in the visual layer when `--fg-color` is enabled.
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
- How MRC compression works, and when not to use it: [`docs/mrc-compression-explained.md`](docs/mrc-compression-explained.md), [`docs/when-not-to-mrc.md`](docs/when-not-to-mrc.md)
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
