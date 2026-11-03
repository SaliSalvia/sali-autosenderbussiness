plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.kapt")
}

android {
    namespace = "ir.salisec.autosender"
    // Google Play requires API 36 for new apps and updates since 2026-08-31.
    compileSdk = 36
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "ir.salisec.autosender"
        minSdk = 24
        targetSdk = 36
        versionCode = 3
        versionName = "2.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            // R8 is on so the release artifact is a real, shrunk build. Conservative
            // keep rules protect JavaMail / Room / PDFBox reflection (proguard-rules.pro).
            isMinifyEnabled = true
            isShrinkResources = true
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

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    // Lint runs in CI as a report; warnings must not silently turn a green build red.
    lint {
        abortOnError = false
        checkReleaseBuilds = false
        warningsAsErrors = false
    }

    packaging {
        resources.excludes += setOf(
            "META-INF/LICENSE*", "META-INF/NOTICE*", "META-INF/DEPENDENCIES"
        )
    }
}

dependencies {
    // Pinned to the set that already produced a green APK. Major upgrades are
    // intentionally not auto-merged: see root build.gradle.kts and dependabot.yml.
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.activity:activity-compose:1.9.3")

    val composeBom = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")

    implementation("androidx.room:room-runtime:2.8.5")
    implementation("androidx.room:room-ktx:2.8.5")
    kapt("androidx.room:room-compiler:2.8.5")

    implementation("androidx.datastore:datastore-preferences:1.3.0")
    implementation("androidx.security:security-crypto:1.1.0")
    implementation("androidx.documentfile:documentfile:1.1.0")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    implementation("com.sun.mail:android-mail:1.6.8")
    implementation("com.sun.mail:android-activation:1.6.8")

    implementation("com.tom-roush:pdfbox-android:2.0.27.0")

    // Pure-JVM unit tests for domain logic. No emulator needed in CI.
    testImplementation("junit:junit:4.13.2")
}
