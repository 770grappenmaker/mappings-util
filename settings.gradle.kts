rootProject.name = "mappings-util"

pluginManagement {
    plugins {
        kotlin("jvm") version "1.9.23"
        id("org.jetbrains.dokka") version "1.9.10"
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}