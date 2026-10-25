// Headless stress test of the app's controllers (no Compose UI, no Android SDK):
// ./gradlew -p stress run --args="<tables dir> <minutes>"
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

rootProject.name = "stress"
include(":engine")
project(":engine").projectDir = file("../engine")
