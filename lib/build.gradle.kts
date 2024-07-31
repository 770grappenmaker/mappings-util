plugins {
    id("kotlin-convention")
    id("published-library")
    alias(libs.plugins.dokka)
}

repositories {
    mavenCentral()
}

publishedLibrary {
    enablePackages = true
}

kotlin {
    explicitApi()
}

dependencies {
    api(libs.bundles.asm)
    api(libs.coroutines.core)
    testImplementation(kotlin("test"))
}