plugins {
    id("kotlin-convention")
    id("published-library")
}

repositories {
    maven("https://maven.fabricmc.net/")
}

dependencies {
    api(projects.mappingsUtil)
    compileOnly(libs.tiny.remapper) {
        exclude(group = "net.fabricmc", module = "mapping-io")
    }
}