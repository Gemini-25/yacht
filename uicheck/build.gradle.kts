import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.0.21"
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21"
    id("org.jetbrains.compose") version "1.7.3"
    application
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    sourceSets["main"].kotlin.srcDirs(
        "../android/app/src/main/kotlin/yacht/app/core",
        "../android/app/src/main/kotlin/yacht/app/ui",
    )
}

dependencies {
    implementation(project(":engine"))
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.9.0")
}

application {
    mainClass.set("yacht.uicheck.ScreenshotsKt")
    applicationDefaultJvmArgs = listOf("-Xmx2g")
}
