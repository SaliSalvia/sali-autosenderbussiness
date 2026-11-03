// Pinned, mutually compatible toolchain.
//
// WHY THIS IS PINNED: main was broken by independently merged Dependabot majors
// (AGP 9.5.0 + Kotlin 2.4.21 + Gradle 8.9). AGP 9.5 requires Gradle >= 9.6,
// has built-in Kotlin (so kotlin-android / kotlinOptions / kapt are removed),
// and core-ktx 1.19.1 requires compileSdk >= 37. Those three changes cannot be
// merged one-by-one without a coordinated migration.
//
// This stack is the smallest upgrade that satisfies Google Play's API 36
// requirement: AGP 8.13.2 supports compileSdk 36 and requires Gradle >= 8.13
// (pinned to 8.13 in gradle-wrapper.properties). Kotlin Gradle Plugin 2.4.21
// fully supports Gradle 7.6.3-9.7.0, so the release stays reproducible.
plugins {
    id("com.android.application") version "8.13.2" apply false
    id("org.jetbrains.kotlin.android") version "2.4.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21" apply false
    id("org.jetbrains.kotlin.kapt") version "2.4.21" apply false
}
