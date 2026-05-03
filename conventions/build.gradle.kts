plugins {
    `kotlin-dsl`
}

group = "nl.koenoostveen"

repositories {
    mavenCentral()
}

dependencies {
    implementation(libs.dokka)
    implementation(libs.dokka.javadoc)
    implementation(libs.kotlin.jvm)
}