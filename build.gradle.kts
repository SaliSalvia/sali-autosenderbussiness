// Pinned, mutually compatible toolchain.
//
// WHY THIS IS PINNED: main was broken by independently merged Dependabot majors
// (AGP 9.5.0 + Kotlin 2.4.21 + Gradle 8.9). AGP 9.5 requires Gradle >= 9.6,
// has built-in Kotlin (so kotlin-android / kotlinOptions / kapt are removed),
// and core-ktx 1.19.1 requires compileSdk >= 37. Those three changes cannot be
// merged one-by-one without a coordinated migration.
//
// This stack is the smallest upgrade that satisfies Google Play's API 36
// requirement: AGP 8.9.1 is the first AGP that officially supports compileSdk 36
// and it requires Gradle >= 8.11.1. Everything else stays on the versions that
// already produced a green APK, so the release stays reproducible.
plugins {
    id("com.android.application") version "8.9.1" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
    id("org.jetbrains.kotlin.kapt") version "2.0.21" apply false
}
