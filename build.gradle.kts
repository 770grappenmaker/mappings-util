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

dependencies {
    dokka(projects.mappingsUtil)
    dokka(projects.tinyRemapperProvider)
}

tasks {
    val deployDocs by registering(Copy::class) {
        dependsOn(dokkaGeneratePublicationHtml)
        from(dokkaGeneratePublicationHtml.map { it.outputDirectory })
        into(projectDir.resolve("docs"))

        duplicatesStrategy = DuplicatesStrategy.FAIL
    }
}