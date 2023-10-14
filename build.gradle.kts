plugins {
    kotlin("jvm")
    id("org.jetbrains.dokka")
}

repositories {
    mavenCentral()
}

group = "com.grappenmaker"
version = "0.1"

kotlin {
    jvmToolchain(8)
    explicitApi()
}

dependencies {
    api("org.ow2.asm:asm:9.4")
    api("org.ow2.asm:asm-commons:9.4")
}