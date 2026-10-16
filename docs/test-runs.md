1. Generate all-features.pdf in ./tests folder

```
(base) bgu@z30b:~/../mrcpdf$ ./gradlew generateTestPdfs
```

2. Run MRC on all-features.pdf, output to ./temp

```
(base) bgu@z30b:~/../mrcpdf$ ./deps/jdk/bin/java -jar ./build/mrcpdf.jar ./tests/all-features.pdf -o ./temp/all-features.pdf

MrcPdf v1.0.0
  Input:  ./tests/all-features.pdf (1.5 MB)
  Output: ./temp/all-features.pdf
  DPI:    300.0
  MRC:    on
  Pass-through: on
  Model:  image-only gate, 11 features, skip when p(not good)>=0.300 (holdout AUC 0.952)
  Workers: 8 thread(s)
  Foreground: black stencil
  Quality gate: model (image-only, 11 features, threshold 0.300)
  Pages:  10
  Words:  2317 (source text)
  Pass-through pages: 0 (digital native)
  Processing 10 pages...
    [prep-1] page 1: 10.4s (cum  10.8s)
    [prep-6] page 6:  9.8s (cum  10.8s)
    [prep-8] page 8:  9.4s (cum  10.9s)
    [prep-2] page 2: 10.4s (cum  10.9s)
    [prep-5] page 5: 10.0s (cum  10.9s)
    [prep-3] page 3: 10.3s (cum  10.9s)
    [prep-7] page 7:  9.7s (cum  10.9s)
    [prep-4] page 4: 10.2s (cum  10.9s)
    [prep-9] page 9:  4.4s (cum  15.2s)
    [prep-10] page 10:  4.4s (cum  15.2s)
    Processing done: 10 pages in 15.2s (8 threads)
  Batch JBIG2 compression...
    JBIG2 batch done in 0.5s (sym: 6300 bytes)
  Assembling PDF...
  Finalizing document...
  Warning: 70 characters dropped from invisible text layer — unsupported glyphs for font.
  Set pdf.fontPath in settings.jsonc to a CJK TTF/OTF file (e.g., NotoSansCJKsc-Regular.otf).
  Bookmarks:  2 (preserved)
  Links:      4 (preserved)
  Attachments: 3 (preserved)
  Total: 10 pages in 0:16
  Output: all-features.pdf (543 KB)
  Words:  2317 (extracted text)
```

Notes:

- Recorded against the post-fix pipeline (chroma-safe 4:4:4 JPEG encoding, blur
  sigma rescaled with the background scale). The 1.5 MB → 543 KB (~2.8×) sample
  is a synthetic all-features fixture, not a scan; for real-world ratios run
  `./scripts/benchmark_mrc.sh`.
- The CJK warning shows only because the invisible text layer uses the default
  Latin font; set `pdf.fontPath` in settings.jsonc to a CJK font to lift it.
- Timing and sym-dict sizes vary with machine and content; only sizes and the
  preserved-element counts are stable facts.