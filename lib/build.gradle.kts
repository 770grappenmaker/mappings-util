plugins {
    id("kotlin-convention")
    id("published-library")
    id("relocator-convention")
    alias(libs.plugins.dokka)
}

repositories {
    mavenCentral()
}

kotlin {
    explicitApi()
    compilerOptions { optIn.add("com.grappenmaker.mappings.remap.ExperimentalJarRemapper") }
}

dependencies {
    api(libs.bundles.asm)
    api(libs.coroutines.core)
    testImplementation(kotlin("test"))
}