plugins {
    id("com.android.application")
}

android {
    namespace = "com.metiri.armeasure"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.metiri.armeasure"
        minSdk = 24        // ARCore "AR Required" floor — verified, see docs/setup.md
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    // Built-in Kotlin (AGP 9): jvmTarget defaults to compileOptions.targetCompatibility
}

dependencies {
    implementation("androidx.core:core-ktx:1.18.0")
    // Required by the UniFFI-generated Kotlin bindings (JNA backend) in uniffi/core/core.kt
    // UniFFI 0.32 generated Kotlin bindings run on the JNA backend. Android
    // needs the AAR variant (@aar) — it carries libjnidispatch.so per-ABI;
    // the plain jar has no Android natives and crashes at runtime with
    // UnsatisfiedLinkError. (caught by Gate 0.3, 2026-08-16)
    implementation("net.java.dev.jna:jna:5.19.1@aar")

    // Phase 2: ARCore SDK (pinned). Emulator testing via Google Play Services
    // for AR x86 APK sideloaded onto the AVD (see docs/setup.md).
    implementation("com.google.ar:core:1.53.0")

    // Gate 2.1: pure-JVM unit tests for capability-decision logic (no Robolectric
    // needed — the evaluator is Android-free by design).
    testImplementation("junit:junit:4.13.2")

    androidTestImplementation("androidx.test:core:1.7.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
}
