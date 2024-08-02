@file:Relocated

package com.grappenmaker.mappings.format

import com.grappenmaker.mappings.*
import com.grappenmaker.mappings.LineAndNumber
import com.grappenmaker.mappings.fixupHoles

/**
 * Represents either a tiny v1 or a tiny v2 mappings file, which does not have a definition anywhere.
 * The serialization method of these mappings is governed by [isV2].
 *
 * Tiny V2 mappings support "mappings metadata", which will be stored in the [metadata] map, where the values
 * are the tab-separated values after the keys declared in the mappings file.
 *
 * @property metadata said metadata
 * @property isV2 whether this mappings file is Tiny version 2.
 */
public data class TinyMappings(
    override val namespaces: List<String>,
    override val classes: List<MappedClass>,
    val metadata: Map<String, List<String>> = emptyMap(),
    val isV2: Boolean
) : Mappings {
    init {
        assertValidDescs()
    }
}

/**
 * Represents the Tiny v1 mappings format
 */
public data object TinyMappingsV1Format : TinyMappingsWriter {
    override fun detect(lines: List<String>): Boolean =
        lines.firstOrNull()?.parts()?.first() == "v1"

    override fun parse(lines: Iterator<String>): TinyMappings {
        val info = (lines.nextOrNull() ?: error("Invalid / missing Tiny v1 header")).parts()
        val namespaces = info.drop(1)

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
                        fields = fields.getOrPut(parts[1]) { mutableListOf() },
                        methods = methods.getOrPut(parts[1]) { mutableListOf() },
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

        fixupHoles(methods, fields, classes)
        return TinyMappings(namespaces, classes, isV2 = false)
    }

    override fun TinyMappingsWriter.Context.write(): Sequence<String> = sequence {
        require(!mappings.isV2) { "Expected Tiny mappings v1, found v2" }

        yield("v1\t${mappings.namespaces.join()}")
        for (c in mappings.classes) {
            yield("CLASS\t${c.names.unfixNames().join()}")
            for (m in c.methods) yield("METHOD\t${c.names.first()}\t${m.desc}\t${m.names.unfixNames().join()}")
            for (f in c.fields) yield("FIELD\t${c.names.first()}\t${f.desc!!}\t${f.names.unfixNames().join()}")
        }
    }
}

/**
 * Represents the Tiny v2 mappings format
 */
public data object TinyMappingsV2Format : TinyMappingsWriter {
    override fun detect(lines: List<String>): Boolean =
        lines.firstOrNull()?.parts()?.first() == "tiny"

    override fun parse(lines: Iterator<String>): TinyMappings {
        // FIXME: Skip meta for now
        val info = (lines.nextOrNull() ?: error("Invalid / missing Tiny v2 header")).parts()
        val namespaces = info.drop(3)

        var state: TinyV2State = MappingState()

        for ((idx, line) in lines.withIndex()) {
            if (line.isBlank()) continue
            state = with(LineAndNumber(line, idx + 2)) { state.update() }
        }

        repeat(2) { state = state.end() }

        val finalState =
            state as? MappingState ?: error("Did not finish walking tree, parsing failed (ended in $state)")

        return TinyMappings(
            namespaces,
            finalState.classes,
            finalState.metadata,
            isV2 = true
        )
    }

    private sealed interface TinyV2State {
        context(LineAndNumber)
        fun update(): TinyV2State

        fun end(): TinyV2State
    }

    private class MappingState : TinyV2State {
        val classes = mutableListOf<MappedClass>()
        val metadata = hashMapOf<String, List<String>>()

        context(LineAndNumber)
        override fun update(): TinyV2State {
            val ident = line.countStart()
            val (type, parts) = line.prepare()

            if (ident != 0) {
                // TODO: handle duplicate keys
                metadata[type] = parts
                return this
            }

            if (type != "c") parseError("Invalid top-level member type $type")

            return ClassState(this, parts.fixNames())
        }

        override fun end() = this
    }

    private class ClassState(val owner: MappingState, val names: List<String>) : TinyV2State {
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

    private class FieldState(val owner: ClassState, val desc: String, val names: List<String>) : TinyV2State {
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

    private class MethodState(val owner: ClassState, val desc: String, val names: List<String>) : TinyV2State {
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

    private fun String.prepare() = trimStart().parts().splitFirst()
    private fun String.countStart(sequence: String = "\t") =
        windowedSequence(sequence.length, sequence.length).takeWhile { it == sequence }.count()

    override fun TinyMappingsWriter.Context.write(): Sequence<String> = sequence {
        require(mappings.isV2) { "Expected Tiny v2 mappings, found v1" }

        yield("tiny\t2\t0\t${mappings.namespaces.join()}")
        for ((k, v) in mappings.metadata) yield("\t$k\t${v.join()}")
        for (c in mappings.classes) {
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
        }
    }
}

/**
 * Writes [TinyMappings] to a mappings file represented by a list of strings. If [compact] is set, a more compact
 * format of tiny mappings will be used, see [TinyMappingsWriter.write].
 */
public fun TinyMappings.write(compact: Boolean = isV2): List<String> =
    (if (isV2) TinyMappingsV2Format else TinyMappingsV1Format).write(this, compact).toList()

/**
 * Writes [TinyMappings] as a lazily evaluated [Sequence]. If [compact] is set, a more compact
 * format of tiny mappings will be used, see [TinyMappingsWriter.write].
 */
public fun TinyMappings.writeLazy(compact: Boolean = isV2): Sequence<String> =
    (if (isV2) TinyMappingsV2Format else TinyMappingsV1Format).write(this, compact)

/**
 * Convenience interface for parameterizing writing tiny mappings
 */
public sealed interface TinyMappingsWriter : MappingsFormat<TinyMappings> {
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

// Quirk: empty name means take the last name
context(TinyMappingsWriter)
private fun List<String>.fixNames() = buildList {
    val (first, rest) = this@fixNames.splitFirst()
    require(first.isNotEmpty()) { "first namespaced name is not allowed to be empty in Tiny mappings!" }
    add(first)

    rest.forEach { add(it.ifEmpty { last() }) }
}

context(TinyMappingsWriter)
private fun <T> List<T>.splitFirst() = first() to drop(1)

context(TinyMappingsWriter)
private fun String.parts() = split('\t')

context(TinyMappingsWriter)
private fun List<String>.join() = joinToString("\t")