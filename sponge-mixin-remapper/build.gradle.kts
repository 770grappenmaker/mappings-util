plugins {
    id("kotlin-convention")
    id("dokka-convention")
}

dependencies {
    api(projects.mappingsUtil)
    api(libs.mixin)
}