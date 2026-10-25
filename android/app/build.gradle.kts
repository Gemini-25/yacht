plugins {
    id("com.android.application")
    kotlin("android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "yacht.app"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "0.2.0"
    }

    flavorDimensions += "app"
    productFlavors {
        // A new id: installs as its own app next to the earlier "요트 AI", which keeps its records.
        create("next") {
            dimension = "app"
            applicationId = "yacht.dice.ai2"
            resValue("string", "app_name", "요트 AI 2")
            resValue("color", "ic_launcher_background", "#1A3A6B")
        }
        // The earlier app's id: installs over "요트 AI" (same key) and keeps its records, so they
        // can be backed up there and opened in "요트 AI 2".
        create("classic") {
            dimension = "app"
            applicationId = "yacht.dice.ai"
            resValue("string", "app_name", "요트 AI")
            resValue("color", "ic_launcher_background", "#1B5E20")
        }
    }

    signingConfigs {
        // Personal sideloading only: a fixed key so new builds install over old ones.
        create("personal") {
            storeFile = file("personal.keystore")
            storePassword = "yachtdice"
            keyAlias = "yacht"
            keyPassword = "yachtdice"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("personal")
        }
        debug {
            signingConfig = signingConfigs.getByName("personal")
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
        resValues = true
    }

    // dist.bin is memory-mapped straight from the APK, so it must stay uncompressed.
    androidResources {
        noCompress += "bin"
    }
}

dependencies {
    implementation(project(":engine"))
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
