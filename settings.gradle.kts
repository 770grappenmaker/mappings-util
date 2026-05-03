enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

rootProject.name = "mappings-util-root"

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

include(
    "samples",
    "remapper",
    "converter",
    "mappings-util",
    "tiny-remapper-provider",
    "sponge-mixin-remapper"
)

project(":mappings-util").projectDir = file("lib")
includeBuild("conventions")