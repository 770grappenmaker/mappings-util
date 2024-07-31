dependencyResolutionManagement {
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}

rootProject.name = "conventions"

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}