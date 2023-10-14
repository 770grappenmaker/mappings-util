rootProject.name = "mappings-util"

pluginManagement {
    plugins {
        kotlin("jvm") version "1.9.10"
        id("org.jetbrains.dokka") version "1.9.0"
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.7.0"
}