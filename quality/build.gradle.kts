import dev.detekt.gradle.Detekt

plugins {
    // Detekt loads the Kotlin plugin API even in this analysis-only project.
    kotlin("jvm") version "2.4.0" apply false
    id("com.diffplug.spotless") version "8.10.4"
    id("dev.detekt") version "2.0.0-alpha.6"
}

repositories {
    maven("https://maven-central.storage-download.googleapis.com/maven2")
    mavenCentral()
}

val sourceRoot = providers.gradleProperty("analysisRoot").map(::file).getOrElse(rootDir.parentFile)
val reportRoot = providers.gradleProperty("analysisReports").map(::file).getOrElse(layout.buildDirectory.dir("reports").get().asFile)
val sourcePatterns = listOf(
    "app/src/main/kotlin/**/*.kt",
    "canvas/src/**/*.kt",
    "jiosaavn/src/**/*.kt",
    "lastfm/src/**/*.kt",
    "musixmatch/src/**/*.kt",
    "shazamkit/src/**/*.kt",
    "spotifycore/src/**/*.kt",
)
val kotlinSources = fileTree(sourceRoot) { sourcePatterns.forEach { include(it) } }
val formatterRoot = providers.gradleProperty("analysisFormatRoot")
    .map(::file).getOrElse(layout.buildDirectory.dir("format-input").get().asFile)

spotless {
    kotlin {
        // Spotless forbids targets outside its project; Python copies only changed files here.
        target(fileTree(formatterRoot) { include("**/*.kt") })
        ktfmt("0.65").kotlinlangStyle()
    }
}

tasks.register<Detekt>("detektReport") {
    setSource(kotlinSources)
    config.setFrom(rootDir.parentFile.resolve("detekt.yml"))
    buildUponDefaultConfig.set(false)
    basePath.set(sourceRoot.absolutePath)
    providers.gradleProperty("analysisClasspathFile").orNull?.let { manifest ->
        classpath.setFrom(file(manifest).readLines().filter { it.isNotBlank() })
        jvmTarget.set("21")
    }
    // The Python gate compares full reports with the base, without suppressing inherited debt.
    ignoreFailures.set(true)
    reports {
        checkstyle.required.set(true)
        checkstyle.outputLocation.set(reportRoot.resolve("detekt.xml"))
        sarif.required.set(true)
        sarif.outputLocation.set(reportRoot.resolve("detekt.sarif"))
        html.required.set(true)
        html.outputLocation.set(reportRoot.resolve("detekt.html"))
    }
}

val cpd = configurations.create("cpd")
dependencies {
    cpd("net.sourceforge.pmd:pmd-cli:7.28.0")
    cpd("net.sourceforge.pmd:pmd-kotlin:7.28.0")
}

tasks.register<JavaExec>("duplicateCodeReport") {
    classpath = cpd
    mainClass.set("net.sourceforge.pmd.cli.PmdCli")
    val roots = sourcePatterns.map { sourceRoot.resolve(it.substringBefore("/**")) }.filter { it.exists() }
    args("cpd", "--minimum-tokens", "120", "--language", "kotlin", "--format", "xml")
    args("--dir", roots.joinToString(",") { it.absolutePath })
    args("--report-file", reportRoot.resolve("cpd.xml").absolutePath)
    // CPD's violation exit code is handled by the report-comparison gate.
    isIgnoreExitValue = true
    doFirst { reportRoot.mkdirs() }
}
