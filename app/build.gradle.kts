// DataKhoj — Copyright (C) 2026 soobujmiah — AGPL-3.0-or-later. See LICENSE.
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

// Signing config is read from keystore.properties, which is gitignored.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "dev.datakhoj.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.datakhoj.app"
        minSdk = 26
        // Target device: Redmi Turbo 4 Pro — Android 15 (API 35), HyperOS 2,
        // Snapdragon 8s Gen 4 (SM8735), Adreno 825, arm64-v8a.
        targetSdk = 35
        versionCode = 2
        versionName = "0.2.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    signingConfigs {
        create("release") {
            if (keystoreProps.getProperty("storeFile") != null) {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            // Suffixed so debug and release can be installed side by side on
            // the same device for comparison.
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    sourceSets["androidTest"].java.srcDir("src/androidTest/kotlin")
    sourceSets["main"].java.srcDir("src/main/kotlin")
    buildFeatures { compose = true }
    // Room exports its schema so migrations can be diffed in review.
    ksp { arg("room.schemaLocation", "$projectDir/schemas") }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.14" }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}

// Dependencies are added when code actually uses them, not in anticipation.
// Deferred until their phase: WorkManager (Phase 2b), navigation (Phase 3),
// documentfile/SAF (Phase 5).
dependencies {
    implementation(project(":core"))

    // Room — added in Phase 2 because app/data now uses it.
    val room = "2.6.1"
    implementation("androidx.room:room-runtime:$room")
    implementation("androidx.room:room-ktx:$room")
    ksp("androidx.room:room-compiler:$room")
    val composeBom = platform("androidx.compose:compose-bom:2024.09.02")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")

    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.room:room-testing:$room")
    androidTestImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
}
