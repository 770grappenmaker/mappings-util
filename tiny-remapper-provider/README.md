# tiny-remapper-provider

Utility for using this mappings library with [tiny-remapper](https://github.com/FabricMC/tiny-remapper),
instead of the remapper provided in this library, since it is considered more versatile and extensible.

tiny-remapper is licensed under GNU LGPL v3.0, a copy of which is present in this directory. Usage of
tiny-remapper is governed by its license. This library is not affiliated with the tiny-remapper contributors.

I am not a lawyer :)

## Usage
This library is also on Maven Central and is versioned similarly to the main library, however, it does not have a
transitive dependency on `tiny-remapper`, since Maven Central only allows artifacts with dependencies on other artifacts
published to Maven Central. Therefore, you have to declare the repositories and dependencies yourself:
```kotlin
repositories {
    mavenCentral()
    maven("https://maven.fabricmc.net/")
}

dependencies {
    implementation("io.github.770grappenmaker:tiny-remapper-provider:0.2")
    implementation("net.fabricmc:tiny-remapper:0.9.0")
}
```