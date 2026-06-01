plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.example.arjun_ai.wear"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.arjun_ai"   // MUST match the phone app
        minSdk = 30                              // Wear OS 3, Galaxy Watch 4+
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
}

dependencies {
    // Compose for Wear OS
    implementation("androidx.compose.ui:ui:1.6.8")
    implementation("androidx.compose.foundation:foundation:1.6.8")
    implementation("androidx.wear.compose:compose-material:1.3.1")
    implementation("androidx.wear.compose:compose-foundation:1.3.1")
    implementation("androidx.activity:activity-compose:1.9.0")

    // FIX: material icons (PlayArrow, Mic, MicOff, Stop)
    implementation("androidx.compose.material:material-icons-extended:1.6.8")

    // Data Layer (watch <-> phone)
    implementation("com.google.android.gms:play-services-wearable:18.2.0")

    // FIX: Task<T>.await() for coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.7.3")

    // Wear OS host library
    implementation("com.google.android.support:wearable:2.9.0")
    compileOnly("com.google.android.wearable:wearable:2.9.0")
}