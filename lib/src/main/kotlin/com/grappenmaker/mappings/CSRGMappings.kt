@file:Relocated

package com.grappenmaker.mappings.format

import com.grappenmaker.mappings.*
import com.grappenmaker.mappings.LineAndNumber
import com.grappenmaker.mappings.fixupHoles

/**
 * Represents a CSRG Mappings file
 */
public data class CSRGMappings(override val classes: List<MappedClass>) : Mappings {
    override val namespaces: List<String> = listOf("official", "named")
}

/**
 * Writes [CSRGMappings] to a mappings file represented by a list of strings
 */
public fun CSRGMappings.write(): List<String> = CSRGMappingsFormat.write(this)

/**
 * Writes [CSRGMappings] as a lazily evaluated [Sequence]
 */
public fun CSRGMappings.writeLazy(): Sequence<String> = CSRGMappingsFormat.writeLazy(this)

/**
 * Represents the CSRG mappings format
 */
public data object CSRGMappingsFormat : MappingsFormat.Undetectable<CSRGMappings> {
    override fun parse(lines: Iterator<String>): CSRGMappings {
        val methods = mutableMapOf<String, MutableList<MappedMethod>>()
        val fields = mutableMapOf<String, MutableList<MappedField>>()
        val classes = mutableListOf<MappedClass>()

        for ((idx, line) in lines.withIndex()) {
            if (line.isBlank()) continue
            val parts = line.split(' ')

            when (parts.size) {
                2 -> classes += MappedClass(
                    names = parts,
                    fields = fields.getOrPut(parts[0]) { mutableListOf() },
                    methods = methods.getOrPut(parts[0]) { mutableListOf() },
                )

                3 -> fields.getOrPut(parts[0]) { mutableListOf() } += MappedField(names = parts.drop(1), desc = null)
                4 -> methods.getOrPut(parts[0]) { mutableListOf() } += MappedMethod(
                    names = listOf(parts[1], parts[3]),
                    desc = parts[2]
                )

                else -> LineAndNumber(line, idx + 1).parseError(
                    "Invalid line entry width, expected either 2 (class), " +
                            "3 (field) or 4 (method), got ${parts.size}"
                )
            }
        }

        fixupHoles(methods, fields, classes)
        return CSRGMappings(classes)
    }

    override fun writeLazy(mappings: CSRGMappings): Sequence<String> = sequence {
        for (c in mappings.classes) {
            yield(c.names.joinToString(" "))
            for (m in c.methods) yield("${c.names[0]} ${m.names[0]} ${m.desc} ${m.names[1]}")
            for (f in c.fields) yield("${c.names[0]} ${f.names[0]} ${f.names[1]}")
        }
    }
}