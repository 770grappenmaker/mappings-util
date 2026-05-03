plugins {
    id("kotlin-convention")
    id("dokka-convention")
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