plugins {
    id("org.jetbrains.kotlin.jvm")
    `java-library`
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    api("org.jsoup:jsoup:1.17.2")
    api("org.json:json:20240303")
    api("com.squareup.okhttp3:okhttp:4.12.0")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
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

tasks.register<JavaExec>("providerTests") {
    group = "verification"
    mainClass.set("dev.datakhoj.core.ProviderTestMain")
    classpath = sourceSets["test"].runtimeClasspath
}
