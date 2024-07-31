enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

rootProject.name = "mappings-util-root"

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}

include("samples", "remapper", "mappings-util", "tiny-remapper-provider")
project(":mappings-util").projectDir = file("lib")
includeBuild("conventions")