allprojects {
    group = "com.grappenmaker"
    version = "0.2"
}

plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.dokka)
}

repositories {
    mavenCentral()
}

tasks {
    val docsDir = projectDir.resolve("docs")
    val deployDocs by registering(Copy::class) {
        notCompatibleWithConfigurationCache("indirectly invokes dokka which makes it unsupported")

        dependsOn(dokkaHtmlMultiModule)
        from(dokkaHtmlMultiModule.map { it.outputDirectory })
        into(docsDir)

        duplicatesStrategy = DuplicatesStrategy.FAIL
    }
}