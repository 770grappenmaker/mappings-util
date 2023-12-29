package com.grappenmaker.mappings

/**
 * Represents any entity that can have different mapped names
 */
public sealed interface Mapped {
    public val names: List<String>
}

/**
 * Represents any entity that can have comments (commonly used in Tiny mappings)
 */
public sealed interface Commented {
    public val comments: List<String>
}

/**
 * Represents a mapped class (containing [fields] and [methods])
 */
public data class MappedClass(
    override val names: List<String>,
    override val comments: List<String>,
    val fields: List<MappedField>,
    val methods: List<MappedMethod>
) : Mapped, Commented

/**
 * Represents a mapped method
 */
public data class MappedMethod(
    override val names: List<String>,
    override val comments: List<String>,
    val desc: String,
    val parameters: List<MappedParameter>,
    val variables: List<MappedLocal>,
) : Mapped, Commented

/**
 * Represents a mapped local variable. This is different to a [MappedParameter], because it carries
 * data about LVT and usage in the bytecode.
 */
public data class MappedLocal(
    val index: Int,
    val startOffset: Int,
    val lvtIndex: Int,
    override val names: List<String>
) : Mapped

/**
 * Represents a mapped parameter of a [MappedMethod], which is different from a [MappedLocal] (see docs)
 */
public data class MappedParameter(
    override val names: List<String>,
    val index: Int,
) : Mapped

/**
 * Represents a mapped field
 */
public data class MappedField(
    override val names: List<String>,
    override val comments: List<String>,
    val desc: String?,
) : Mapped, Commented

/**
 * Represents any type of mappings. The names in all of the [Named] entities are in the order of the [namespaces],
 * and should have equal length arrays.
 */
public sealed interface Mappings {
    public val namespaces: List<String>
    public val classes: List<MappedClass>
}

/**
 * Represents a generic type of mapping that is not deserialized from anything, nor can be serialized to a
 * mappings file. It does not carry format-specific metadata, and is used as an intermediate value for transforming
 * mappings.
 */
public data class GenericMappings(
    override val namespaces: List<String>,
    override val classes: List<MappedClass>
) : Mappings

/**
 * Represents a generic mappings format
 */
public sealed interface MappingsFormat<T : Mappings> {
    /**
     * Returns whether this [MappingsFormat] thinks the [lines] represent a valid input for the mappings parser
     */
    public fun detect(lines: List<String>): Boolean

    /**
     * Parses [lines] (representing a mappings file) into a mappings data structure. Throws an
     * [IllegalStateException] when [lines] is not a valid input for this mappings format.
     * To check if [lines] can be parsed, you could invoke [detect].
     */
    public fun parse(lines: List<String>): T

    /**
     * Writes mappings compatible with this [MappingsFormat] back to a list of lines representing a mappings file
     */
    public fun write(mappings: T): List<String>
}

/**
 * Represents an empty mappings object, with no data.
 */
public data object EmptyMappings : Mappings {
    override val namespaces: List<String> = emptyList()
    override val classes: List<MappedClass> = emptyList()
}

/**
 * The entry point for loading [Mappings]
 */
public object MappingsLoader {
    /**
     * Contains all supported [MappingsFormat]s.
     */
    public val allMappingsFormats: List<MappingsFormat<*>> = listOf(
        TinyMappingsV1Format, TinyMappingsV2Format,
        SRGMappingsFormat, XSRGMappingsFormat,
        ProguardMappingsFormat, TSRGV1MappingsFormat, TSRGV2MappingsFormat
    )

    /**
     * Finds the correct [MappingsFormat] for the mappings file represented by [lines]. Throws an
     * [IllegalStateException] when an invalid mappings sequence is provided (or not supported).
     */
    public fun findMappingsFormat(lines: List<String>): MappingsFormat<*> =
        allMappingsFormats.find { it.detect(lines) } ?: error("No format was found for mappings")

    /**
     * Attempts to load the mappings represented by [lines] as [Mappings]. Throws an [IllegalStateException] when an
     * invalid mappings sequence is provided (or not supported).
     */
    public fun loadMappings(lines: List<String>): Mappings = findMappingsFormat(lines).parse(lines)
}

internal fun Mappings.assertValidDescs() {
    for (c in classes) for (f in c.fields) require(f.desc != null) {
        "field descriptors are not allowed to be null in ${javaClass.simpleName}! Was null in field $f" +
                "Consider calling recoverFieldDescriptors"
    }
}