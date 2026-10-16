# Synthetic-corpus labeling and quality-gate retraining

End-to-end workflow for generating the synthetic quality-gate corpus, labeling
each variant's MRC result, and retraining the committed Phase-2 gate artifact
(`src/main/resources/quality-model.json`).

All commands run from the repo root via the Gradle wrapper (`./gradlew`). The
gradle tasks are defined in `build.gradle.kts` (see the `dumpSyntheticSrc` /
`checkSyntheticCorpus` / `mrcSyntheticSrc` / `buildSyntheticPairs` /
`downloadPdfjs` / `exportLabelHtml` blocks); the Java-side generators live in
`src/main/java/com/mrcpdf/quality/training/`.

> Training the gate on **your own** documents (rather than this synthetic corpus) is
> [`docs/quality-gate-training.md`](quality-gate-training.md). Note in particular that
> the pipeline gates on `quality-image-screen.json`, **not** the `quality-model.json`
> this workflow regenerates.

```text
dumpSyntheticSrc                    exportLabelHtml
  (variant pages PDF + map CSV)       (labeling page, opens in browser)
        |                                      |
        v                                      v
checkSyntheticCorpus (verify)       * hand-label 500 pages *
        |                                      |
        v                                      v
mrcSyntheticSrc (gate off)          labels-mrc-detail.csv
        |                                      |
        v                                      v
buildSyntheticPairs                 * map + join (GAP) *  --->  training CSV
  (side-by-side QA PDF)                              (steps 6-7: manual for now)
                                            |
                                            v
                     buildMergedCorpus (optional, re-add real clean rows)
                                            |
                                            v
                 TrainModel --corpus -> src/main/resources/quality-model.json
```

## What the corpus is

25 synthetic documents are rendered deterministically (seed 42) as clean scans
at 300 DPI. Each document contributes one clean sheet plus 19 degraded sheets,
for **500 pages total** (hence `--docs 25 --pages 20`). Every variant of one
source page shares the `doc` id so train/val splits stay document-aware.

A degraded sheet is not a fixed corruption recipe. Each draws a
[`ScannerTier`](../src/main/java/com/mrcpdf/quality/training/ScannerTier.java)
archetype (excellent office → OCR struggles), samples a `ScannerProfile` from
that tier's parameter range, and runs it through a
`ScannerSimulator` that models the notes in
`notes/simulate-realworld-scans.md`: blur, motion blur, downscale, ink spread,
contrast loss, sensor noise (Gaussian + salt/pepper + fixed pattern), JPEG,
uneven illumination, paper texture, bleed-through, streaks/dust, skew and curl.

Each degraded sheet's **readability score is measured, not assumed**: the
degraded raster is OCR'd and compared word-by-word to the exact text the page
generator printed (`ReadabilityOcr`, `notes/ocr-readable-score.md`). The score
is `1 - word error rate` in `[0,1]` (1.0 = every word readable, 0.0 =
unreadable) and the binary label is `score < SeverityScore.LABEL_CUTOFF`
(currently 0.60): label 1 means unambiguously degraded — MRC must not touch it.
The clean sheet is itself run through a light scan chain (`cleanScan`) so its
features live on the same scale as the degraded sheets; clean sheets hold the
score constant at 1.0.

This measured score captures what actually renders — a downscaled, skewed or
shadowed page fails OCR and scores low, while crisp text on a dirty background
keeps score 0 as a hard negative. The per-effect severities (`SeverityScore`)
only *drive* the tier draw and variant naming; they no longer label.

It is still a **readability prior**, not the final label. The workflow
re-derives the final label from a human reading of the MRC output (steps 4-6),
because what matters is "readable after MRC", not the input classification.

| Tier                | Slug   | Archetype                      |
|---------------------|--------|--------------------------------|
| EXCELLENT_OFFICE    | `ex90` | excellent office scanner       |
| HIGH_QUALITY_MFP    | `mf80` | high-quality multifunction     |
| AVERAGE_OFFICE      | `of70` | average office scan            |
| CHEAP_SCANNER       | `ch60` | inexpensive scanner            |
| OLD_PHOTOCOPY       | `pc50` | old photocopy                  |
| POOR_FAX            | `fx40` | poor fax                       |
| BARELY_READABLE     | `br30` | barely readable                |
| OCR_STRUGGLES       | `oc20` | OCR struggles                  |

## Step 1 — Generate the synthetic files

```sh
./gradlew dumpSyntheticSrc
```

Outputs:

- `build/synthetic-src.pdf` — 500 pages (25 docs × [clean + 19 degraded]).
  Every page carries a vector banner in its blank top margin reading
  `score = X.XXX` (the measured OCR readability).
- `build/synthetic-variant-map.csv` — `page,doc,variant,label,tier,score`, one
  row per page, where `variant` is a readable descriptor such as
  `fx40+blur1.9+jpeg57` and `score` is `0.000`-`1.000`

Page *i* of the PDF is row *i* of the map, for every downstream step.

> Requires `tesseract` with the `eng` language pack (the score is measured by
> OCR against the known page text). The corpus is reproducible for a pinned
> Tesseract version; `dumpSyntheticSrc` fails fast with a hint if it is
> missing.

## Step 2 — Verify the corpus (before labeling)

```sh
./gradlew checkSyntheticCorpus
```

Runs `ScoreCorrCheck` over the generated PDF and reports:

- the label mix and the 10-20 events-per-feature rule for the minority class
  (an 11-feature logistic model needs enough degraded rows to fit stably);
- mean measured readability score per tier (tiers should be ordered);
- the Spearman correlation of the measured readability score with each image
  feature and its expected sign;
- per-tier feature means and overall label ordering.

Fix the tier draw/scoring before labeling if any sign is flagged.

## Step 3 — Gate-off MRC pass

```sh
./gradlew mrcSyntheticSrc
```

Runs the real MRC pipeline over `build/synthetic-src.pdf` using
`build/gate-off.jsonc` (production settings with `pipeline.quality.gateEnabled:
false`), forcing every page through both foreground and background compression
so the labeling doc shows the true MRC result per variant. Output:
`build/synthetic-mrc.pdf`.

> Note: `jbig2enc` is currently absent, so the foreground layer uses the CCITT
> G4 fallback — same visuals, larger file. Install `jbig2enc` into
> `deps/jbig2enc` (see `build/gate-off.jsonc`'s `native.dir`) to get the symbol
> encoder.

## Step 4 — Side-by-side QA doc (optional)

```sh
./gradlew buildSyntheticPairs
```

Renders `build/synthetic-pairs.pdf`: for each of the 500 pages the ORIGINAL on
top and its MRC output below at ~300-DPI panel size, with the variant name,
tier and synthetic score drawn into each page. Useful for a quick eyeball pass
before or alongside the browser labeling tool.

## Step 5 — Label in the browser

```sh
./gradlew exportLabelHtml
```

Fetches a pinned PDF.js build (needs network, one time) into
`build/label/vendor/pdfjs/` and writes `build/label/index.html`. Open it:

```sh
open build/label/index.html
```

1. Load `build/synthetic-src.pdf` as "Original PDF" and `build/synthetic-mrc.pdf`
   as "MRC PDF" via the two file inputs (or serve `build/label` over HTTP, e.g.
   `python3 -m http.server 8000` in that directory, for Safari).
2. For every page, tick **input readable** and **MRC readable**. Keys: `1`
   input, `2` MRC, `0` clear, `ArrowUp`/`ArrowDown` to move; click a page to
   zoom. Every tick auto-saves to `localStorage`. The page metadata shows the
   doc, variant, tier and synthetic score.
3. Export the judgment sheet with **Export labels CSV** (also available:
   **Export state JSON**, and **Import…** to resume from either).

The exported file is `labels-mrc-detail.csv` with header
`doc,page,input_readable,mrc_readable` (values `1`/`0`/blank). `doc` is
`synthetic-src`, `page` is 1..500 matching the variant map.

## Step 6 — Extract per-page features

There is currently **no Gradle task** for this step; the corpus feature file is
produced manually with the `annotate export` tool:

```sh
./gradlew shadowJar
java -cp build/mrcpdf.jar com.mrcpdf.tools.Annotate export \
     build/synthetic-src.pdf --out build/features-synthetic-src.csv --dpi 300
```

Output: `build/features-synthetic-src.csv` — `doc,page,composite` plus the 11
image features (`blurSigmaPx` … `inkCoverage`), 500 rows.

> Caveat: render DPI feeds the features, so it must match the raster the MRC
> pass saw (`gate-off.jsonc` sets `rendering.dpi: 300`; `annotate export`
> defaults to 200 — pass `--dpi 300`).
>
> Caveat: the `score = X.XXX` top-margin banner is drawn into
> `synthetic-src.pdf`, so these features include that overlay's ink. The
> automated `trainQualityModel` path is unaffected (it computes features
> in-memory); only this manual extraction sees the banner.

## Step 7 — Map labels and join features (GAP)

The labeling tool exports two judgments (`input_readable`, `mrc_readable`),
but `annotate ingest` expects a `doc,page,label` sheet with a single 0/1
target. The intended mapping, applied manually until a join tool exists:

- MRC output readable → gate label **0** (allow MRC)
- MRC output unreadable → gate label **1** (degraded, skip MRC)

After producing a `labels-src.csv` (`doc,page,label`) from
`labels-mrc-detail.csv` (e.g. by filtering on `mrc_readable`, keeping pages
where `input_readable` is 1, or keeping them all as hard negatives per your
labeling intent), join it with the features:

```sh
java -cp build/mrcpdf.jar com.mrcpdf.tools.Annotate ingest \
     --features build/features-synthetic-src.csv \
     --labels build/labels-src.csv \
     --out build/quality-corpus-labeled.csv
```

## Step 8 — Retrain the gate

Optionally re-merge the real-world clean rows (the committed artifact is
trained on the merged corpus):

```sh
./gradlew buildMergedCorpus
# -> build/quality-corpus-real.csv (labeled synthetic rows + real clean rows)
```

Train (replace `build/quality-corpus-labeled.csv` with
`build/quality-corpus-real.csv` if you merged):

```sh
./gradlew shadowJar
java -cp build/mrcpdf.jar com.mrcpdf.tools.TrainModel \
     --corpus build/quality-corpus-labeled.csv \
     --out src/main/resources/quality-model.json
```

`TrainModel` performs a document-aware split, winsorizes/z-scores, fits L2
logistic regression, and picks the decision threshold at ~0.9 degraded recall.
It prints validation AUC, threshold, and feature weights — check these before
committing the new artifact.

Commit the regenerated `src/main/resources/quality-model.json` (and the corpus
CSVs/scripts if you made them reproducible) together with the code changes.

> `./gradlew trainQualityModel` is the shortcut for the synthetic-prior path
> only: it generates the corpus (`--generate`) and trains from OCR-measured
> readability labels, **not** human labels. Because those labels still come
> straight from the simulator, that path separates cleanly (AUC ~1) and is a
> sanity/CI artifact, not a substitute for the human-labeled steps above.

## Current state of the pipeline

| Piece                            | Exists?                                      |
|----------------------------------|----------------------------------------------|
| `dumpSyntheticSrc` task + sources| yes (uncommitted on `dev`)                   |
| `build/synthetic-src.pdf` (500p) | yes (`build/`)                               |
| `build/synthetic-variant-map.csv`| yes (`build/`)                               |
| `checkSyntheticCorpus` task      | yes (uncommitted)                            |
| `mrcSyntheticSrc` / `gate-off.jsonc`| yes (`build/`)                             |
| `build/synthetic-mrc.pdf`        | stale from the 240-page corpus               |
| `buildSyntheticPairs` task       | yes (uncommitted)                            |
| `exportLabelHtml` + vendored pdfjs | yes (uncommitted)                          |
| `build/label/index.html`         | yes (`build/`)                               |
| `build/features-synthetic-src.csv`| stale from the 240-page corpus             |
| **labels**                       | **not started** — `build/labels-mrc.csv` is an empty placeholder |
| join + retrain of labeled set    | **pending** (deferred; steps 6-8 are manual) |
