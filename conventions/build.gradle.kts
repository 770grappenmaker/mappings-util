plugins {
    `kotlin-dsl`
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(libs.dokka)
    implementation(libs.kotlin.jvm)
}