import java.net.URI

plugins {
    id("org.jetbrains.dokka")
    id("org.jetbrains.dokka-javadoc")
}

dokka {
    dokkaSourceSets.configureEach {
        val test by project(":samples").the<SourceSetContainer>().getting
        samples.from(test.extensions.getByName("kotlin"))
        reportUndocumented = true

        sourceLink {
            localDirectory = rootDir
            remoteUrl = URI("https://github.com/770grappenmaker/mappings-util/tree/main")
            remoteLineSuffix = "#L"
        }

        includes.from("dokka-module.md")

        externalDocumentationLinks.register("asm") {
            url = URI("https://asm.ow2.io/javadoc/")
            packageListUrl = rootProject.layout.projectDirectory.file("asm.package-list").asFile.toURI()
        }
    }
}
