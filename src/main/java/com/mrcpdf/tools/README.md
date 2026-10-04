# TesseractOcrScore

Scores what Tesseract managed to read on a page, from a Tesseract TSV plus the
searchable PDF it wrote alongside. One page or a whole folder, one line per page,
in page order.

This README covers `TesseractOcrScore` only; the other tools in this package
(`TrainModel`, `BuildMergedCorpus`, `Annotate`, `BboxComparator`) are undocumented
here. The design record is `notes/tesseract-ocr-score.md`.

## Quick start

A page needs **two** files — the TSV the engine's words and confidences came
from, and the searchable PDF whose embedded raster those coordinates live in.
One `tesseract` run writes both, and this is the way to get them:

```bash
tesseract page.png page pdf tsv     # page.pdf + page.tsv, in a single OCR pass
```

Renderer order does not matter (`pdf tsv` and `tsv pdf` are equivalent). Asking
for them separately also works and produces byte-identical output — it just
runs the OCR twice:

```bash
tesseract page.png page pdf
tesseract page.png page tsv
```

Prefer the single pass: it halves the OCR time and guarantees the PDF's raster
and the TSV's coordinate space come from the same read. Passing the renderers
together is not a special case — `tesseract` has always accepted several
renderer names as trailing arguments.

Then either one page:

```bash
./gradlew tesseractOcrScore -PtesseractOcrArgs="page.tsv page.pdf"
# 0.900274
```

…or a folder of same-named pairs:

```bash
./gradlew tesseractOcrScore -PtesseractOcrArgs="--dir pages --out scores.txt"
# Scored 3 page(s) -> scores.txt
```

`scores.txt` — one line per page, `name<TAB>score`, in page order:

```
page_1	0.900274
page_2	0.900274
page_10	0.900274
```

Note the order: `page_2` precedes `page_10`, which a lexicographic sort would
break.

Both files are required even though only the PDF's raster size is read from it:
the TSV carries the confidences, and nothing else records the pixel space those
confidences and boxes live in.

## Running it

| Route | Command | Use when |
|---|---|---|
| Gradle task | `./gradlew tesseractOcrScore -PtesseractOcrArgs="<args>"` | day-to-day; also runs the tool's tests via `./gradlew test` |
| Fat jar | `./gradlew shadowJar` then `./deps/jdk/bin/java -cp build/mrcpdf.jar com.mrcpdf.tools.TesseractOcrScore <args>` | paths contain spaces, or you want the tool's own exit code rather than Gradle's build failure |

`-PtesseractOcrArgs` is split on whitespace, so quoting does not help: a path
like `"My Pages/page 1.tsv"` arrives as four arguments and the run stops with a
usage error. Use the fat jar for those.

## Options

| Option | Description |
|---|---|
| `TSV`, `PDF` | Positional, single-page mode: the Tesseract TSV and its searchable PDF. |
| `--dir <folder>` | Batch mode over same-named `<page>.pdf` + `<page>.tsv` pairs. Non-recursive. Cannot be combined with the positionals. |
| `--out <file>` | Batch output path (default `<folder>/tesseract-ocr-scores.txt`). |
| `--page <n>` | Which PDF page supplies the extent (default: the TSV's `page_num`, else 1). In batch mode it applies to every page. |
| `--dpi <n>` | DPI for the page-box fallback, used only when a page embeds no image (default 300). |
| `-h`, `--help`, `-V`, `--version` | Standard picocli. |

Diagnostics, warnings and the batch summary go to **stderr**; in single mode
stdout carries the score and nothing else, so `… > score.txt` is safe.

## Exit codes

| Code | Meaning |
|---|---|
| 0 | Scored. |
| 1 | A read or parse failure: a PDF with no matching TSV, an unreadable PDF, a TSV with no recognisable header row. |
| 2 | Usage: missing arguments, a path that is not a file or directory, `--dir` together with positionals, or a folder containing no PDFs. |

Batch mode validates every pair before writing anything, so a failed run leaves
no partial output file — line *N* of a scores file is always page *N*.

## The score

Four terms over the word confidences, combined by a weighted sum and then gated
on how much the engine found at all:

| Term | Weight | Measures |
|---|---|---|
| `mConfMean` | 0.35 | mean confidence / 100 |
| `mConfTail` | 0.25 | 10th-percentile confidence / 100 — the worst tenth, not the average |
| `mDispersion` | 0.20 | `1 − cv/0.5`: consistent confidences score high, a spread of confidences low |
| `mLex` | 0.20 | fraction of words ≥ 2 characters and > 80 % alphanumeric — rejects punctuation soup |

```
weighted = 0.35·mConfMean + 0.25·mConfTail + 0.20·mDispersion + 0.20·mLex
score    = clamp01(weighted · min(1, usableBoxes / 50))
```

The evidence gate multiplies rather than averages, because the four terms
measure the quality of what the engine *found* and the gate measures whether it
found anything. Without it the metric rewarded blankness: a page whose text had
degraded past recognition was scored like a pristine blank sheet, since a smooth
ink-free page has excellent statistics over a sample of one box.

**A page with no usable boxes scores `0.0`** — an engine that read nothing is
the worst case, not an unknown one.

Only level-5 (word) rows count, and a row must have non-empty text, a
non-negative confidence and a positive area. Confidences are read as floats, as
the TSV format specifies; a fractional value such as `92.4` is kept rather than
dropped. In a TSV written from a multi-page PDF, only the rows whose `page_num`
matches the page being scored are used, so one page's words cannot be folded
into another's statistics.

### Reading a number

`1.0` means 50 or more cleanly-read, lexically sane words. Below the floor the
score falls linearly with the box count, so a page can score low purely for
being sparse. The floor of 50 is not tuned: every page below it on the
500-page pilot was completely unreadable, while the *least* readable clean page
carried 398 boxes, so any value from 30 to 200 gives identical results.

This is a measure of OCR readability, not a verdict on the page. A crisp,
well-typeset page that Tesseract was never asked about — or one carrying
script it cannot read — will not score well.

## Page extent

The usable-box filter compares each box against the page's dimensions, so those
dimensions have to be in the same units as the boxes. TSV coordinates are
**raster pixels**; a PDF page box is **points**. For a 300-DPI letter page that
is 2550×3301 px against 612×792 pt — feeding points to the filter marks almost
every box off-page and drives the score to roughly nothing.

So the extent is taken from the largest embedded image, which is the bitmap
Tesseract actually read. Only when a page embeds no image at all does it fall
back to `(CropBox ?: MediaBox) / 72 × --dpi`, which is the case where `--dpi`
matters.

To confirm the extent a PDF will yield:

```bash
pdfimages -list page.pdf
#  page num type  width height ...
#      1     0 image  2550  3301 ...
```

If those are not the dimensions the TSV was written against, the run is being
scored against the wrong page.

## Usable boxes

A word box is used only if it is plausibly glyph-local:

| Condition | Rationale |
|---|---|
| `width ≥ 4`, `height ≥ 3` | below this a box cannot hold a glyph |
| fully inside `[0,w]×[0,h]` | a box off the page is a parsing artefact |
| `width ≤ 0.8·w`, `height ≤ 0.8·h` | a near-page-sized box is a merged region, not a word |

## Batch rules

- Driven by the PDFs: the folder is scanned non-recursively for `*.pdf`, and
  each needs a same-named `.tsv`.
- A PDF without its TSV **fails the run**. An orphan `.tsv` with no PDF beside
  it is dropped — the run is driven by the PDFs, and a stray sidecar is not a
  page.
- Pages are ordered **naturally**, so `page_2` precedes `page_10`. A
  lexicographic sort would interleave them and break the line↔page guarantee
  for anything downstream that joins the two.
- Any per-page failure fails the whole run.

## Relationship to the MRC pipeline

The tool is deliberately standalone: it does not call `MrcReadiness`, and it
needs neither `TsvExtractor`, `FeatureExtractor` nor `QualityModel`. The
constants and arithmetic are mirrored from `MrcReadiness`, where they were
calibrated, so a raw Tesseract output folder can be scored without the rest of
the pipeline.

The cost of that independence is that the two copies can drift. **Change one and
change the other.**