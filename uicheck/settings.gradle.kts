// Local check only: compiles the Android app's controller and Compose UI against
// Compose Multiplatform (desktop) without the Android SDK: ./gradlew -p uicheck compileKotlin
// Running the screenshot tool (installDist) also needs Google's Maven repository.
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

rootProject.name = "uicheck"
include(":engine")
project(":engine").projectDir = file("../engine")
