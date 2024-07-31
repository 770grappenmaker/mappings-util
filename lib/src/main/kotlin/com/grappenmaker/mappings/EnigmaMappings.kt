package com.grappenmaker.mappings

import java.net.URL

/**
 * Represents either an enigma mappings file
 */
public data class EnigmaMappings(override val classes: List<MappedClass>) : Mappings {
    init {
        assertValidDescs()
    }

    override val namespaces: List<String> = listOf("official", "named")
}

/**
 * Writes [EnigmaMappings] to a mappings file represented by a list of strings.
 */
public fun EnigmaMappings.write(): List<String> = EnigmaMappingsFormat.write(this)

/**
 * Writes [EnigmaMappings] as a lazily evaluated [Sequence].
 */
public fun EnigmaMappings.writeLazy(): Sequence<String> = EnigmaMappingsFormat.writeLazy(this)

/**
 * Represents the enigma mappings format
 */
public data object EnigmaMappingsFormat : MappingsFormat<EnigmaMappings> {
    override fun detect(lines: List<String>): Boolean = lines.firstOrNull()?.parts()?.first() == "CLASS"

    override fun parse(lines: Iterator<String>): EnigmaMappings {
        var state: EnigmaState = MappingState()

        for ((idx, line) in lines.withIndex()) {
            if (line.isBlank()) continue
            state = with(LineAndNumber(line, idx + 2)) { state.update() }
        }

        repeat(3) { state = state.end() }
        return EnigmaMappings(
            (state as? MappingState ?: error("Did not finish walking tree, parsing failed (ended in $state)")).classes
        )
    }

    private sealed interface EnigmaState {
        context(LineAndNumber)
        fun update(): EnigmaState

        fun end(): EnigmaState

        sealed interface ClassHolding : EnigmaState {
            val classes: MutableList<MappedClass>
        }
    }

    private class MappingState : EnigmaState.ClassHolding {
        override val classes = mutableListOf<MappedClass>()

        context(LineAndNumber)
        override fun update(): EnigmaState {
            val ident = line.countStart()
            val (type, parts) = line.prepare()

            if (ident != 0) parseError("Invalid indent top-level")
            if (type != "CLASS") parseError("Invalid top-level member type $type")

            return ClassState(this, 1, parts)
        }

        override fun end() = this
    }

    private class ClassState(
        val owner: EnigmaState.ClassHolding,
        val indent: Int,
        val names: List<String>
    ) : EnigmaState.ClassHolding {
        val comments = mutableListOf<String>()
        val fields = mutableListOf<MappedField>()
        val methods = mutableListOf<MappedMethod>()
        override val classes = owner.classes

        context(LineAndNumber)
        override fun update(): EnigmaState {
            val ident = line.countStart()
            if (ident < indent) {
                end()
                return owner.update()
            }

            val (type, parts) = line.prepare()

            return when (type) {
                "CLASS" -> ClassState(this, indent + 1, names.zip(parts) { a, b -> "$a$$b" })
                "FIELD" -> FieldState(this, indent + 1, parts.last(), parts.dropLast(1))
                "METHOD" -> MethodState(this, indent + 1, parts.last(), parts.dropLast(1))
                "COMMENT" -> {
                    comments += parts.join()
                    this
                }

                else -> parseError("Invalid class member type $type")
            }
        }

        override fun end(): EnigmaState {
            owner.classes += MappedClass(names, comments, fields, methods)
            return owner.end()
        }
    }

    private class FieldState(
        val owner: ClassState,
        val indent: Int,
        val desc: String,
        val names: List<String>
    ) : EnigmaState {
        val comments = mutableListOf<String>()

        context(LineAndNumber)
        override fun update(): EnigmaState {
            val ident = line.countStart()
            if (ident < indent) {
                end()
                return owner.update()
            }

            val (type, parts) = line.prepare()
            if (type != "COMMENT") parseError("Invalid field member type $type!")
            comments += parts.join()

            return this
        }

        override fun end(): EnigmaState {
            owner.fields += MappedField(names, comments, desc)
            return owner
        }
    }

    private class MethodState(
        val owner: ClassState,
        val indent: Int,
        val desc: String,
        val names: List<String>
    ) : EnigmaState {
        val comments = mutableListOf<String>()
        val parameters = mutableListOf<MappedParameter>()
        private val dummyArgState by lazy { ArgState(this, indent + 1) }

        context(LineAndNumber)
        override fun update(): EnigmaState {
            val ident = line.countStart()
            if (ident < indent) {
                end()
                return owner.update()
            }

            val (type, parts) = line.prepare()
            return when (type) {
                "COMMENT" -> {
                    comments += parts.join()
                    this
                }

                "ARG" -> {
                    val (index, names) = parts.splitFirst()

                    parameters += MappedParameter(
                        names,
                        index.toIntOrNull() ?: parseError("Invalid parameter index $index")
                    )

                    dummyArgState
                }

                else -> parseError("Invalid method member type $type")
            }
        }

        override fun end(): EnigmaState {
            owner.methods += MappedMethod(names, comments, desc, parameters)
            return owner
        }
    }

    private class ArgState(private val owner: MethodState, private val indent: Int) : EnigmaState {
        context(LineAndNumber)
        override fun update(): EnigmaState {
            val ident = line.countStart()
            if (ident < indent) {
                end()
                return owner.update()
            }

            val (type) = line.prepare()
            if (type != "COMMENT") parseError("Invalid arg member type $type")

            return this
        }

        override fun end() = owner
    }

    private fun String.prepare() = trimIndent().parts().splitFirst()

    private fun <T> List<T>.splitFirst() = first() to drop(1)
    private fun String.countStart(sequence: String = "\t") =
        windowedSequence(sequence.length, sequence.length).takeWhile { it == sequence }.count()

    private fun String.parts() = split(' ')
    private fun List<String>.join() = joinToString(" ")

    override fun writeLazy(mappings: EnigmaMappings): Sequence<String> = sequence {
        // key = name "path part"
        // value = children
        data class Entry(var value: MappedClass? = null, val children: MutableMap<String, Entry> = hashMapOf())

        // First pass: create tree of MappedClass
        val root = Entry()
        fun Entry.traverse(path: List<String>): Entry {
            if (path.isEmpty()) return this

            val owner = path.first()
            return children.getOrPut(owner) { Entry() }.traverse(path.drop(1))
        }

        for (c in mappings.classes) {
            val path = c.names.first().split('$')
            root.traverse(path).value = c
        }

        // Second pass: traverse tree and write
        suspend fun SequenceScope<String>.write(entry: Entry, indent: Int, parentNames: List<String>?) {
            val prefix = "\t".repeat(indent)
            val v = entry.value ?: error("Did not traverse tree somehow, this is a bug!")

            val names = if (parentNames != null) v.names.zip(parentNames) { a, b -> a.drop(b.length + 1) } else v.names
            yield("${prefix}CLASS ${names.join()}")

            for (nested in entry.children.values) write(nested, indent + 1, v.names)
            for (cm in v.comments) yield("${prefix}\tCOMMENT $cm")

            for (m in v.methods) {
                yield("${prefix}\tMETHOD ${m.names.join()} ${m.desc}")
                for (cm in m.comments) yield("${prefix}\t\tCOMMENT $cm")
                for (p in m.parameters) yield("${prefix}\t\tARG ${p.index} ${p.names.join()}")
            }

            for (f in v.fields) {
                yield("${prefix}\tFIELD ${f.names.join()} ${f.desc!!}")
                for (cm in f.comments) yield("${prefix}\t\tCOMMENT $cm")
            }
        }

        root.children.values.forEach { write(it, 0, null) }
    }
}