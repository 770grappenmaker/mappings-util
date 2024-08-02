plugins {
    `kotlin-dsl`
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(libs.dokka)
    implementation(libs.ksp)
    implementation(libs.kotlin.jvm)
    implementation(libs.kotlin.metadata)
    implementation(libs.asm)
    implementation(libs.asm.commons)
}