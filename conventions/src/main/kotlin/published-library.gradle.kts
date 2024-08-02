import org.jetbrains.dokka.gradle.DokkaTask
import org.jetbrains.dokka.gradle.DokkaTaskPartial
import java.net.URI

plugins {
    `maven-publish`
    `java-library`
    signing
    id("kotlin-convention")
    id("org.jetbrains.dokka")
}

repositories {
    mavenCentral()
}

val dokkaJavadoc by tasks.getting(DokkaTask::class)
val dokkaAsJavadoc by tasks.registering(Jar::class) {
    dependsOn(dokkaJavadoc)
    from(dokkaJavadoc.outputDirectory)
    archiveClassifier.set("javadoc")
}

java { withSourcesJar() }
kotlin { explicitApi() }

publishing {
    publications {
        create<MavenPublication>("central") {
            groupId = "io.github.770grappenmaker"

            artifact(dokkaAsJavadoc)
            from(components["java"])

            pom {
                name = project.name
                description = "A library for handling and using JVM name mappings (SRG, Tiny, Proguard)"
                url = "https://github.com/770grappenmaker/mappings-util"
                packaging = "jar"

                licenses {
                    license {
                        name = "The MIT License"
                        url = "https://opensource.org/license/mit/"
                        distribution = "repo"
                    }
                }

                developers {
                    developer {
                        id = "770grappenmaker"
                        name = "NotEvenJoking"
                        email = "770grappenmaker@gmail.com"
                        url = "https://github.com/770grappenmaker/"
                    }
                }

                scm {
                    connection = "scm:git:git://github.com/770grappenmaker/mappings-util.git"
                    developerConnection = "scm:git:ssh://github.com:770grappenmaker/mappings-util.git"
                    url = "https://github.com/770grappenmaker/mappings-util/tree/main"
                }
            }
        }
    }

    repositories {
        maven {
            name = "Central"
            url = uri("https://s01.oss.sonatype.org/service/local/staging/deploy/maven2/")
            credentials(PasswordCredentials::class)
        }
    }
}

signing {
    sign(publishing.publications)
}

tasks {
    withType<DokkaTaskPartial>().configureEach {
        dokkaSourceSets.configureEach {
            val test by project(":samples").sourceSets.getting
            samples.from(test.extensions.getByName("kotlin"))
            reportUndocumented = true

            sourceLink {
                localDirectory = rootDir
                remoteUrl = URI("https://github.com/770grappenmaker/mappings-util/tree/main").toURL()
                remoteLineSuffix = "#L"
            }

            includes.from("dokka-module.md")

            externalDocumentationLink {
                url = URI("https://asm.ow2.io/javadoc/").toURL()
                packageListUrl = rootProject.layout.projectDirectory.file("asm.package-list").asFile.toURI().toURL()
            }
        }
    }

    named("sourcesJar", Jar::class) { exclude { "generated" in it.file.absolutePath } }
}