plugins {
    id("kotlin-convention")
}

dependencies {
    testImplementation(projects.mappingsUtil)
    testImplementation(kotlin("test"))
}

// TODO: do we want samples to be tests as well?
// TODO: they serve a different purpose and are more docs-friendly
tasks {
    test {
        useJUnitPlatform()
        testLogging {
            events("passed")
        }
    }
}