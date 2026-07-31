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
    maxHeapSize = "2g"
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
