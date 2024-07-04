# Module mappings-util

A small JVM mappings library designed to load, modify, and use mappings, for runtime and pre-runtime remapping.
Several mappings formats are supported, like SRG, XSRG, Tiny (v1 and v2), Proguard.

## Supported [MappingsFormat][com.grappenmaker.mappings.MappingsFormat]s

| **Name**                                                                     | **Description**                                                                               |
|------------------------------------------------------------------------------|-----------------------------------------------------------------------------------------------|
| [ProguardMappingsFormat][com.grappenmaker.mappings.ProguardMappingsFormat]   | Emitted by [Proguard](https://www.guardsquare.com/proguard) as debug symbols                  |
| [SRGMappingsFormat][com.grappenmaker.mappings.SRGMappingsFormat]             | Searge mappings commonly used in the [ModCoderPack](http://www.modcoderpack.com/)             |
| [XSRGMappingsFormat][com.grappenmaker.mappings.XSRGMappingsFormat]           | An extension of the [SRGMappingsFormat][com.grappenmaker.mappings.SRGMappingsFormat]          |
| [TSRGV1MappingsFormat][com.grappenmaker.mappings.TSRGV1MappingsFormat]       | A variation on the [SRGMappingsFormat][com.grappenmaker.mappings.SRGMappingsFormat]           |
| [TSRGV2MappingsFormat][com.grappenmaker.mappings.TSRGV2MappingsFormat]       | A variation on the [SRGMappingsFormat][com.grappenmaker.mappings.SRGMappingsFormat]           |
| [TinyMappingsV1Format][com.grappenmaker.mappings.TinyMappingsV1Format]       | An obsolete version of Tiny mappings, popularized by [yarn](https://github.com/FabricMC/yarn) |
| [TinyMappingsV2Format][com.grappenmaker.mappings.TinyMappingsV2Format]       | Tiny mappings, popularized by [yarn](https://github.com/FabricMC/yarn)                        |
| [CompactedMappingsFormat][com.grappenmaker.mappings.CompactedMappingsFormat] | An experimental mappings format inspired by Tiny, which compresses the format slightly        |

## Loading (mappings) files

| **Type**                                                               | **Snippet**                                                                              |
|------------------------------------------------------------------------|------------------------------------------------------------------------------------------|
| Any type of [MappingsFormat][com.grappenmaker.mappings.MappingsFormat] | [MappingsLoader.loadMappings][com.grappenmaker.mappings.MappingsLoader.loadMappings]     |
| An [AccessWidener][com.grappenmaker.mappings.AccessWidener]            | [loadAccessWidener][com.grappenmaker.mappings.loadAccessWidener]                         |
| [CompactedMappings][com.grappenmaker.mappings.CompactedMappings]       | [CompactedMappingsFormat.parse][com.grappenmaker.mappings.CompactedMappingsFormat.parse] |

## Writing (mappings) files

| **Type**                                                               | **Snippet**                                                                  |
|------------------------------------------------------------------------|------------------------------------------------------------------------------|
| Any type of [MappingsFormat][com.grappenmaker.mappings.MappingsFormat] | [Mappings.write][com.grappenmaker.mappings.write]                            |
| An [AccessWidener][com.grappenmaker.mappings.AccessWidener]            | [AccessWidener.write][com.grappenmaker.mappings.write]                       |
| [CompactedMappings][com.grappenmaker.mappings.CompactedMappings]       | [CompactedMappings.write][com.grappenmaker.mappings.write]                   |

## Common [Mappings][com.grappenmaker.mappings.Mappings] operations

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

// Or for remapping a full jar
remapJar(mappings, inputFile, outputFile, "fromNamespace", "toNamespace")

// Transforming mappings
val extracted = mappings.extractNamespaces("newFrom", "newTo")
val renamed = mappings.renameNamespaces("newFirst", "newSecond", "newThird")
val reordered = mappings.reorderNamespaces("c", "b", "a")
val joined = mappings.join("fromA", otherMappings, "fromB", "intermediary")
val filtered = mappings.filterNamespaces("b", "c")
val tinyMappings = mappings.asTinyMappings(v2 = true)

// Writing mappings
File("/path/to/some/mappings/file").writeText(tinyMappings.write().joinToString("\n"))
```

## Common [AccessWidener][com.grappenmaker.mappings.AccessWidener] operations
```kt
// Parsing
val lines = File("/path/to/some/file.accesswidener").readText().trim().lines()
val aw = loadAccessWidener(lines)

// Remapping to a different namespace
aw.remap(mappings, "newNamespace")
aw.remap(remapper, "newNamespace")

// Converting to a tree
val tree = aw.toTree()

// Applying to a Class file
val reader = ClassReader(bytes)
val writer = ClassWriter(reader)
reader.accept(AccessWidenerVisitor(writer, tree), 0)

// Applying to a ClassNode
val node = ClassNode()
reader.accept(node)
node.applyWidener(tree)

// Combining to create a "joined" widener
val joined = aw + otherAW

// Works on Sequences and Iterables
val joined = listOf(aw, otherAW, yetAnotherAW).join()

// Writing (unsupported for trees)
aw.write()
```