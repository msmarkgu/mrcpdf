# Training your own quality-gate model

mrcpdf's per-page quality gate decides, for every page that is not digital-native,
whether MRC compression would degrade it. Pages the gate flags as "not good" keep
the fidelity-preserving path (full-resolution original image, no JBIG2 mask);
everything else is MRC-compressed. This document is about training a gate tuned to
*your* documents.

```text
your PDFs ──▶ Annotate export ──▶ features.csv ──┐
your labels ─────────────────▶ map.csv  ──┬─────┼────┐
                                         │     v    │
                                         │  trainQualityScreen ─▶ src/main/resources/
                                         │  (RealTrainImageModel)  quality-image-screen.json
                                         │     ^    │
                                         └─────────┘ RealTrainEval / RealTrainCrossVal
```

The gate is a small logistic model over 11 per-page **image statistics** (no OCR at
runtime): `blurSigmaPx, edgeWidthCV, hiFreqEnergy, laplacianVar, otsuSeparability,
michelsonContrast, histRange, bgUniformity, noiseSigma, snr, inkCoverage`. All
machinery below is Java in this repository — no Python, no model servers.

## What the pipeline actually loads

At start-up the tool reads exactly one artifact from its classpath:
`src/main/resources/quality-image-screen.json` (`QualityModel.loadScreenDefault()`).
There is no `--model` flag and no settings key that points elsewhere — to run with
your own gate you **replace that file and rebuild the jar**.

The bundled file is a **good-vs-rest screen**: it stores `pGood` (higher = better).
On load the weights and bias are negated and the threshold flipped, so all pipeline
code sees one polarity — `predict(x)` = p(not good), and the gate skips MRC when

```text
p(not good)  >=  1 − screen.threshold        (shipped: 1 − 0.700 = 0.300)
```

The `good` head inside `heads[]` is the classifier; the `middle` and `bad` heads are
kept only for comparison and are not used by the gate. The identity
`loadScreen(...).predict(x) == 1 − pGood(x)` is pinned by a test, so a file with the
wrong polarity either fails to load or turns every decision upside down — that test
is your safety net (see [Wire it in](#5-wire-it-in)).

> ⚠️ **Not this one:** `src/main/resources/quality-model.json` (written by
> `com.mrcpdf.tools.TrainModel`) is the superseded research artifact. The runtime no
> longer reads it. If you train with `TrainModel` directly, nothing you run will use
> the result. Use the screen workflow below.

## Prerequisites

- JDK 21 and the fat jar: `./bootstrap.sh`, then `./gradlew shadowJar` → `build/mrcpdf.jar`.
  The examples use the project JDK at `./deps/jdk/bin/java`.
- `tesseract` with the `eng` language pack — only if you label pages by OCR.
- Disk/RAM: rendering whole PDFs at 300 DPI is the expensive step; the gradle
  training tasks reserve `-Xmx3g`. Plan on several GB in `build/`.

## 1. DIY — a gate for your own pages

### 1.1 Compute per-page features

```bash
./deps/jdk/bin/java -cp build/mrcpdf.jar com.mrcpdf.tools.Annotate export \
    your-doc.pdf --out your-features.csv --dpi 300
```

One row per page, header `doc,page,composite` followed by the 11 feature names.
`composite` rides along for annotators; the fitter ignores it.

Two things silently poison features, so fix them before labeling:

- **DPI.** Features are measured on the rendered raster. Train at the DPI you will
  compress at (default 300). Rasterize that document at a different DPI later and
  the model is scoring features from a different population.
- **Overlay ink.** Anything rendered onto the page *is* in the features. Repeated
  stamps, timestamps or page numbers printed into the image become part of the data
  and the model will learn them. (The synthetic corpus actively avoids this —
  `docs/synthetic-labels.md` contains the banner note.)
- **The `doc` column.** The fit holds out whole documents, not stray pages, because
  the pages of one document share fonts and layout. `Annotate export` sets `doc` to
  the PDF file name — so export **one PDF per source document**. If every row has its
  own `doc`, the grouped split holds out one page at a time and every report overstates
  generalization.

### 1.2 Label pages with a readability target

The fitter reads a continuous target column, `labelWordF1` (0..1 = word F1, the
fraction of words that survive degradation), and classifies pages against two cuts:

| Class  | Label column          | Meaning                                  |
|--------|-----------------------|------------------------------------------|
| good   | F1 ≥ `--good-above` (0.90) | MRC is worth it — compress            |
| middle | between the cuts       | ambiguous (head kept for comparison)     |
| bad    | F1 ≤ `--bad-below` (0.60) | MRC would degrade — keep fidelity     |

Choose how to get the number honestly:

- **OCR word F1 against known text** — the reference method (what the shipped screen
  is trained on). OCR the degraded page and compare word-by-word to the ground-truth
  text. This is exactly the synthetic pipeline in `docs/synthetic-labels.md`.
- **A measured proxy when there is no ground truth** — e.g.
  `./gradlew tesseractOcrScore -PtesseractOcrArgs="--dir pages --out scores.txt"`
  produces one 0..1 score per page in page order (`tools/README.md`). It measures the
  engine's reading confidence, not word F1, so it lives on a different scale — set the
  cuts to *your* scale (see below) and eyeball the separations.
- **Human judgment** — coarse single ratings (0.05 / 0.5 / 0.95) are enough to steer
  the cuts and catch gross errors, though they give the fitter little gradient.

Because the cuts are yours, keep them consistent across the **fit**, the **eval** and
the **CV** commands below; the notes in `build.gradle.kts` hammer this down (the
`checkRealCorpus` task fails if its cuts contradict `dumpRealTrain`).

### 1.3 Fit

```bash
./deps/jdk/bin/java -cp build/mrcpdf.jar com.mrcpdf.quality.training.RealTrainImageModel \
    --features your-features.csv \
    --map your-map.csv \
    --good-above 0.90 --bad-below 0.60 \
    --threshold 0.70 \
    --out-json my-screen.json
```

The fitter performs a **grouped 80/20 split holding out whole documents**, winsorizes
(1%/99%) and z-scores with statistics fitted on the training rows only, fits three
one-vs-rest logistic heads (400 epochs, lr 0.5, λ 0.01, batch 64, seed 42 by default),
and scores the shipped head on the held-out side. The output JSON carries the
predictions provenance — `fit`, `thresholds`, `scaler`, `metrics`, `notes` — so a
file never lies about how it was made.

`--threshold` is the **operating point** (pGood cutoff): 0.70 is the shipped value,
chosen for precision at the cost of a few extra skipped pages. Raise it (fewer
fidelity pages, more aggressive compression) or lower it (safer, larger output, more
fidelity pages). It is a policy choice — make it with the CV report in hand.

### 1.4 Validate before shipping

```bash
./deps/jdk/bin/java -cp build/mrcpdf.jar com.mrcpdf.quality.training.RealTrainEval \
    --features your-features.csv --map your-map.csv \
    --good-above 0.90 --bad-below 0.60 \
    --out-json eval.json --report eval-report.txt

./deps/jdk/bin/java -cp build/mrcpdf.jar com.mrcpdf.quality.training.RealTrainCrossVal \
    --features your-features.csv --map your-map.csv \
    --good-above 0.90 --bad-below 0.60 \
    --cv 5 --out-json cv.json --report cv-report.txt --errors cv-errors.csv
```

- `RealTrainEval`: the same single grouped split the artifact embeds — useful to
  eyeball, noisy because it is one 100-row holdout.
- `RealTrainCrossVal`: document-aware 5-fold CV that pools held-out predictions from
  per-fold models. **This is the number to quote and tune against**, not the artifact's
  `metrics` block. `cv-errors.csv` lists which held-out pages the gate would skip or
  compress wrongly — read it before trusting your threshold.

Keep the minority class fed: an 11-feature logistic needs on the order of 10–20
degraded events per feature (the real corpus QA enforces a minimum), or the fit is
underdetermined and the CV will tell you loudly.

### 1.5 Wire it in

```bash
mkdir -p tmp/model-backup
cp src/main/resources/quality-image-screen.json tmp/model-backup/
cp my-screen.json src/main/resources/quality-image-screen.json
./gradlew shadowJar
```

Run the guard rail:

```bash
./gradlew test
```

`QualityModelTest` reads the committed file and pins the polarity identity and the
gate's exact tie behaviour — if your artifact breaks the contract (feature order,
missing `good` head, threshold not in (0,1), negative-base negation), a test goes red.

Then compress a known scan and check the banner:

```text
Model:  image-only good-vs-rest screen gate, 11 features, skip when p(not good)>=0.300 (holdout AUC 0.952)
Quality gate: model (image-only good-vs-rest screen, 11 features, threshold 0.300)
```

The `variant` string is not your file name — confirm your file is live by comparing
the **threshold** (`1 − screen.threshold` from your JSON) and the holdout AUC to the
values in your artifact. Set `--threshold` to a distinctive value during testing and
watch it appear as `skip when p(not good)>=(1 − threshold)`.

Finally A/B against the bundled gate: keep the original in `tmp/model-backup`, swap,
and compare per-page decisions, output size, and the visual result on your hardest
pages. Revert with `cp tmp/model-backup/quality-image-screen.json src/main/resources/`
then rebuild.

The gate only sees pages that are **not** digital-native — pass-through pages bypass
it entirely (see `docs/when-not-to-mrc.md`).

## 2. Reference — reproduce the shipped screen from the real corpus

If you want to retrain on the same data the bundled gate was fitted on, the whole
pipeline is wired up as gradle tasks (dependencies and times documented in
`build.gradle.kts` and `notes/collect-realworld-pdfs.md`):

```text
fetchRealBases        manifest corpus/real-bases.csv (US public-domain PDFs), network fetch
dumpRealCorpus        degrade every base page down the scanner tiers, ~4 h, needs tesseract
exportRealBasePages   review PDF of the base pages (fast)
checkRealCorpus       audit + steering QA, ~1.5-2 h
dumpRealTrain         1 clean + 3 steered variants per base page -> build/real-train-{features,map}.csv
trainQualityScreen    same fit as above, writing src/main/resources/quality-image-screen.json
```

Cheap validation slices are available by overriding with `-P`, appended after the
defaults (last flag wins): e.g. `./gradlew dumpRealCorpus -PrealArgs="-pages 6 --page-limit 10"`,
`./gradlew dumpRealTrain -PrealTrainArgs="-pages 4 --seed 42"`. `trainQualityScreen`
consumes exactly the two-CSV contract from [1.3](#13-fit) — produce your own
`build/real-train-features.csv` / `build/real-train-map.csv` and the task will train
your file into the committed location.

## 3. Reference — synthetic and labeled paths (legacy artifact)

- `TrainModel --generate` / `--corpus`, `Annotate ingest`, `exportLabelHtml` and
  `buildMergedCorpus` produce `quality-model.json` — **not loaded by the runtime; use
  for experiments only**. `docs/synthetic-labels.md` is the full walkthrough.
- `./gradlew trainQualityModel` is a shortcut that generates a synthetic corpus and
  trains straight from OCR-measured readability labels (AUC ≈ 1 by construction). It
  is a sanity/CI artifact, not a substitute for the steps above.

## Gotchas — the checklist

- **Wrong artifact.** `TrainModel` → `quality-model.json` is research-only; the gate
  reads the *screen* JSON. Fit with `RealTrainImageModel`.
- **Polarity.** Store pGood; the loader negates. Never hand a screen file to
  `QualityModel.load()`, and never "fix" the head signs to make `predict` intuitive.
- **Feature order.** The JSON embeds `featureNames`; the file must carry exactly these
  and the gate's 11 features in the same positions.
- **Scaler on training rows only.** The winsorize/z-score statistics must describe the
  training rows; leaking the test distribution into the scaler flatters every report.
- **DPI.** Train at the DPI you compress at.
- **Grouping.** Whole documents are held out; one `doc` per page defeats it.
- **Cuts drift.** `--good-above`/`--bad-below` must match across fit/eval/CV.
- **Overlay ink.** Stamps and page banners become features.
- **Noisy metrics.** The artifact's `metrics` block is one grouped holdout; cite the CV.
- **Swap needs a rebuild.** The artifact lives on the classpath; changing the file
  without `shadowJar` changes nothing.
- **When the gate is off** (`pipeline.quality.gateEnabled: false`, or MRC disabled)
  the model is never consulted.