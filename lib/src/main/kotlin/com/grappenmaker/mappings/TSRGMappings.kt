package com.grappenmaker.mappings

/**
 * Represents either a TSRG v1 or a TSRG v2 mappings file, which does not have a definition anywhere.
 * The serialization method of these mappings is governed by [isV2]
 *
 * @property isV2 whether this mappings file is TSRG version 2
 */
public data class TSRGMappings(
    override val namespaces: List<String>,
    override val classes: List<MappedClass>,
    val isV2: Boolean
) : Mappings

/**
 * Writes [TSRGMappings] to a mappings file represented by a list of strings
 */
public fun TSRGMappings.write(): List<String> = (if (isV2) TSRGV2MappingsFormat else TSRGV1MappingsFormat).write(this)

/**
 * Writes [TSRGMappings] as a lazily evaluated [Sequence]
 */
public fun TSRGMappings.writeLazy(): Sequence<String> =
    (if (isV2) TSRGV2MappingsFormat else TSRGV1MappingsFormat).writeLazy(this)

/**
 * Represents the TSRG v1 mappings format
 */
public data object TSRGV1MappingsFormat : MappingsFormat<TSRGMappings> by TSRGMappingsFormat(false)

/**
 * Represents the TSRG v2 mappings format
 */
public data object TSRGV2MappingsFormat : MappingsFormat<TSRGMappings> by TSRGMappingsFormat(true)

internal class TSRGMappingsFormat(private val isV2: Boolean) : MappingsFormat<TSRGMappings> {
    override fun detect(lines: List<String>): Boolean = when {
        isV2 -> lines.firstOrNull()?.startsWith("tsrg2") == true
        lines.size < 2 -> false
        else -> {
            // The assumption is that TSRG v1 supports only 2 namespaces, this has not been verified
            // To test whether this mapping matches TSRG v1, we match this condition, assuming the first
            // class has at least one mapped field or method, this does not have to be the case.
            // The only way to unambiguously verify if this mapping is a valid TSRG v1 mapping, we have to parse
            // the entire thing, or at least parts of it, which might be too expensive.
            val (fc, fe) = lines
            fc.parts().size == 2 && fe.startsWith('\t') && fe.parts().size == 2
        }
    }

    override fun parse(lines: Iterator<String>): TSRGMappings {
        // TODO: standardize default namespaces if the mapping format omits them
        // Currently, default mapping namespaces differ per mapping format, which might not be desirable
        val namespaces = if (isV2) (lines.nextOrNull() ?: error("Mappings are empty!")).parts().drop(1)
        else listOf("obf", "srg")

        var state: TSRGState = MappingState()

        for ((idx, line) in lines.withIndex()) {
            if (line.isBlank()) continue
            state = with(LineAndNumber(line, idx + 2)) { state.update() }
        }

        repeat(2) { state = state.end() }

        val end = (state as? MappingState ?: error("Did not finish walking tree, parsing failed (ended in $state)"))
        return TSRGMappings(namespaces, end.classes, isV2)
    }

    private sealed interface TSRGState {
        context(LineAndNumber)
        fun update(): TSRGState
        fun end(): TSRGState
    }

    private inner class MappingState : TSRGState {
        val classes = mutableListOf<MappedClass>()

        context(LineAndNumber)
        override fun update(): TSRGState {
            val ident = line.countStart()
            val parts = line.parts()

            if (ident != 0) parseError("Invalid indent top-level")
            return ClassState(this, parts)
        }

        override fun end() = this
    }

    private inner class ClassState(val owner: MappingState, val names: List<String>) : TSRGState {
        val comments = mutableListOf<String>()
        val fields = mutableListOf<MappedField>()
        val methods = mutableListOf<MappedMethod>()

        context(LineAndNumber)
        override fun update(): TSRGState {
            val ident = line.countStart()
            if (ident < 1) {
                end()
                return owner.update()
            }

            val parts = line.parts()
            return when {
                '(' in parts[1] -> MethodState(this, parts[1], listOf(parts.first()) + parts.drop(2))
                else -> {
                    fields += MappedField(parts, emptyList(), null)
                    this
                }
            }
        }

        override fun end(): TSRGState {
            owner.classes += MappedClass(names, comments, fields, methods)
            return owner.end()
        }
    }

    private inner class MethodState(val owner: ClassState, val desc: String, val names: List<String>) : TSRGState {
        val parameters = mutableListOf<MappedParameter>()

        context(LineAndNumber)
        override fun update(): TSRGState {
            val ident = line.countStart()
            if (ident < 2) {
                end()
                return owner.update()
            }

            val parts = line.parts()
            if (parts.size == 1) if (parts.single() != "static") parseError("Unrecognized method meta: $parts")
            else parameters += MappedParameter(parts.drop(1), parts.first().toInt())

            return this
        }

        override fun end(): TSRGState {
            owner.methods += MappedMethod(names, emptyList(), desc, parameters, emptyList())
            return owner
        }
    }

    private fun String.countStart(sequence: String = "\t") =
        windowedSequence(sequence.length, sequence.length).takeWhile { it == sequence }.count()

    private fun String.parts() = trimIndent().split(' ')
    private fun List<String>.join() = joinToString(" ")

    override fun writeLazy(mappings: TSRGMappings): Sequence<String> = sequence {
        require(mappings.isV2 == isV2) { "Cannot write v1 mappings with v2 format and vice versa" }
        if (!isV2) require(mappings.namespaces.size == 2) {
            "TSRG v1 supports exactly 2 mapping namespaces, found: ${mappings.namespaces}"
        }

        if (isV2) yield("tsrg2 ${mappings.namespaces.join()}")
        for (c in mappings.classes) {
            yield(c.names.join())
            for (f in c.fields) yield('\t' + f.names.join())
            for (m in c.methods) {
                yield("\t${m.names.first()} ${m.desc} ${m.names.drop(1).join()}")
                for (p in m.parameters) yield("\t\t${p.index} ${p.names.join()}")
            }
        }
    }
}