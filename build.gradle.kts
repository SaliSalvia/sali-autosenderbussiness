// Pinned Android toolchain: AGP 8.13.2 + Gradle 8.13 + Kotlin 2.2.21.
// Upgrade these together and require a green CI run before merging.
plugins {
    id("com.android.application") version "8.13.2" apply false
    id("org.jetbrains.kotlin.android") version "2.2.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.21" apply false
    id("org.jetbrains.kotlin.kapt") version "2.2.21" apply false
}
