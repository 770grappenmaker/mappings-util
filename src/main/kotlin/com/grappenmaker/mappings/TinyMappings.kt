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
    (if (isV2) TinyMappingsV2Format else TinyMappingsV1Format).write(this, compact).toList()

internal class TinyMappingsFormat(private val isV2: Boolean) : TinyMappingsWriter {
    // TODO: rethink this mechanism
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

    override fun parse(lines: Iterator<String>): TinyMappings {
        // FIXME: Skip meta for now
        val info = (lines.nextOrNull() ?: error("Invalid / missing Tiny header (v2=$isV2)")).parts()
        val namespaces = info.drop(if (isV2) 3 else 1)

        return TinyMappings(namespaces, if (isV2) {
            var state: TinyV2State = MappingState()

            for ((idx, line) in lines.withIndex()) {
                if (line.isBlank()) continue
                state = with(LineAndNumber(line, idx + 2)) { state.update() }
            }

            repeat(2) { state = state.end() }
            (state as? MappingState ?: error("Did not finish walking tree, parsing failed (ended in $state)")).classes
        } else {
            val methods = mutableMapOf<String, MutableList<MappedMethod>>()
            val fields = mutableMapOf<String, MutableList<MappedField>>()
            val classes = mutableListOf<MappedClass>()

            for ((idx, line) in lines.withIndex()) {
                if (line.isBlank()) continue
                val parts = line.parts()

                with(LineAndNumber(line, idx + 2)) {
                    when (val t = parts.firstOrNull() ?: parseError("Missing member type")) {
                        "CLASS" -> classes += MappedClass(
                            names = parts.drop(1).fixNames(),
                            fields = fields[parts[1]] ?: listOf(),
                            methods = methods[parts[1]] ?: listOf(),
                        )

                        "FIELD" -> fields.getOrPut(parts[1]) { mutableListOf() } += MappedField(
                            names = parts.drop(3).fixNames(),
                            desc = parts[2]
                        )

                        "METHOD" -> methods.getOrPut(parts[1]) { mutableListOf() } += MappedMethod(
                            names = parts.drop(3).fixNames(),
                            desc = parts[2]
                        )

                        else -> parseError("Invalid member type $t")
                    }
                }
            }

            classes
        }, isV2)
    }

    private sealed interface TinyV2State {
        context(LineAndNumber)
        fun update(): TinyV2State

        fun end(): TinyV2State
    }

    private inner class MappingState : TinyV2State {
        val classes = mutableListOf<MappedClass>()

        context(LineAndNumber)
        override fun update(): TinyV2State {
            val ident = line.countStart()
            val (type, parts) = line.prepare()

            if (ident != 0) parseError("Invalid indent top-level")
            if (type != "c") parseError("Invalid top-level member type $type")

            return ClassState(this, parts.fixNames())
        }

        override fun end() = this
    }

    private inner class ClassState(val owner: MappingState, val names: List<String>) : TinyV2State {
        val comments = mutableListOf<String>()
        val fields = mutableListOf<MappedField>()
        val methods = mutableListOf<MappedMethod>()

        context(LineAndNumber)
        override fun update(): TinyV2State {
            val ident = line.countStart()
            if (ident < 1) {
                end()
                return owner.update()
            }

            val (type, parts) = line.prepare()

            return when (type) {
                "f" -> FieldState(this, parts.first(), parts.drop(1).fixNames())
                "m" -> MethodState(this, parts.first(), parts.drop(1).fixNames())
                "c" -> {
                    comments += parts.joinToString("\t")
                    this
                }

                else -> parseError("Invalid class member type $type")
            }
        }

        override fun end(): TinyV2State {
            owner.classes += MappedClass(names, comments, fields, methods)
            return owner.end()
        }
    }

    private inner class FieldState(val owner: ClassState, val desc: String, val names: List<String>) : TinyV2State {
        val comments = mutableListOf<String>()

        context(LineAndNumber)
        override fun update(): TinyV2State {
            val ident = line.countStart()
            if (ident < 2) {
                end()
                return owner.update()
            }

            val (type, parts) = line.prepare()
            if (type != "c") parseError("Invalid field member type $type!")
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

        context(LineAndNumber)
        override fun update(): TinyV2State {
            val ident = line.countStart()
            if (ident < 2) {
                end()
                return owner.update()
            }

            val (type, parts) = line.prepare()
            when (type) {
                "c" -> comments += parts.joinToString("\t")
                "p" -> {
                    val (index, names) = parts.splitFirst()
                    parameters += MappedParameter(
                        names,
                        index.toIntOrNull() ?: parseError("Invalid parameter index $index")
                    )
                }

                "v" -> {
                    val (idx, offset) = parts.take(2).map { it.toIntOrNull() ?: parseError("Invalid local index $it") }
                    val lvtIndex = parts.getOrNull(2)?.toIntOrNull()
                    locals += MappedLocal(idx, offset, lvtIndex ?: -1, parts.drop(if (lvtIndex != null) 3 else 2))
                }

                else -> parseError("Invalid method member type $type")
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
    private fun List<String>.join() = joinToString("\t")

    override fun TinyMappingsWriter.Context.write(): Sequence<String> = sequence {
        require(mappings.isV2 == isV2) { "tiny mappings versions do not match" }

        yield((if (isV2) "tiny\t2\t0" else "v1") + "\t${mappings.namespaces.join()}")
        for (c in mappings.classes) {
            if (isV2) {
                yield("c\t${c.names.unfixNames().join()}")
                for (cm in c.comments) yield("\tc\t$cm")

                for (m in c.methods) {
                    yield("\tm\t${m.desc}\t${m.names.unfixNames().join()}")
                    for (cm in m.comments) yield("\t\tc\t$cm")
                    for (p in m.parameters) yield("\t\tp\t${p.index}\t${p.names.unfixNames().join()}")
                    for (v in m.variables) {
                        yield("\t\tv\t${v.index}\t${v.startOffset}\t${v.lvtIndex}\t${v.names.unfixNames().join()}")
                    }
                }

                for (f in c.fields) {
                    yield("\tf\t${f.desc!!}\t${f.names.unfixNames().join()}")
                    for (cm in f.comments) yield("\t\tc\t$cm")
                }
            } else {
                yield("CLASS\t${c.names.unfixNames().join()}")
                for (m in c.methods) yield("METHOD\t${c.names.first()}\t${m.desc}\t${m.names.unfixNames().join()}")
                for (f in c.fields) yield("FIELD\t${c.names.first()}\t${f.desc!!}\t${f.names.unfixNames().join()}")
            }
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
    public fun Context.write(): Sequence<String>

    /**
     * Writes tiny mappings represented by [mappings] to a file, using a compact format if [compact] is set.
     */
    public fun write(mappings: TinyMappings, compact: Boolean): Sequence<String> = Context(mappings, compact).write()

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

    override fun writeLazy(mappings: TinyMappings): Sequence<String> = Context(mappings, mappings.isV2).write()
}