# Synthetic-corpus labeling and quality-gate retraining

End-to-end workflow for generating the synthetic quality-gate corpus, labeling
each variant's MRC result, and retraining the committed Phase-2 gate artifact
(`src/main/resources/quality-model.json`).

All commands run from the repo root via the Gradle wrapper (`./gradlew`). The
gradle tasks are defined in `build.gradle.kts` (see the `dumpSyntheticSrc` /
`mrcSyntheticSrc` / `buildSyntheticPairs` / `downloadPdfjs` / `exportLabelHtml`
blocks); the Java-side generators live in
`src/main/java/com/mrcpdf/quality/training/`.

```text
dumpSyntheticSrc                    exportLabelHtml
  (variant pages PDF + map CSV)       (labeling page, opens in browser)
        |                                      |
        v                                      v
mrcSyntheticSrc (gate off)          * hand-label 240 pages *
        |                                      |
        v                                      v
buildSyntheticPairs                 labels-mrc-detail.csv
  (side-by-side QA PDF)                    |
                                           v
    * annotate export            (GAP, see step 6)  --->  * annotate ingest (GAP)
    features-synthetic-src.csv                      training CSV
                                           |
                                           v
                    buildMergedCorpus (optional, re-add real clean rows)
                                           |
                                           v
                TrainModel --corpus -> src/main/resources/quality-model.json
```

## What the corpus is

20 synthetic documents are rendered deterministically (seed 42) as clean scans
at 300 DPI. Each clean page derives 11 degradations; every variant shares the
doc id so train/val splits stay document-aware. Label semantics in
`SyntheticCorpus.buildVariants`:

| Variant                        | Severity      | Input label |
|--------------------------------|---------------|-------------|
| clean                          | —             | 0           |
| blur-1.0                       | mild          | 0           |
| blur-4.5                       | severe        | **1**       |
| noise-6                        | light         | 0           |
| noise-40                       | heavy bg      | 0 (hard neg)|
| jpeg-0.75                      | light         | 0           |
| jpeg-0.1                       | heavy bg      | 0 (hard neg)|
| scale-0.75                     | mild          | 0           |
| scale-0.3                      | severe        | **1**       |
| strokes-4                      | light         | 0           |
| strokes-25                     | heavy         | 0 (hard neg)|
| strokes-50                     | severe        | **1**       |

Label 1 = unambiguously degraded, MRC must not touch. Heavy background
corruptions that leave text crisp stay label 0 as hard negatives; the mild
version of every corruption is label 0.

This input-severity label is only a prior. The workflow re-derives the final
label from a human reading of the MRC output (steps 4 and 6), because what
matters is "readable after MRC", not the input classification.

## Step 1 — Generate the synthetic files

```sh
./gradlew dumpSyntheticSrc
```

Outputs:

- `build/synthetic-src.pdf` — 240 pages (20 docs × [clean + 11 variants])
- `build/synthetic-variant-map.csv` — `page,doc,variant,label`, one row per page

Page *i* of the PDF is row *i* of the map, for every downstream step.

## Step 2 — Gate-off MRC pass

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

## Step 3 — Side-by-side QA doc (optional)

```sh
./gradlew buildSyntheticPairs
```

Renders `build/synthetic-pairs.pdf`: for each of the 240 pages the ORIGINAL on
top and its MRC output below at ~300-DPI panel size, with the variant name and
input label drawn into each page. Useful for a quick eyeball pass before or
alongside the browser labeling tool.

## Step 4 — Label in the browser

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
   zoom. Every tick auto-saves to `localStorage`.
3. Export the judgment sheet with **Export labels CSV** (also available:
   **Export state JSON**, and **Import…** to resume from either).

The exported file is `labels-mrc-detail.csv` with header
`doc,page,input_readable,mrc_readable` (values `1`/`0`/blank). `doc` is
`synthetic-src`, `page` is 1..240 matching the variant map.

## Step 5 — Extract per-page features

There is currently **no Gradle task** for this step; the corpus feature file is
produced manually with the `annotate export` tool:

```sh
./gradlew shadowJar
java -cp build/mrcpdf.jar com.mrcpdf.tools.Annotate export \
     build/synthetic-src.pdf --out build/features-synthetic-src.csv --dpi 300
```

Output: `build/features-synthetic-src.csv` — `doc,page,composite` plus the 11
image features (`blurSigmaPx` … `inkCoverage`), 240 rows.

> Caveat: render DPI feeds the features, so it must match the raster the MRC
> pass saw (`gate-off.jsonc` sets `rendering.dpi: 300`; `annotate export`
> defaults to 200 — pass `--dpi 300`).

## Step 6 — Map labels and join features (GAP)

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

## Step 7 — Retrain the gate

Optionally re-merge the real-world clean rows (the committed artifact is
trained on the merged corpus):

```sh
./gradlew buildMergedCorpus
# -> build/quality-corpus-real.csv (labeled synthetic rows + real clean rows)
```

Train (replace `build/quality-corpus-labeled.csv` with `build/quality-corpus-real.csv`
if you merged):

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

## Current state of the pipeline

| Piece                            | Exists?                                      |
|----------------------------------|----------------------------------------------|
| `dumpSyntheticSrc` task + sources| yes (uncommitted on `dev`)                   |
| `build/synthetic-src.pdf` (240p) | yes (`build/`)                               |
| `build/synthetic-variant-map.csv`| yes (`build/`)                               |
| `mrcSyntheticSrc` / `gate-off.jsonc`| yes (`build/`)                             |
| `build/synthetic-mrc.pdf`        | yes (`build/`)                               |
| `buildSyntheticPairs` task       | yes (uncommitted)                            |
| `exportLabelHtml` + vendored pdfjs | yes (uncommitted)                          |
| `build/label/index.html`         | yes (`build/`)                               |
| `build/features-synthetic-src.csv`| yes (`build/`, produced manually)           |
| **labels**                       | **not started** — `build/labels-mrc.csv` is an empty placeholder |
| join + retrain of labeled set    | **pending** (deferred; steps 6–7 are manual) |