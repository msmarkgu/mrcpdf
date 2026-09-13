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
tasks.register<JavaExec>("trainQualityModel") {
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.mrcpdf.tools.TrainModel")
    args("--generate", "build/quality-corpus.csv",
         "--out", "src/main/resources/quality-model.json",
         "--variant", "image-only", "--docs", "20", "--seed", "42")
}

// Emits build/synthetic-pages.pdf: one labeled preview sheet per synthetic doc
// (all clean/mild/degraded variants) so the training pages can be eyeballed.
tasks.register<JavaExec>("dumpSyntheticPages") {
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.mrcpdf.quality.training.SynthPageDump")
    args("--docs", "20", "--seed", "42", "--out", "build/synthetic-pages.pdf")
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
// variant, same order as synthetic-pages.pdf) plus a page->variant map CSV,
// so a gate-off MRC pass can be labeled and joined back to corpus rows.
tasks.register<JavaExec>("dumpSyntheticSrc") {
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.mrcpdf.quality.training.SynthSrcDump")
    args("--docs", "20", "--seed", "42",
         "--out", "build/synthetic-src.pdf",
         "--map", "build/synthetic-variant-map.csv")
}

// Runs the real MRC pipeline over build/synthetic-src.pdf with the quality
// gate disabled (build/gate-off.jsonc), so every page is compressed both ways
// for labeling.  jbig2enc is currently absent -> CCITT G4 fallback (same
// visuals, larger file).
tasks.register<JavaExec>("mrcSyntheticSrc") {
    dependsOn("dumpSyntheticSrc")
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.mrcpdf.MrcPdf")
    jvmArgs("-Xmx3g")
    args("--settings", "build/gate-off.jsonc", "--threads", "4",
         "build/synthetic-src.pdf", "-o", "build/synthetic-mrc.pdf")
}

// Renders the side-by-side original | MRC labeling doc from the src and MRC
// PDFs (240 pages; page i corresponds to variant-map row i).
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
