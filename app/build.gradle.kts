import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// Release signing is driven by a git-ignored keystore.properties in the project root
// (storeFile / storePassword / keyAlias / keyPassword). When it is absent — local debug
// work, CI without secrets — the release build silently falls back to debug signing, so
// the module still assembles. The permanent release keystore must exist before the first
// field rollout: updates only install over a same-key build.
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}

android {
    namespace = "com.mediplus.spapp"
    // compileSdk 37: modern AndroidX (core 1.19, activity 1.11, compose 2025.10) require it.
    // targetSdk stays 36 (runtime-behavior opt-in per plan); compileSdk >= targetSdk is standard.
    compileSdk {
        version = release(37) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.mediplus.spapp"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "com.mediplus.spapp.HiltTestRunner"

        // Configurable back-office base URL. Overridable per build type (e.g. MockWebServer in CI).
        // Must end in the API version prefix the spec's server URL carries — every Retrofit path is
        // relative to it, and a trailing slash is what keeps the last segment from being dropped.
        buildConfigField("String", "BASE_URL", "\"https://10.21.2.82:8080/api/v1/\"")
    }

    signingConfigs {
        if (keystorePropsFile.exists()) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            // Local Docker back office on the LAN (plain HTTP; see network_security_config.xml).
            buildConfigField("String", "BASE_URL", "\"http://10.21.2.82:8080/api/v1/\"")
            enableUnitTestCoverage = true
        }
        release {
            optimization {
                enable = false
            }
            // Sign with the permanent release key when keystore.properties is present;
            // otherwise this stays on the default debug signing config.
            if (keystorePropsFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
        isCoreLibraryDesugaringEnabled = true
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }

    testOptions {
        unitTests {
            isReturnDefaultValues = true
            isIncludeAndroidResources = true
        }
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/*.kotlin_module",
            )
        }
    }

    @Suppress("UnstableApiUsage")
    lint {
        // Fail the build on lint errors; warnings are surfaced but do not abort day-to-day builds.
        abortOnError = true
        checkDependencies = true
        // Third-party privacy-sensitive libs (JMRTD/BouncyCastle) ship benign warnings we don't own.
        disable += setOf("ObsoleteLintCustomCheck")
    }
}

dependencies {
    // Compose BOM governs all Compose artifact versions.
    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    androidTestImplementation(composeBom)

    // AndroidX / lifecycle / activity
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.activity.compose)

    // Compose UI + Material 3
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    // Navigation
    implementation(libs.androidx.navigation.compose)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.core)

    // Serialization + networking
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.retrofit)
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging.interceptor)

    // CameraX
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)

    // ML Kit (on-device capture guidance + MRZ OCR)
    implementation(libs.mlkit.face.detection)
    implementation(libs.mlkit.text.recognition)

    // Storage (non-sensitive prefs only)
    implementation(libs.androidx.datastore.preferences)

    // java.time on minSdk 24
    coreLibraryDesugaring(libs.desugar.jdk.libs)

    // Leak detection (debug only)
    debugImplementation(libs.leakcanary.android)

    // Unit tests
    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.turbine)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.retrofit.kotlinx.serialization)

    // Instrumented tests
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.hilt.android.testing)
    kspAndroidTest(libs.hilt.compiler)
}
