import org.jetbrains.dokka.gradle.DokkaTask
import java.net.URL

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.dokka)
    `maven-publish`
    `java-library`
    signing
}

repositories {
    mavenCentral()
}

group = "com.grappenmaker"
version = "0.1.7"

kotlin {
    jvmToolchain(8)
    explicitApi()
    compilerOptions { freeCompilerArgs.add("-Xcontext-receivers") }
}

val dokkaJavadoc by tasks.getting(DokkaTask::class)
val dokkaAsJavadoc by tasks.registering(Jar::class) {
    dependsOn(dokkaJavadoc)
    from(dokkaJavadoc.outputDirectory)
    archiveClassifier.set("javadoc")
}

java {
    withSourcesJar()
}

dependencies {
    api(libs.bundles.asm)
    api(libs.coroutines.core)
    testImplementation(kotlin("test"))
}

publishing {
    publications {
        fun MavenPublication.setup() {
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

        create<MavenPublication>("central") {
            groupId = "io.github.770grappenmaker"
            setup()
        }

        create<MavenPublication>("packages", MavenPublication::setup)
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
    test {
        useJUnitPlatform()
        testLogging {
            events("passed")
        }
    }

    withType<DokkaTask>().configureEach {
        dokkaSourceSets.configureEach {
            val test by project(":samples").sourceSets.getting
            samples.from(test.kotlin)
            reportUndocumented = true

            sourceLink {
                localDirectory = rootDir
                remoteUrl = URL("https://github.com/770grappenmaker/mappings-util/tree/main")
                remoteLineSuffix = "#L"
            }

            includes.from("dokka-module.md")

            externalDocumentationLink {
                url = URL("https://asm.ow2.io/javadoc/")
                packageListUrl = project.layout.projectDirectory.file("asm.package-list").asFile.toURI().toURL()
            }
        }
    }

    val dokkaHtml by getting
    val deployDocs by registering(Copy::class) {
        dependsOn(dokkaHtml)
        from(dokkaHtml)
        into(projectDir.resolve("docs"))
    }
}