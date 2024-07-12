package com.grappenmaker.mappings

import kotlin.io.path.Path
import kotlin.io.path.readText
import kotlin.io.path.writeLines

/**
 * Represents any entity that can have different mapped names
 *
 * @property names The names of this named entity, which should correspond to some namespaces in [Mappings.namespaces]
 */
public sealed interface Mapped {
    public val names: List<String>
}

/**
 * Represents any entity that can have comments (commonly used in Tiny mappings)
 *
 * @property comments All comments that the author of some [Mappings] file wrote
 */
public sealed interface Commented {
    public val comments: List<String>
}

/**
 * Represents a mapped class (containing [fields] and [methods])
 *
 * @property fields Mapping information for member fields
 * @property methods Mapping information for member methods
 */
public data class MappedClass(
    override val names: List<String>,
    override val comments: List<String> = emptyList(),
    val fields: List<MappedField> = emptyList(),
    val methods: List<MappedMethod> = emptyList()
) : Mapped, Commented

/**
 * Represents a mapped method
 *
 * @property desc The JVMS method descriptor in the "first namespace" of a [Mappings] file
 * @property parameters Deobfuscation information regarding parameter names
 * @property variables Deobfuscation information regarding local variable names
 */
public data class MappedMethod(
    override val names: List<String>,
    override val comments: List<String> = emptyList(),
    val desc: String,
    val parameters: List<MappedParameter> = emptyList(),
    val variables: List<MappedLocal> = emptyList(),
) : Mapped, Commented

/**
 * Represents a mapped local variable. This is different to a [MappedParameter], because it carries
 * data about LVT and usage in the bytecode.
 *
 * @property index the index (starting at zero) of this local variable
 * @property startOffset the offset of this local variable in the local variable table
 * @property lvtIndex the index of this local variable in the local variable table (less than zero if omitted)
 */
public data class MappedLocal(
    val index: Int,
    val startOffset: Int,
    val lvtIndex: Int,
    override val names: List<String>
) : Mapped

/**
 * Represents a mapped parameter of a [MappedMethod], which is different from a [MappedLocal] (see docs)
 *
 * @property index the index (starting at zero) of this parameter
 */
public data class MappedParameter(
    override val names: List<String>,
    val index: Int,
) : Mapped

/**
 * Represents a mapped field
 *
 * @property desc The JVMS field descriptor in the "first namespace" of a [Mappings] file
 */
public data class MappedField(
    override val names: List<String>,
    override val comments: List<String> = emptyList(),
    val desc: String?,
) : Mapped, Commented

/**
 * Represents any type of mappings. The names in all of the [Mapped] entities are in the order of the [namespaces],
 * and should have equal length arrays.
 *
 * @property namespaces All namespaces (in order) that this mappings file supports/contains
 * @property classes All mapped classes in this mappings file (in order, not important)
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

    // TODO: consider whether it is safe to make this an extension
    /**
     * Parses [lines] (representing a mappings file) into a mappings data structure. Throws an
     * [IllegalStateException] when [lines] is not a valid input for this mappings format.
     * To check if [lines] can be parsed, you could invoke [detect].
     *
     * If detection is not required, [parse] with an [Iterator] should be preferred
     */
    public fun parse(lines: List<String>): T = parse(lines.iterator())

    /**
     * Writes mappings compatible with this [MappingsFormat] back to a list of lines representing a mappings file
     */
    public fun write(mappings: T): List<String> = writeLazy(mappings).toList()

    /**
     * Parses [lines] (an iterator of lines in a mappings file) into a mappings data structure. Throws an
     * [IllegalStateException] when [lines] is not a valid input for this mappings format.
     */
    public fun parse(lines: Iterator<String>): T

    /**
     * Writes mappings compatible with this [MappingsFormat] to a lazily evaluated [Sequence]
     */
    public fun writeLazy(mappings: T): Sequence<String>

    /**
     * Represents a mappings format that does not support detecting inputs, because of ambiguities or similarities with
     * other formats. [detect] will always return `false`. If you want to use this mappings format, it should either
     * be known ahead of time or stored somewhere that this is the case, this library won't handle that for you.
     */
    public sealed interface Undetectable<T : Mappings> : MappingsFormat<T> {
        @Deprecated("This mappings format does not support detection", replaceWith = ReplaceWith("false"))
        override fun detect(lines: List<String>): Boolean = false
    }
}

/**
 * Parses [lines] (a [Sequence] of lines in a mappings file) into a mappings data structure. Throws an
 * [IllegalStateException] when [lines] is not a valid input for this mappings format.
 */
public fun <T : Mappings> MappingsFormat<T>.parse(lines: Sequence<String>): T = parse(lines.iterator())

/**
 * Represents an empty mappings object, with no data.
 */
public data object EmptyMappings : Mappings {
    override val namespaces: List<String> = emptyList()
    override val classes: List<MappedClass> = emptyList()
}

/**
 * The entry point for loading [Mappings].
 *
 * Note that it should be preferred that it is known in advance which mappings format is being dealt with,
 * as the detection mechanism can be inaccurate due to it not fully parsing the input file. It can also slow the entire
 * process down, since it requires the caller to read some probably large resource into a List<String> first,
 * which costs allocations.
 */
public object MappingsLoader {
    /**
     * Contains all supported [MappingsFormat]s.
     */
    public val allMappingsFormats: List<MappingsFormat<*>> = listOf(
        TinyMappingsV1Format, TinyMappingsV2Format,
        SRGMappingsFormat, XSRGMappingsFormat,
        ProguardMappingsFormat, TSRGV1MappingsFormat, TSRGV2MappingsFormat,
        CSRGMappingsFormat, EnigmaMappingsFormat, RecafMappingsFormat
    )

    /**
     * Finds the correct [MappingsFormat] for the mappings file represented by [lines]. Throws an
     * [IllegalStateException] when an invalid mappings sequence is provided (or not supported).
     *
     * Note that mappings formats that do not support detection (inheritors of [MappingsFormat.Undetectable]) will not
     * be returned by this function.
     *
     * @see [MappingsFormat.detect]
     */
    public fun findMappingsFormat(lines: List<String>): MappingsFormat<*> =
        allMappingsFormats.find { it.detect(lines) } ?: error("No format was found for mappings")

    /**
     * Attempts to load the mappings represented by [lines] as [Mappings]. Throws an [IllegalStateException] when an
     * invalid mappings sequence is provided (or not supported).
     *
     * Note that mappings in formats that do not support detection (inheritors of [MappingsFormat.Undetectable]) will
     * not be parsed correctly, and an [IllegalStateException] will be thrown.
     *
     * @see [MappingsFormat.parse]
     */
    public fun loadMappings(lines: List<String>): Mappings = findMappingsFormat(lines).parse(lines)
}

internal fun Mappings.assertValidDescs() {
    for (c in classes) for (f in c.fields) require(f.desc != null) {
        "field descriptors are not allowed to be null in ${javaClass.simpleName}! Was null in field $f" +
                "Consider calling recoverFieldDescriptors"
    }
}

// Parsing error handling
internal data class LineAndNumber(val line: String, val number: Int)

internal fun LineAndNumber.parseError(msg: String): Nothing =
    throw IllegalArgumentException("Parsing failed at line $number: $msg")

internal fun fixupHoles(
    methods: Map<String, List<MappedMethod>>,
    fields: Map<String, List<MappedField>>,
    classes: MutableList<MappedClass>
) {
    val missingClasses = methods.keys + fields.keys - classes.mapTo(hashSetOf()) { it.names.first() }
    classes += missingClasses.map { name ->
        MappedClass(
            names = listOf(name, name),
            fields = fields[name] ?: listOf(),
            methods = methods[name] ?: listOf()
        )
    }
}