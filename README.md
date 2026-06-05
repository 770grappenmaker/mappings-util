# mappings-util
A small JVM mappings library designed to load, modify, and use mappings, for runtime and pre-runtime remapping.
Several mappings formats are supported, namely:
- Tiny (v1 and v2),
- SRG and XSRG,
- CSRG,
- TSRG (v1 and v2),
- Proguard,
- Recaf,
- Enigma,
- and a custom "compacted" binary format

**Important: this is a Kotlin-first library.** This means that this library was written in Kotlin and with Kotlin in mind. You can use this library in Java as well, albeit probably with a slightly worse experience (extension functions do not exist in Java, for example). Using Kotlin is highly recommended.

**Important**: this library introduced **binary incompatibility** in version 0.2
## Usage
`mappings-util` is on [Maven Central](https://central.sonatype.com/artifact/nl.koenoostveen/mappings-util), 
and can be linked in your project depending on the build system.

### Gradle (Kotlin DSL)
```kotlin
repositories {
    mavenCentral()
}

dependencies {
    implementation("nl.koenoostveen:mappings-util:0.2")
}
```
### Maven
```xml
<dependencies>
    <dependency>
        <groupId>nl.koenoostveen</groupId>
        <artifactId>mappings-util</artifactId>
        <version>0.2</version>
    </dependency>
</dependencies>
```

## Docs
Available on [Forgejo Pages](https://koen.pages.koenoostveen.nl/mappings-util).

## Examples
- Some basic examples on how to transform and handle mappings can be found [here](samples/src/test/kotlin/samples/Mappings.kt)
- Parsing mappings from disk can be done in two ways:
  - Fully buffered:
  ```kt
  MappingsLoader.loadMappings(File("/path/to/some/mappings/file").readLines())
  ```
  - Partially buffered:
  ```kt
  File("/path/to/some/mappings/file").inputStream().use { inp -> MappingsLoader.loadMappings(inp) }
  ```
  The latter is slightly faster and more memory efficient.
- Mappings may be used for remapping through:
```kt
val remapper = MappingsRemapper(
    mappings,
    from = "fromNamespace",
    to = "toNamespace",
    loader = ClasspathLoaders.fromSystemLoader()
)

val reader = ClassReader(bytes)
val writer = ClassWriter(null)
reader.accept(LambdaAwareRemapper(writer, remapper), 0)

// Or remapping a ClassNode
val node = ClassNode()
reader.accept(node)
node.remap(remapper)

// Or for remapping a full jar
remapJar(mappings, inputFile, outputFile, "fromNamespace", "toNamespace")

// Or with the experimental DSL
performRemap {
    copyResources = true
    mappings = File("/path/to/some/mappings/file").inputStream().use { inp ->
        MappingsLoader.loadMappings(inp)
    }
    
    loader = ClasspathLoaders.fromJars(listOf(
        "classpath-a.jar",
        "classpath-b.jar"
    ))

    task(
        input = Path("input.jar"),
        output = Path("output.jar"),
        fromNamespace = "fromNamespace",
        toNamespace = "toNamespace",
    )
}
```
- Writing mappings to disk is easy:
```kt
File("/path/to/some/mappings/file").bufferedWriter().use { mappings.writeLazy().writeTo(it) }
```

## Tools
### Remapper
`remapper` can take JAR files and a mappings file, and produce a remapped JAR file. Example:
```shell
remapper --force -- client.jar client-mapped.jar client.txt official named
```
takes Proguard mappings from `client.txt`, and turns `client.jar` into `client-mapped.jar`, taking names in
"official" to "named".

### Converter
Converts mappings files between formats. Usage:
```
converter <mappings> <format> [output]
where <format> is one of:
  - "tinyv1"
  - "tiny"
  - "srg"
  - "xsrg"
  - "proguard"
  - "tsrg"
  - "tsrg2"
  - "csrg"
  - "enigma"
  - "recaf"

If [output] is missing or -, defaults to stdout
```

## License
Use of this source code is governed by the MIT license, a copy of which can be found [here](LICENSE.md).

## Contributing
PRs are welcome!
