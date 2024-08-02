plugins {
    id("kotlin-convention")
    application
}

dependencies {
    implementation(libs.ksp.api)
    implementation(libs.poet)
    implementation(libs.poet.ksp)
}
