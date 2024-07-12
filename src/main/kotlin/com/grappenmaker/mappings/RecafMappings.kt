package com.grappenmaker.mappings

import kotlin.io.path.Path
import kotlin.io.path.useLines

/**
 * Represents a Recaf Mappings file
 */
public data class RecafMappings(override val classes: List<MappedClass>) : Mappings {
    init {
        assertValidDescs()
    }

    override val namespaces: List<String> = listOf("official", "named")
}

/**
 * Writes [RecafMappings] to a mappings file represented by a list of strings
 */
public fun RecafMappings.write(): List<String> = RecafMappingsFormat.write(this)

/**
 * Writes [RecafMappings] as a lazily evaluated [Sequence]
 */
public fun RecafMappings.writeLazy(): Sequence<String> = RecafMappingsFormat.writeLazy(this)

/**
 * Represents the Recaf mappings format
 */
public data object RecafMappingsFormat : MappingsFormat.Undetectable<RecafMappings> {
    override fun parse(lines: Iterator<String>): RecafMappings {
        val methods = mutableMapOf<String, MutableList<MappedMethod>>()
        val fields = mutableMapOf<String, MutableList<MappedField>>()
        val classes = mutableListOf<MappedClass>()

        for ((idx, line) in lines.withIndex()) {
            if (line.isBlank()) continue
            val parts = line.split(' ')

            when (parts.size) {
                2 -> if ('(' in parts[0]) {
                    val nameAndDesc = parts[0].substringAfter('.')
                    val parenIdx = nameAndDesc.indexOf('(')

                    methods.getOrPut(parts[0].substringBefore('.')) { mutableListOf() } += MappedMethod(
                        names = listOf(nameAndDesc.take(parenIdx), parts[1]),
                        desc = nameAndDesc.drop(parenIdx)
                    )
                } else {
                    classes += MappedClass(
                        names = parts,
                        fields = fields.getOrPut(parts[0]) { mutableListOf() },
                        methods = methods.getOrPut(parts[0]) { mutableListOf() },
                    )
                }

                3 -> fields.getOrPut(parts[0].substringBefore('.')) { mutableListOf() } += MappedField(
                    names = listOf(parts[0].substringAfter('.'), parts[2]),
                    desc = parts[1]
                )

                else -> LineAndNumber(line, idx + 1).parseError(
                    "Invalid line entry width, expected either 2 (class or method), or 3 (field), got ${parts.size}"
                )
            }
        }

        fixupHoles(methods, fields, classes)
        return RecafMappings(classes)
    }

    override fun writeLazy(mappings: RecafMappings): Sequence<String> = sequence {
        for (c in mappings.classes) {
            yield(c.names.joinToString(" "))
            for (m in c.methods) yield("${c.names[0]}.${m.names[0]}${m.desc} ${m.names[1]}")
            for (f in c.fields) yield("${c.names[0]}.${f.names[0]} ${f.desc!!} ${f.names[1]}")
        }
    }
}