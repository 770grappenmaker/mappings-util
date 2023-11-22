plugins {
    kotlin("jvm")
    id("org.jetbrains.dokka")
    `maven-publish`
}

repositories {
    mavenCentral()
}

group = "com.grappenmaker"
version = "0.1"

java {
    withSourcesJar()
    withJavadocJar()
}

kotlin {
    jvmToolchain(8)
    explicitApi()
}

dependencies {
    api("org.ow2.asm:asm:9.4")
    api("org.ow2.asm:asm-commons:9.4")
}

publishing {
    publications {
        create<MavenPublication>("lib") { from(components["java"]) }
    }

    repositories {
        maven {
            name = "Packages"
            url = uri("https://maven.pkg.github.com/770grappenmaker/mappings-util")

            credentials {
                username = System.getenv("GITHUB_ACTOR")
                password = System.getenv("GITHUB_TOKEN")
            }
        }
    }
}