plugins {
    `java-library`
    id("publishing-base")
    id("kotlin-convention")
    id("dokka-convention")
}

repositories {
    mavenCentral()
}

val dokkaAsJavadoc by tasks.registering(Jar::class) {
    dependsOn(tasks.dokkaGeneratePublicationJavadoc)
    from(tasks.dokkaGeneratePublicationJavadoc.map { it.outputDirectory })
    archiveClassifier.set("javadoc")
}

java { withSourcesJar() }
kotlin { explicitApi() }

publishing {
    publications {
        create<MavenPublication>("central") {
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
                        name = "Koen Oostveen"
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
}
