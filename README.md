# mappings-util
A small JVM mappings library designed to load, modify, and use mappings, for runtime and pre-runtime remapping.
Several mappings formats are supported, like SRG, XSRG, Tiny (v1 and v2), Proguard.

**Important: this is a Kotlin-first library.** This means that this library was written in Kotlin and with Kotlin in mind. You can use this library in Java as well, albeit probably with a slightly worse experience (extension functions do not exist in Java, for example). Using Kotlin is highly recommended.

### Usage
`mappings-util` is on [Maven Central](https://central.sonatype.com/artifact/io.github.770grappenmaker/mappings-util), 
and can be linked in your project depending on the build system.

#### Gradle (Kotlin DSL)
```kotlin
repositories {
    mavenCentral()
}

dependencies {
    implementation("io.github.770grappenmaker:mappings-util:0.1.7")
}
```
#### Maven
```xml
<dependencies>
    <dependency>
        <groupId>io.github.770grappenmaker</groupId>
        <artifactId>mappings-util</artifactId>
        <version>0.1.7</version>
    </dependency>
</dependencies>
```

### Docs
Available in [GitHub Pages](https://770grappenmaker.github.io/mappings-util/).

### Examples
Note: these examples are in Kotlin, but can be applied in Java as well.
```kt
// Parsing mappings
val lines = File("/path/to/some/mappings/file").readLines()

// The mappings format is automatically detected
val mappings = MappingsLoader.loadMappings(lines)

// Using mappings
val remapper = MappingsRemapper(
    mappings,
    from = "fromNamespace",
    to = "toNamespace",
    loader = ClasspathLoaders.fromSystemLoader()
)

val reader = ClassReader(bytes)
val writer = ClassWriter(reader)
reader.accept(LambdaAwareRemapper(writer, remapper), 0)

// Or remapping a ClassNode
val node = ClassNode()
reader.accept(node)
node.remap(remapper)

// Or for remapping a full jar
remapJar(mappings, inputFile, outputFile, "fromNamespace", "toNamespace")

// Transforming mappings
val extracted = mappings.extractNamespaces("newFrom", "newTo")
val renamed = mappings.renameNamespaces("newFirst", "newSecond", "newThird")
val reordered = mappings.reorderNamespaces("c", "b", "a")
val joined = mappings.join(otherMappings, "intermediary")
val filtered = mappings.filterNamespaces("b", "c")
val tinyMappings = mappings.asTinyMappings(v2 = true)

// Writing mappings
File("/path/to/some/mappings/file").writeText(tinyMappings.write().joinToString("\n"))
```

### Building
```shell
./gradlew build
```

### License
Use of this source code is governed by the MIT license, a copy of which can be found [here](LICENSE.md).

### Contributing
PRs are welcome!