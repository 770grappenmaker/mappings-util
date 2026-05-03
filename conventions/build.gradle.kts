plugins {
    `kotlin-dsl`
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(libs.dokka)
    implementation(libs.dokka.javadoc)
    implementation(libs.kotlin.jvm)
}