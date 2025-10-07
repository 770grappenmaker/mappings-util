plugins {
    id("kotlin-convention")
    id("published-library")
}

dependencies {
    api(projects.mappingsUtil)
    api(libs.mixin)
}