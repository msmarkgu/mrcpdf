import java.io.ByteArrayOutputStream

plugins {
    application
    id("com.github.johnrengelman.shadow") version "8.1.1"
}

group = "com.mrcpdf"
version = "1.0.0"

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.apache.pdfbox:pdfbox:3.0.6")
    implementation("org.apache.pdfbox:jbig2-imageio:3.0.5")
    implementation("com.github.jai-imageio:jai-imageio-jpeg2000:1.4.0")
    implementation("info.picocli:picocli:4.7.6")
    annotationProcessor("info.picocli:picocli-codegen:4.7.6")

    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application {
    mainClass.set("com.mrcpdf.MrcPdf")
}

tasks.named<Test>("test") {
    dependsOn("generateTestPdfs")
    outputs.upToDateWhen { false }
    maxParallelForks = 2
    minHeapSize = "512m"
    maxHeapSize = "3g"
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
    }
    afterSuite(KotlinClosure2({ desc: TestDescriptor, result: TestResult ->
        if (desc.parent != null) {
            println("  ${desc.name}: ${result.resultType} (${result.testCount} tests, " +
                "${result.successfulTestCount} passed, ${result.failedTestCount} failed, " +
                "${result.skippedTestCount} skipped)")
        } else {
            println("")
            println("Total: ${result.resultType} (${result.testCount} tests, " +
                "${result.successfulTestCount} passed, ${result.failedTestCount} failed, " +
                "${result.skippedTestCount} skipped)")
        }
    }))
}

tasks.register<JavaExec>("generateTestPdfs") {
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("com.mrcpdf.TestPdfGenerator")
    args("--force")
}

// Regenerates the committed Phase-2 quality-gate artifact from the synthetic
// corpus.  Run after changing FeatureExtractor or the corpus generator.
// Requires `tesseract` (eng) — the corpus labels are OCR-measured readability.
tasks.register<JavaExec>("trainQualityModel") {
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.mrcpdf.tools.TrainModel")
    args("--generate", "build/quality-corpus.csv",
         "--out", "src/main/resources/quality-model.json",
         "--variant", "image-only", "--docs", "25", "--seed", "42")
}

// Emits build/synthetic-pages.pdf: one labeled preview sheet per synthetic doc
// (all clean/mild/degraded variants) so the training pages can be eyeballed.
tasks.register<JavaExec>("dumpSyntheticPages") {
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.mrcpdf.quality.training.SynthPageDump")
    args("--docs", "25", "--seed", "42", "--out", "build/synthetic-pages.pdf")
}

// Merges the synthetic corpus with the real clean-page rows into
// build/quality-corpus-real.csv, the corpus the committed artifact is trained
// on.  Run after regenerating the synthetic corpus (e.g. after a generator
// change) before trainQualityModel --corpus.
tasks.register<JavaExec>("buildMergedCorpus") {
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.mrcpdf.tools.BuildMergedCorpus")
    args("--synthetic", "build/quality-corpus.csv",
         "--features", "eval/real-world/features",
         "--out", "build/quality-corpus-real.csv")
}

// Dumps the raw 300-DPI corpus variant pages into a plain PDF (one page per
// variant, same order as synthetic-pages.pdf) plus a page->variant map CSV
// (page,doc,variant,label,tier,score with measured OCR readability), so a
// gate-off MRC pass can be labeled and joined back to corpus rows.
// Requires `tesseract` (eng) — see notes/ocr-readable-score.md.
tasks.register<JavaExec>("dumpSyntheticSrc") {
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.mrcpdf.quality.training.SynthSrcDump")
    args("--docs", "25", "--pages", "20", "--seed", "42",
         "--out", "build/synthetic-src.pdf",
         "--map", "build/synthetic-variant-map.csv")
    outputs.files(
        layout.buildDirectory.file("synthetic-src.pdf"),
        layout.buildDirectory.file("synthetic-variant-map.csv"))
}

// Verifies the generated corpus before labeling: checks the 10-20
// events-per-feature rule for the minority class, ordering of the mean
// measured readability score by tier, and Spearman sign of each image feature
// vs the measured score.
tasks.register<JavaExec>("checkSyntheticCorpus") {
    dependsOn("dumpSyntheticSrc")
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.mrcpdf.quality.training.ScoreCorrCheck")
    jvmArgs("-Xmx3g")
    args("--src", "build/synthetic-src.pdf",
         "--map", "build/synthetic-variant-map.csv",
         "--dpi", "300", "--min-events", "165")
}

// Runs the real MRC pipeline over build/synthetic-src.pdf with the quality
// gate disabled (build/gate-off.jsonc), so every page is compressed both ways
// for labeling.  jbig2enc is currently absent -> CCITT G4 fallback (same
// visuals, larger file).
tasks.register<JavaExec>("mrcSyntheticSrc") {
    dependsOn("dumpSyntheticSrc", "writeGateOffConfig")
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.mrcpdf.MrcPdf")
    jvmArgs("-Xmx3g")
    args("--settings", "build/gate-off.jsonc", "--threads", "4",
         "build/synthetic-src.pdf", "-o", "build/synthetic-mrc.pdf")
}

// Renders the side-by-side original | MRC labeling doc from the src and MRC
// PDFs (500 pages; page i corresponds to variant-map row i).
tasks.register<JavaExec>("buildSyntheticPairs") {
    dependsOn("mrcSyntheticSrc")
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.mrcpdf.quality.training.BuildSyntheticPairs")
    jvmArgs("-Xmx3g")
    args("--src", "build/synthetic-src.pdf",
         "--mrc", "build/synthetic-mrc.pdf",
         "--map", "build/synthetic-variant-map.csv",
         "--out", "build/synthetic-pairs.pdf")
}

// Writes build/gate-off.jsonc: production settings with the per-page quality
// gate disabled, so a gate-off MRC pass compresses every page both ways for
// labeling. Generated from settings.jsonc so flag changes stay in one place.
tasks.register("writeGateOffConfig") {
    val src = file("settings.jsonc")
    val out = layout.buildDirectory.file("gate-off.jsonc").get().asFile
    inputs.file(src)
    outputs.file(out)
    doLast {
        val text = src.readText()
        val noGate = text.replace("\"pipeline.quality.gateEnabled\": true",
                "\"pipeline.quality.gateEnabled\": false")
        out.parentFile.mkdirs()
        out.writeText(noGate)
    }
}

// Downloads the born-digital real-world base corpus listed in
// corpus/real-bases.csv into build/real-bases/<doc>.pdf.  The manifest (URL +
// license + source) is committed; the fetched PDFs are not — build/ is
// gitignored.  Dead or redirected links are reported and skipped instead of
// failing the build, so one stale URL never blocks the rest of the corpus.
// Emits build/real-bases/provenance.csv (per-doc reachability audit) and
// build/real-bases/available.txt (the bases that fetched cleanly), which
// RealSrcDump consumes via --base-list.  See notes/collect-realworld-pdfs.md.
tasks.register("fetchRealBases") {
    val manifest = file("corpus/real-bases.csv")
    val outDir = layout.buildDirectory.dir("real-bases").get().asFile
    inputs.file(manifest)
    outputs.dir(outDir)
    doLast {
        if (!manifest.isFile) throw GradleException("missing manifest: ${manifest.path}")
        outDir.mkdirs()
        val rows = ArrayList<String>()
        rows.add("doc,url,license,source,kind,status,bytes,local")
        var ok = 0
        var failed = 0
        manifest.readLines().drop(1).forEach { line ->
            val t = line.trim()
            if (t.isEmpty() || t.startsWith("#")) return@forEach
            val f = t.split(",")
            if (f.size < 3) return@forEach
            val doc = f[0].trim()
            val url = f[1].trim()
            val license = f[2].trim()
            val source = f.getOrNull(3)?.trim().orEmpty()
            val kind = f.getOrNull(4)?.trim().orEmpty()
            val dest = outDir.resolve("$doc.pdf")
            if (dest.isFile && dest.length() > 1024L) {
                rows.add("$doc,$url,$license,$source,$kind,cached,${dest.length()},${dest.name}")
                ok++
                return@forEach
            }
            val buf = ByteArrayOutputStream()
            val code = try {
                exec {
                    isIgnoreExitValue = true
                    commandLine("curl", "-sSL", "--max-time", "180",
                        "-o", dest.absolutePath, "-w", "%{http_code}", url)
                    standardOutput = buf
                }
                buf.toString().trim().ifEmpty { "000" }
            } catch (e: Exception) {
                "err"
            }
            if (code == "200" && dest.isFile && dest.length() > 1024L) {
                rows.add("$doc,$url,$license,$source,$kind,ok,${dest.length()},${dest.name}")
                ok++
            } else {
                dest.delete()
                rows.add("$doc,$url,$license,$source,$kind,failed-$code,0,")
                failed++
                logger.lifecycle("  fetchRealBases: SKIP $doc ($code) $url")
            }
        }
        outDir.resolve("provenance.csv").writeText(rows.joinToString("\n") + "\n")
        val available = outDir.listFiles { f -> f.isFile && f.name.endsWith(".pdf") && f.length() > 1024L }
            ?.sortedBy { it.name }
            ?.joinToString("\n") { it.absolutePath }
            .orEmpty()
        outDir.resolve("available.txt").writeText(available + "\n")
        logger.lifecycle("  fetchRealBases: $ok available, $failed skipped")
    }
}

// Dumps 300-DPI scan-quality variants of a REAL base document (not the AWT
// placeholder pages): each base page is rendered, degraded through the same
// scanner tiers, and embedded into build/real-src.pdf with a page->variant map
// (page,doc,variant,label,tier,score, measured OCR readability).  Requires
// `tesseract` (eng) — see notes/ocr-readable-score.md.  With --base-list it
// sweeps every base fetched by fetchRealBases, one output page per (doc,page).
tasks.register<JavaExec>("dumpRealSrc") {
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.mrcpdf.quality.training.RealSrcDump")
    jvmArgs("-Xmx3g")
    args("--base", "notes/MRC-Compression-Explained.pdf", "--pages", "20", "--seed", "42",
         "--out", "build/real-src.pdf",
         "--map", "build/real-variant-map.csv")
    outputs.files(
        layout.buildDirectory.file("real-src.pdf"),
        layout.buildDirectory.file("real-variant-map.csv"))
}

// Cross-document corpus dump: sweeps every base fetched by fetchRealBases and
// keeps the 5 most prose-dense pages per document (150..3000 text-layer words),
// so cover/TOC/plate/table-only pages can't pollute the OCR labels.  Every
// variant of one base document shares a `real<N>` doc id, which keeps
// Dataset.splitByDoc holding out whole documents.
//
// Gradle's --args *replaces* this task's args rather than appending, so extra
// flags go through -P instead and are appended after the defaults (last
// occurrence wins in RealSrcDump's parser, so they can override):
//   ./gradlew dumpRealCorpus -PrealArgs="--pages 6 --page-limit 10"
// for a cheap sliced validation run.
tasks.register<JavaExec>("dumpRealCorpus") {
    dependsOn("fetchRealBases")
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.mrcpdf.quality.training.RealSrcDump")
    jvmArgs("-Xmx3g")
    val baseList = "build/real-bases/available.txt"
    val outPdf = "build/real-corpus-src.pdf"
    val outMap = "build/real-corpus-variant-map.csv"
    val defaults = listOf("--base-list", baseList, "--pages-per-doc", "5", "--pages", "20",
        "--seed", "42", "--out", outPdf, "--map", outMap)
    setArgs(defaults)
    doFirst {
        val extra = providers.gradleProperty("realArgs").orNull
        if (!extra.isNullOrBlank()) {
            setArgs(defaults + extra.trim().split(Regex("\\s+")))
        }
    }
    outputs.files(
        layout.buildDirectory.file("real-corpus-src.pdf"),
        layout.buildDirectory.file("real-corpus-variant-map.csv"))
    inputs.file(baseList)
    inputs.property("realArgs", providers.gradleProperty("realArgs").orElse(""))
}

// Gate-off MRC pass over the real-variant source PDF, so every page is
// compressed both ways for the side-by-side labeling doc.
tasks.register<JavaExec>("mrcRealSrc") {
    dependsOn("dumpRealSrc", "writeGateOffConfig")
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.mrcpdf.MrcPdf")
    jvmArgs("-Xmx3g")
    args("--settings", "build/gate-off.jsonc", "--threads", "4",
         "build/real-src.pdf", "-o", "build/real-mrc.pdf")
}

// Renders the side-by-side original | MRC labeling doc for the real variants.
tasks.register<JavaExec>("buildRealPairs") {
    dependsOn("mrcRealSrc")
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.mrcpdf.quality.training.BuildSyntheticPairs")
    jvmArgs("-Xmx3g")
    args("--src", "build/real-src.pdf",
         "--mrc", "build/real-mrc.pdf",
         "--map", "build/real-variant-map.csv",
         "--out", "build/real-pairs.pdf")
}

// Pulls the 125 base pages out of the full dump into one review PDF
// (build/real-base-pages.pdf) plus an index CSV, so the corpus can be eyeballed
// without paging through 20 grades per base page.  Pages are copied with
// PDDocument.importPage, so nothing is re-rendered.  See notes/collect-realworld-pdfs.md.
//
// Deliberately does NOT dependOn("dumpRealCorpus"): the dump takes ~4 h, so a
// dependsOn plus any up-to-date invalidation would silently cost 4 h to produce
// a 65 MB review PDF.  Instead the dump artifacts are declared as inputs, and
// ExportRealBasePages fails with a clear message if they are missing.
tasks.register<JavaExec>("exportRealBasePages") {
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.mrcpdf.quality.training.ExportRealBasePages")
    jvmArgs("-Xmx3g")
    val variantMap = "build/real-corpus-variant-map.csv"
    val srcPdf = "build/real-corpus-src.pdf"
    val logFile = "build/corpus-full.log"
    val availableTxt = "build/real-bases/available.txt"
    val outPdf = "build/real-base-pages.pdf"
    val outIndex = "build/real-base-pages.csv"
    val defaults = listOf("--variant-map", variantMap, "--src", srcPdf,
        "--log", logFile, "--available", availableTxt, "--out", outPdf, "--index", outIndex)
    setArgs(defaults)
    doFirst {
        val missing = listOf(variantMap, srcPdf).filter { !file(it).isFile }
        if (missing.isNotEmpty()) {
            throw GradleException("missing dump artifacts ${missing.joinToString(", ")} — "
                + "run ./gradlew dumpRealCorpus first (or -x dumpRealCorpus to skip the dependency)")
        }
        val extra = providers.gradleProperty("realBaseArgs").orNull
        if (!extra.isNullOrBlank()) {
            setArgs(defaults + extra.trim().split(Regex("\\s+")))
        }
    }
    outputs.files(
        layout.buildDirectory.file("real-base-pages.pdf"),
        layout.buildDirectory.file("real-base-pages.csv"))
    inputs.file(variantMap)
    inputs.file(availableTxt)
    inputs.property("realBaseArgs", providers.gradleProperty("realBaseArgs").orElse(""))
}

// Measures the raw MrcReadiness quantities over the corpus's own clean scans and
// over a fixed reference photocopy, then prints the constants for MrcRefs.  Only
// needed when the corpus or the metric bodies change; the output must be pasted
// back into MrcRefs and recorded in notes/mrc-calibration.md.
// Requires `tesseract` (eng) — two OCR passes per page.  Long: ~40 min for 125
// pages, or pass a page limit as the first argument.
tasks.register<JavaExec>("calibrateMrc") {
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.mrcpdf.quality.training.MrcCalibration")
    jvmArgs("-Xmx3g")
    val reviewPdf = "build/real-base-pages.pdf"
    doFirst {
        if (!file(reviewPdf).isFile) {
            throw GradleException("missing $reviewPdf — run ./gradlew exportRealBasePages first")
        }
    }
}

// Generates the labeled training set (1 clean + 9 stochastic scan variants per
// base page) from the review corpus.  Each variant is labeled by R_mrc
// (MrcReadiness, bucketed good/middle/bad), a reference-free measurement of the
// page's own pixels -- deliberately not by an analytic severity (which reads the
// generator's parameters) nor by the same features the model consumes, either
// of which would be circular.  Measured OCR word-multiset F1 is recorded
// alongside as auditWordF1 for validation, but does not define the label.
// Long: ~1.5-2 h with --workers 3.
tasks.register<JavaExec>("dumpRealTrain") {
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.mrcpdf.quality.training.RealTrainDump")
    jvmArgs("-Xmx3g")
    val reviewPdf = "build/real-base-pages.pdf"
    val baseIndex = "build/real-base-pages.csv"
    val basesDir = "build/real-bases"
    val defaults = listOf("--review", reviewPdf, "--index", baseIndex, "--bases", basesDir,
        "--out", "build/real-train-src.pdf", "--features", "build/real-train-features.csv",
        "--map", "build/real-train-map.csv", "--shard-dir", "build/real-train-shards",
        "--pages", "10", "--seed", "42", "--workers", "3")
    setArgs(defaults)
    doFirst {
        val missing = listOf(reviewPdf, baseIndex).filter { !file(it).isFile }
        if (missing.isNotEmpty()) {
            throw GradleException("missing base-page artifacts ${missing.joinToString(", ")} — "
                + "run ./gradlew exportRealBasePages first")
        }
        val extra = providers.gradleProperty("realTrainArgs").orNull
        if (!extra.isNullOrBlank()) {
            setArgs(defaults + extra.trim().split(Regex("\\s+")))
        }
    }
    outputs.files(
        layout.buildDirectory.file("real-train-src.pdf"),
        layout.buildDirectory.file("real-train-features.csv"),
        layout.buildDirectory.file("real-train-map.csv"))
    inputs.file(reviewPdf)
    inputs.file(baseIndex)
    inputs.property("realTrainArgs", providers.gradleProperty("realTrainArgs").orElse(""))
}

// Fits 3 one-vs-rest logistic regressions per feature set and reports
// good/middle/bad results for both the requested random 250/1000 split and a
// grouped split that holds out 25 whole base pages.  Seconds, not minutes.
tasks.register<JavaExec>("evalRealTrainModel") {
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.mrcpdf.quality.training.RealTrainEval")
    jvmArgs("-Xmx2g")
    val features = "build/real-train-features.csv"
    val map = "build/real-train-map.csv"
    val defaults = listOf("--features", features, "--map", map,
        "--out-json", "build/real-train-model.json", "--report", "build/real-train-report.txt",
        "--seed", "42", "--epochs", "400", "--lr", "0.5", "--lambda", "0.01")
    setArgs(defaults)
    doFirst {
        val missing = listOf(features).filter { !file(it).isFile }
        if (missing.isNotEmpty()) {
            throw GradleException("missing ${missing.joinToString(", ")} — run ./gradlew dumpRealTrain first")
        }
        val extra = providers.gradleProperty("realTrainEvalArgs").orNull
        if (!extra.isNullOrBlank()) {
            setArgs(defaults + extra.trim().split(Regex("\\s+")))
        }
    }
    outputs.files(
        layout.buildDirectory.file("real-train-model.json"),
        layout.buildDirectory.file("real-train-report.txt"))
    inputs.file(features)
    inputs.file(map)
    inputs.property("realTrainEvalArgs", providers.gradleProperty("realTrainEvalArgs").orElse(""))
}

// Vendors a pinned pdfjs legacy UMD build into build/label/vendor/pdfjs so the
// static labeling page can render PDFs over file:// (classic script, not ES
// modules).  Requires network; one-time fetch.
tasks.register("downloadPdfjs") {
    val dir = project.layout.buildDirectory.dir("label/vendor/pdfjs").get().asFile
    dir.mkdirs()
    val ver = "3.11.174"
    val base = "https://unpkg.com/pdfjs-dist@$ver/legacy/build"
    inputs.property("pdfjsVersion", ver)
    outputs.files(dir.resolve("pdf.min.js"), dir.resolve("pdf.worker.min.js"))
    doLast {
        exec { workingDir = dir; commandLine("curl", "-fsSL", "-o", "pdf.min.js", "$base/pdf.min.js") }
        exec { workingDir = dir; commandLine("curl", "-fsSL", "-o", "pdf.worker.min.js", "$base/pdf.worker.min.js") }
    }
}

// Emits build/label/index.html: the static PDF.js labeling page for the
// synthetic corpus (original | MRC per row, readability checkboxes, autosave
// to localStorage, CSV/state export).  Retraining steps are deferred.
tasks.register<JavaExec>("exportLabelHtml") {
    dependsOn("downloadPdfjs")
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.mrcpdf.quality.training.ExportLabelHtml")
    args("--map", "build/synthetic-variant-map.csv",
         "--out", "build/label/index.html")
}

tasks {
    shadowJar {
        archiveBaseName.set("mrcpdf")
        archiveClassifier.set("")
        archiveVersion.set("")
        destinationDirectory.set(layout.buildDirectory)
    }
    build {
        dependsOn(shadowJar)
        dependsOn.removeAll { it.toString().endsWith("check") }
    }
}
