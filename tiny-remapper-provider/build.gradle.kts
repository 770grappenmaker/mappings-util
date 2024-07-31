plugins {
    id("kotlin-convention")
    id("published-library")
}

repositories {
    maven("https://maven.fabricmc.net/")
}

dependencies {
    api(projects.mappingsUtil)
    api(libs.tiny.remapper) {
        exclude(module = "net.fabricmc:mapping-io")
    }
}