import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.google.services)
    alias(libs.plugins.firebase.crashlytics)
}

val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties()
if (keystorePropertiesFile.exists()) {
    keystorePropertiesFile.inputStream().use { keystoreProperties.load(it) }
}

val versionPropertiesFile = rootProject.file("version.properties")
val versionProperties = Properties()
check(versionPropertiesFile.exists()) {
    "Missing version.properties (VERSION_CODE / VERSION_NAME). Create it or restore from git."
}
versionPropertiesFile.inputStream().use { versionProperties.load(it) }

val githubReleaseFile = rootProject.file("github-release.properties")
val githubRelease = Properties()
if (githubReleaseFile.exists()) {
    githubReleaseFile.inputStream().use { githubRelease.load(it) }
}
val githubRepository = githubRelease.getProperty("GITHUB_REPOSITORY")?.trim().orEmpty()
check(!githubRepository.contains('"') && !githubRepository.contains('\\')) {
    "GITHUB_REPOSITORY in github-release.properties must not contain quotes or backslashes"
}

android {
    namespace = "com.bestiapop.android"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.bestiapop.android"
        minSdk = 26
        // Play: updates must target API 36+ from 2026-08-31.
        targetSdk = 36
        versionCode = versionProperties.getProperty("VERSION_CODE").toInt()
        versionName = versionProperties.getProperty("VERSION_NAME")
        buildConfigField(
            "String",
            "GITHUB_REPOSITORY",
            "\"$githubRepository\""
        )

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // These tests require a host kill between phases; never discover them in a normal run.
        testInstrumentationRunnerArguments["notAnnotation"] =
            "com.bestiapop.android.persistence.HostOrchestratedProcessDeathTest"
    }

    signingConfigs {
        create("release") {
            if (keystorePropertiesFile.exists()) {
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
            }
        }
    }

    val sharedSigning = if (keystorePropertiesFile.exists()) {
        signingConfigs.getByName("release")
    } else {
        signingConfigs.getByName("debug")
    }

    buildTypes {
        // Same cert as release when keystore.properties exists so debug↔release
        // install -r keeps Room/DataStore (Android rejects a different signature over -k data).
        debug {
            signingConfig = sharedSigning
            // JaCoCo via AGP: unit + instrumented coverage reports for debug.
            enableUnitTestCoverage = true
            enableAndroidTestCoverage = true
        }
        release {
            isMinifyEnabled = true
            isDebuggable = false
            signingConfig = sharedSigning
            ndk {
                // Play: native debug symbols inside the AAB (SYMBOL_TABLE = names, not full DWARF).
                debugSymbolLevel = "SYMBOL_TABLE"
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    // Pin latest JaCoCo (JDK 26 / class file 70). AGP applies it when coverage flags above are on.
    testCoverage {
        jacocoVersion = libs.versions.jacoco.get()
    }
    testOptions {
        // Robolectric/Room tests need merged Android resources; device UI tests need deterministic motion.
        unitTests.isIncludeAndroidResources = true
        animationsDisabled = true
    }
    sourceSets {
        // Shared JVM helpers keep feature scenarios small without leaking test-only dependencies
        // into the instrumented APK.
        getByName("test").kotlin.directories.add("src/sharedTest/java")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        jniLibs {
            // Uncompressed + 16 KB-aligned native libs (Play requirement since 2025-11-01).
            useLegacyPackaging = false
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/INDEX.LIST"
            excludes += "META-INF/io.netty.versions.properties"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

ksp {
    arg("room.generateKotlin", "true")
}

dependencies {
    // Storage DocumentFile
    implementation("androidx.documentfile:documentfile:1.0.1")

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.fragment.ktx)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)

    // Firebase Crashlytics only (no Analytics / advertising ID)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.crashlytics)

    // Media3
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.session)
    implementation(libs.androidx.media3.ui)

    // Room DB
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // DataStore
    implementation(libs.androidx.datastore.preferences)

    // Coil Image loading
    implementation(libs.coil.compose)

    // Ktor embedded web server
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)

    // Networking
    implementation(libs.okhttp)
    implementation(libs.kotlinx.coroutines.android)

    // Audio file tag write (mp3 / m4a / flac / ogg) — minSdk 26 OK with official JAR
    implementation("net.jthink:jaudiotagger:3.0.1")

    // Functional & Integration UI Testing
    testImplementation(libs.junit4)
    testImplementation(libs.json)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.room.testing)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    // Robolectric 4.16.1 is the latest stable release, but its bundled ASM cannot read JDK 26.
    testImplementation(libs.asm)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    androidTestImplementation(libs.junit4)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.okhttp.mockwebserver)
}
