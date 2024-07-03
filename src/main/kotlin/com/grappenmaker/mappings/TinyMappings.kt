package com.grappenmaker.mappings

/**
 * Represents either a tiny v1 or a tiny v2 mappings file, which does not have a definition anywhere.
 * The serialization method of these mappings is governed by [isV2]
 *
 * @property isV2 whether this mappings file is Tiny version 2.
 */
public data class TinyMappings(
    override val namespaces: List<String>,
    override val classes: List<MappedClass>,
    val isV2: Boolean
) : Mappings {
    init {
        assertValidDescs()
    }
}

/**
 * Represents the Tiny v1 mappings format
 */
public data object TinyMappingsV1Format : TinyMappingsWriter by TinyMappingsFormat(false)

/**
 * Represents the Tiny v2 mappings format
 */
public data object TinyMappingsV2Format : TinyMappingsWriter by TinyMappingsFormat(true)

/**
 * Writes [TinyMappings] to a mappings file represented by a list of strings. If [compact] is set, a more compact
 * format of tiny mappings will be used, see [TinyMappingsWriter.write].
 */
public fun TinyMappings.write(compact: Boolean = isV2): List<String> =
    (if (isV2) TinyMappingsV2Format else TinyMappingsV1Format).write(this, compact)

internal class TinyMappingsFormat(private val isV2: Boolean) : MappingsFormat<TinyMappings>, TinyMappingsWriter {
    override fun detect(lines: List<String>): Boolean =
        lines.firstOrNull()?.parts()?.first() == (if (isV2) "tiny" else "v1") && (isV2 || lines.drop(1).all {
            it.startsWith("CLASS") || it.startsWith("FIELD") || it.startsWith("METHOD") || it.isEmpty()
        })

    // Quirk: empty name means take the last name
    private fun List<String>.fixNames() = buildList {
        val (first, rest) = this@fixNames.splitFirst()
        require(first.isNotEmpty()) { "first namespaced name is not allowed to be empty in tiny!" }
        add(first)

        rest.forEach { add(it.ifEmpty { last() }) }
    }

    override fun parse(lines: List<String>): TinyMappings {
        require(detect(lines)) { "Invalid mappings" }

        // FIXME: Skip meta for now
        val info = (lines.firstOrNull() ?: error("Mappings are empty!")).parts()
        val namespaces = info.drop(if (isV2) 3 else 1)
        val mapLines = lines.drop(1).dropWhile { it.countStart() != 0 }.filter { it.isNotBlank() }

        return TinyMappings(namespaces, if (isV2) {
            var state: TinyV2State = MappingState()
            mapLines.forEach { state = state.update(it) }
            repeat(2) { state = state.end() }

            (state as? MappingState ?: error("Did not finish walking tree, parsing failed (ended in $state)")).classes
        } else {
            val parted = lines.map { it.parts() }
            val methods = parted.collect("METHOD") { entry ->
                val (desc, names) = entry.splitFirst()
                MappedMethod(
                    names = names.fixNames(),
                    comments = listOf(),
                    desc = desc,
                    parameters = listOf(),
                    variables = listOf()
                )
            }

            val fields = parted.collect("FIELD") { entry ->
                val (desc, names) = entry.splitFirst()
                MappedField(
                    names = names.fixNames(),
                    comments = listOf(),
                    desc = desc
                )
            }

            parted.filter { (type) -> type == "CLASS" }.map { entry ->
                MappedClass(
                    names = entry.drop(1).fixNames(),
                    comments = listOf(),
                    fields = fields[entry[1]] ?: listOf(),
                    methods = methods[entry[1]] ?: listOf(),
                )
            }
        }, isV2)
    }

    private inline fun <T> List<List<String>>.collect(type: String, mapper: (List<String>) -> T) =
        filter { (t) -> t == type }
            .groupBy { (_, unmappedOwner) -> unmappedOwner }
            .mapValues { (_, entries) -> entries.map { mapper(it.drop(2)) } }

    private sealed interface TinyV2State {
        fun update(line: String): TinyV2State
        fun end(): TinyV2State
    }

    private inner class MappingState : TinyV2State {
        val classes = mutableListOf<MappedClass>()

        override fun update(line: String): TinyV2State {
            val ident = line.countStart()
            val (type, parts) = line.prepare()

            require(ident == 0) { "Invalid indent top-level" }
            require(type == "c") { "Non-class found at top level: $type" }

            return ClassState(this, parts.fixNames())
        }

        override fun end() = this
    }

    private inner class ClassState(val owner: MappingState, val names: List<String>) : TinyV2State {
        val comments = mutableListOf<String>()
        val fields = mutableListOf<MappedField>()
        val methods = mutableListOf<MappedMethod>()

        override fun update(line: String): TinyV2State {
            val ident = line.countStart()
            if (ident < 1) {
                end()
                return owner.update(line)
            }

            val (type, parts) = line.prepare()

            return when (type) {
                "f" -> FieldState(this, parts.first(), parts.drop(1).fixNames())
                "m" -> MethodState(this, parts.first(), parts.drop(1).fixNames())
                "c" -> {
                    comments += parts.joinToString("\t")
                    this
                }

                else -> error("Invalid class member type $type")
            }
        }

        override fun end(): TinyV2State {
            owner.classes += MappedClass(names, comments, fields, methods)
            return owner.end()
        }
    }

    private inner class FieldState(val owner: ClassState, val desc: String, val names: List<String>) : TinyV2State {
        val comments = mutableListOf<String>()

        override fun update(line: String): TinyV2State {
            val ident = line.countStart()
            if (ident < 2) {
                end()
                return owner.update(line)
            }

            val (type, parts) = line.prepare()
            require(type == "c") { "fields can only have comments, found type $type!" }
            comments += parts.joinToString("\t")

            return this
        }

        override fun end(): TinyV2State {
            owner.fields += MappedField(names, comments, desc)
            return owner
        }
    }

    private inner class MethodState(val owner: ClassState, val desc: String, val names: List<String>) : TinyV2State {
        val comments = mutableListOf<String>()
        val parameters = mutableListOf<MappedParameter>()
        val locals = mutableListOf<MappedLocal>()

        override fun update(line: String): TinyV2State {
            val ident = line.countStart()
            if (ident < 2) {
                end()
                return owner.update(line)
            }

            val (type, parts) = line.prepare()
            when (type) {
                "c" -> comments += parts.joinToString("\t")
                "p" -> {
                    val (index, names) = parts.splitFirst()
                    parameters += MappedParameter(names, index.toIntOrNull() ?: error("Invalid index $index"))
                }

                "v" -> {
                    val (idx, offset) = parts.take(2).map { it.toIntOrNull() ?: error("Invalid index $it for local") }
                    val lvtIndex = parts.getOrNull(2)?.toIntOrNull()
                    locals += MappedLocal(idx, offset, lvtIndex ?: -1, parts.drop(if (lvtIndex != null) 3 else 2))
                }

                else -> error("Illegal type in method $type")
            }

            return this
        }

        override fun end(): TinyV2State {
            owner.methods += MappedMethod(names, comments, desc, parameters, locals)
            return owner
        }
    }

    private fun String.prepare() = trimIndent().parts().splitFirst()

    private fun <T> List<T>.splitFirst() = first() to drop(1)
    private fun String.countStart(sequence: String = "\t") =
        windowedSequence(sequence.length, sequence.length).takeWhile { it == sequence }.count()

    private fun String.parts() = split('\t')
    private fun List<String>.indent() = map { "\t" + it }
    private fun List<String>.join() = joinToString("\t")

    override fun TinyMappingsWriter.Context.write(): List<String> {
        require(mappings.isV2 == isV2) { "tiny mappings versions do not match" }

        val header = (if (isV2) "tiny\t2\t0" else "v1") + "\t${mappings.namespaces.join()}"
        return listOf(header) + if (!isV2) {
            val classesPart = mappings.classes.map { "CLASS\t${it.names.unfixNames().join()}" }
            val methodsPart = mappings.classes.flatMap { c ->
                c.methods.map {
                    "METHOD\t${c.names.first()}\t${it.desc}\t${it.names.unfixNames().join()}"
                }
            }

            val fieldsPart = mappings.classes.flatMap { c ->
                c.fields.map {
                    "FIELD\t${c.names.first()}\t${it.desc!!}\t${it.names.unfixNames().join()}"
                }
            }

            classesPart + methodsPart + fieldsPart
        } else mappings.classes.flatMap { c ->
            listOf("c\t${c.names.unfixNames().join()}") + (c.methods.flatMap { m ->
                listOf("m\t${m.desc}\t${m.names.unfixNames().join()}") + (m.parameters.map {
                    "p\t${it.index}\t${it.names.unfixNames().join()}"
                } + m.variables.map {
                    "v\t${it.index}\t${it.startOffset}\t${it.lvtIndex}\t${it.names.unfixNames().join()}"
                }).indent()
            } + c.fields.map {
                "f\t${it.desc!!}\t${it.names.unfixNames().join()}"
            }).indent()
        }
    }
}

/**
 * Convenience interface for parameterizing writing tiny mappings
 */
public interface TinyMappingsWriter : MappingsFormat<TinyMappings> {
    /**
     * Writes some tiny mappings represented by a [Context] to a file
     */
    public fun Context.write(): List<String>

    /**
     * Writes tiny mappings represented by [mappings] to a file, using a compact format if [compact] is set.
     */
    public fun write(mappings: TinyMappings, compact: Boolean): List<String> = Context(mappings, compact).write()

    /**
     * Context for writing [TinyMappings], see [write]. If [compact] is set, a more compact format will be used,
     * which is unsupported by mappings-io but supported by tiny-mappings-parser and this library.
     *
     * @property mappings the mappings that will be written by this [Context]
     * @property compact whether a compact format will be used
     */
    public data class Context(val mappings: TinyMappings, val compact: Boolean) {
        /**
         * see [fixNames]
         */
        internal fun List<String>.unfixNames(): List<String> {
            if (!compact) return this
            if (isEmpty()) return emptyList()

            val result = mutableListOf(first())
            var last = first()

            for (c in drop(1)) {
                result += if (c == last) "" else {
                    last = c
                    c
                }
            }

            return result
        }
    }

    override fun write(mappings: TinyMappings): List<String> = Context(mappings, mappings.isV2).write()
}