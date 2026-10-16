import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm")
    application
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation(project(":engine"))
}

application {
    mainClass.set("yacht.tools.MainKt")
    applicationDefaultJvmArgs = listOf("-Xmx6g", "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8")
}
