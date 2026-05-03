import com.grappenmaker.conventions.sopsDecrypt

plugins {
    `maven-publish`
    signing
}

repositories {
    mavenCentral()
}

publishing {
    repositories {
        maven {
            name = "Central"
            url = uri("https://ossrh-staging-api.central.sonatype.com/service/local/staging/deploy/maven2/")

            credentials(PasswordCredentials::class) {
                username = sopsDecrypt("maven-central-username")
                password = sopsDecrypt("maven-central-password")
            }
        }
    }
}

signing {
    sign(publishing.publications)
    useGpgCmd()
}

