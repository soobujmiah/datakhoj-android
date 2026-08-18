// DataKhoj — Copyright (C) 2026 soobujmiah — AGPL-3.0-or-later. See LICENSE.
plugins {
    id("org.jetbrains.kotlin.jvm")
    `java-library`
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

// Without this, Kotlin defaults to jvmTarget 1.8 and the build fails with
// "Inconsistent JVM-target compatibility" against Java 17.
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    kotlinOptions.jvmTarget = "17"
}

dependencies {
    api("org.jsoup:jsoup:1.17.2")
    // org.json is part of the Android platform — compile against it, never bundle it.
    compileOnly("org.json:json:20240303")
    api("com.squareup.okhttp3:okhttp:4.12.0")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
    testImplementation("org.json:json:20240303")
    testImplementation("org.jetbrains.kotlin:kotlin-test")
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
}

tasks.test {
    useJUnitPlatform()
    systemProperty("conformance.dir", "${rootDir}/spec/conformance/cases")
    testLogging { events("passed", "failed", "skipped") }
}

/** Runs the cross-engine conformance corpus and writes kotlin-results.json. */
tasks.register<JavaExec>("conformance") {
    group = "verification"
    description = "Verify this engine against the shared spec/conformance corpus."
    mainClass.set("dev.datakhoj.core.ConformanceMain")
    classpath = sourceSets["test"].runtimeClasspath
    args("${rootDir}/spec/conformance/cases")
}

tasks.register<JavaExec>("intentTests") {
    group = "verification"
    description = "Smart-search intent parser tests (offline)."
    mainClass.set("dev.datakhoj.core.IntentTestMain")
    classpath = sourceSets["test"].runtimeClasspath
}

tasks.register<JavaExec>("datasetTests") {
    group = "verification"
    description = "Dataset, transform, dedup and export tests."
    mainClass.set("dev.datakhoj.core.DatasetTestMain")
    classpath = sourceSets["test"].runtimeClasspath
}

tasks.register<JavaExec>("aiTests") {
    group = "verification"
    description = "On-device AI layer: fallback, resilience, LLM output safety."
    mainClass.set("dev.datakhoj.core.AiTestMain")
    classpath = sourceSets["test"].runtimeClasspath
}

tasks.register<JavaExec>("providerTests") {
    group = "verification"
    mainClass.set("dev.datakhoj.core.ProviderTestMain")
    classpath = sourceSets["test"].runtimeClasspath
}
