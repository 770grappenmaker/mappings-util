import com.grappenmaker.conventions.sopsDecrypt
import java.net.HttpURLConnection
import java.net.URI

buildscript {
    dependencies {
        classpath("nl.koenoostveen:conventions")
    }
}

allprojects {
    group = "nl.koenoostveen"
    version = "0.2"
}

plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.dokka)
    id("publishing-base")
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

    val publishToOSSRH by registering {
        notCompatibleWithConfigurationCache("Uses URLConnection")
        group = "publishing"

        subprojects {
            dependsOn(tasks.matching { it.name == "publishAllPublicationsToCentralRepository" })
        }

        doLast {
            val uri = URI("https://ossrh-staging-api.central.sonatype.com/manual/upload/defaultRepository/${project.group}")
            with(uri.toURL().openConnection() as HttpURLConnection) {
                requestMethod = "POST"
                setRequestProperty("Authorization", "Bearer ${sopsDecrypt("maven-central-token")}")

                doInput = true
                connect()

                if (responseCode / 100 != 2) throw GradleException("Failed to request manual upload at OSSRH: ${
                    getInputStream().readBytes().decodeToString()
                }")
            }
        }
    }
}