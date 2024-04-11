import org.jetbrains.dokka.gradle.DokkaTask

plugins {
    kotlin("jvm")
    id("org.jetbrains.dokka")
    `maven-publish`
    `java-library`
    signing
}

repositories {
    mavenCentral()
}

group = "com.grappenmaker"
version = "0.1.4"

kotlin {
    jvmToolchain(8)
    explicitApi()
}

val dokkaHtml by tasks.getting(DokkaTask::class)
val dokkaAsJavadoc by tasks.registering(Jar::class) {
    dependsOn(dokkaHtml)
    from(dokkaHtml.outputDirectory)
    archiveClassifier.set("javadoc")
}

java {
    withSourcesJar()
}

dependencies {
    api("org.ow2.asm:asm:9.6")
    api("org.ow2.asm:asm-commons:9.6")
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