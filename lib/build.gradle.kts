plugins {
    id("kotlin-convention")
    id("published-library")
    alias(libs.plugins.dokka)
    alias(libs.plugins.ksp)
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

    implementation(projects.relocator)
    ksp(projects.relocator)
}