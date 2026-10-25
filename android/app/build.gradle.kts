plugins {
    id("com.android.application")
    kotlin("android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "yacht.app"
    compileSdk = 35

    defaultConfig {
        // A new id: installs as its own app next to the earlier "요트 AI" (yacht.dice.ai), which keeps its records.
        applicationId = "yacht.dice.ai2"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "0.2.0"
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
