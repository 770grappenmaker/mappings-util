plugins {
    id("org.jetbrains.kotlin.jvm")
}

repositories {
    mavenCentral()
}

kotlin {
    jvmToolchain(8)
    compilerOptions { freeCompilerArgs.add("-Xcontext-receivers") }
}