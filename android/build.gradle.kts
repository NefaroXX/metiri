// Pinned toolchain (Phase 0 deliverable — see docs/setup.md):
//   AGP 9.1.1  — Kotlin support is BUILT INTO AGP 9.x (no KGP; the
//                org.jetbrains.kotlin.android plugin is rejected by AGP 9)
//   Gradle 9.3.1 (wrapper — see gradle/wrapper/gradle-wrapper.properties)
//   JDK 25 (daemon; Gradle 9.1+ supports Java 25), jvmTarget 17
plugins {
    id("com.android.application") version "9.1.1" apply false
}
