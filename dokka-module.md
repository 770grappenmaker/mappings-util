# Module mappings-util

A small JVM mappings library designed to load, modify, and use mappings, for runtime and pre-runtime remapping.
Several mappings formats are supported, like SRG, XSRG, Tiny (v1 and v2), Proguard.

**Important:** some documentation entries will have seemingly runnable samples. They are, in fact, not runnable.
This is a known Dokka issue which will be addressed in a future release of Dokka. See [this issue](https://github.com/Kotlin/dokka/issues/3041).

## Supported [MappingsFormat][com.grappenmaker.mappings.MappingsFormat]s

| **Name**                                                                     | **Description**                                                                                                                                    |
|------------------------------------------------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------|
| [ProguardMappingsFormat][com.grappenmaker.mappings.ProguardMappingsFormat]   | Emitted by [Proguard](https://www.guardsquare.com/proguard) as debug symbols                                                                       |
| [SRGMappingsFormat][com.grappenmaker.mappings.SRGMappingsFormat]             | Searge mappings commonly used in the [ModCoderPack](http://www.modcoderpack.com/)                                                                  |
| [XSRGMappingsFormat][com.grappenmaker.mappings.XSRGMappingsFormat]           | An extension of the [SRGMappingsFormat][com.grappenmaker.mappings.SRGMappingsFormat]                                                               |
| [CSRGMappingsFormat][com.grappenmaker.mappings.CSRGMappingsFormat]           | A simplification of the [SRGMappingsFormat][com.grappenmaker.mappings.SRGMappingsFormat]                                                           |
| [TSRGV1MappingsFormat][com.grappenmaker.mappings.TSRGV1MappingsFormat]       | A variation on the [SRGMappingsFormat][com.grappenmaker.mappings.SRGMappingsFormat]                                                                |
| [TSRGV2MappingsFormat][com.grappenmaker.mappings.TSRGV2MappingsFormat]       | A variation on the [SRGMappingsFormat][com.grappenmaker.mappings.SRGMappingsFormat]                                                                |
| [TinyMappingsV1Format][com.grappenmaker.mappings.TinyMappingsV1Format]       | An obsolete version of Tiny mappings, popularized by [yarn](https://github.com/FabricMC/yarn)                                                      |
| [TinyMappingsV2Format][com.grappenmaker.mappings.TinyMappingsV2Format]       | Tiny mappings, popularized by [yarn](https://github.com/FabricMC/yarn)                                                                             |
| [EnigmaMappingsFormat][com.grappenmaker.mappings.EnigmaMappingsFormat]       | Enigma mappings, used in [Enigma](https://github.com/FabricMC/Enigma)                                                                              |
| [CompactedMappingsFormat][com.grappenmaker.mappings.CompactedMappingsFormat] | An experimental mappings format inspired by Tiny, which compresses the format slightly (its docs are in [here][com.grappenmaker.mappings.compact]) |

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

## Mappings transformations

| **Name**                                                                          | **Description**                                                           |
|-----------------------------------------------------------------------------------|---------------------------------------------------------------------------|
| [Mappings.renameNamespaces][com.grappenmaker.mappings.renameNamespaces]           | Renames namespaces                                                        |
| [Mappings.reorderNamespaces][com.grappenmaker.mappings.reorderNamespaces]         | Reorders/duplicates namespaces                                            |
| [Mappings.join][com.grappenmaker.mappings.join]                                   | Joins two [Mappings][com.grappenmaker.mappings.Mappings] objects together |
| [Mappings.filterNamespaces][com.grappenmaker.mappings.filterNamespaces]           | Filters certain namespaces by a set of allowed names, or a predicate      |
| [Mappings.deduplicateNamespaces][com.grappenmaker.mappings.deduplicateNamespaces] | Removes duplicate namespaces                                              |


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

## Compacted mappings specification ([CompactedMappingsFormat][com.grappenmaker.mappings.CompactedMappingsFormat])
Conventions:
- arrays are denoted `name[]` and are stored with an integral data type before it to indicate the amount of entries in the array
- string are encoded by first storing its length as a single byte, followed by the utf-8 representation
- varints are used, see [wiki.vg](https://wiki.vg/Data_types#VarInt_and_VarLong)
- descriptors are encoded like strings but without its length, since that can be deduced by reading the descriptor bit by bit. The following replacements are made (to save space):
  - `Ljava/lang/Object;` -> `A`
  - `Ljava/lang/String;` -> `G`
  - `Ljava/util/List;` -> `R`

Format:

| **Name & Type**        | **Description**                                                                          |
|------------------------|------------------------------------------------------------------------------------------|
| magic: int             | Always "ACMF" to identify the format, encoded as ascii to a single integer: magicEncoded |
| version: byte          | The version of the format being serialized, which is currently just `1`                  |
| namespaces[]: string[] | The namespaces stored (the length is stored as a byte)                                   |
| classes[]: class[]     | The mapped classes (the length is stored as an int)                                      |

`class` type (corresponding to [MappedClass][com.grappenmaker.mappings.MappedClass]):

| **Name & Type**   | **Description**                                                                                                                                                      |
|-------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| names[]: string[] | Names of this [MappedClass][com.grappenmaker.mappings.MappedClass] instance, an empty string (length = 0) means the last name with a nonzero length should be copied |
| members: mapped[] | The mapped members (the length is stored as a varint)                                                                                                                |

`mapped` type (corresponding to [Mapped][com.grappenmaker.mappings.Mapped]):

| **Name & Type**        | **Description**                                                                                                                                            |
|------------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------|
| names[]: string[]      | Names of this [Mapped][com.grappenmaker.mappings.Mapped] instance, an empty string (length = 0) means the last name with a nonzero length should be copied |
| descriptor: descriptor | If the descriptor starts with a `(`, this [Mapped][com.grappenmaker.mappings.Mapped] represents a method, a field otherwise                                |
