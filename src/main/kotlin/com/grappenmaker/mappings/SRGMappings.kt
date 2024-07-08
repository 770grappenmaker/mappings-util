package com.grappenmaker.mappings

import org.objectweb.asm.commons.SimpleRemapper

/**
 * Represent any type of SRG mappings, whether this data structure represents XSRG or regular SRG is governed
 * by [isExtended].
 *
 * @property isExtended whether this is an extended SRG mappings file (XSRG)
 */
public data class SRGMappings(override val classes: List<MappedClass>, val isExtended: Boolean) : Mappings {
    override val namespaces: List<String> = listOf("official", "named")
}

/**
 * Shorthand for converting [SRGMappings] to a [SimpleRemapper].
 *
 * @see [Mappings.asSimpleRemapper]
 */
public fun SRGMappings.asSimpleRemapper(): SimpleRemapper = asSimpleRemapper(namespaces[0], namespaces[1])

/**
 * Writes [SRGMappings] to a mappings file represented by a list of strings
 */
public fun SRGMappings.write(): List<String> = (if (isExtended) XSRGMappingsFormat else SRGMappingsFormat).write(this)

/**
 * Represents the SRG mappings format
 */
public data object SRGMappingsFormat : MappingsFormat<SRGMappings> by BasicSRGParser(false)

/**
 * Represents the XSRG mappings format
 */
public data object XSRGMappingsFormat : MappingsFormat<SRGMappings> by BasicSRGParser(true)

internal class BasicSRGParser(private val isExtended: Boolean) : MappingsFormat<SRGMappings> {
    private val entryTypes = setOf("CL", "FD", "MD", "PK")
    override fun detect(lines: List<String>): Boolean {
        if (!lines.all { it.substringBefore(':') in entryTypes || it.isEmpty() }) return false
        return lines.find { it.startsWith("FD:") }?.let { l -> l.split(" ").size > 3 == isExtended } ?: true
    }

    override fun parse(lines: Iterator<String>): SRGMappings {
        val methods = mutableMapOf<String, MutableList<MappedMethod>>()
        val fields = mutableMapOf<String, MutableList<MappedField>>()
        val classes = mutableListOf<MappedClass>()

        for ((idx, line) in lines.withIndex()) {
            if (line.isBlank()) continue
            val parts = line.split(' ')

            with(LineAndNumber(line, idx + 2)) {
                when (val t = parts.firstOrNull() ?: parseError("Missing member type")) {
                    "CL:" -> classes += MappedClass(
                        names = parts.subList(1, 3),
                        fields = fields[parts[1]] ?: listOf(),
                        methods = methods[parts[1]] ?: listOf()
                    )

                    "FD:" -> fields.getOrPut(parts[1].substringBeforeLast('/')) { mutableListOf() } += MappedField(
                        names = listOf(
                            parts[1].substringAfterLast('/'),
                            parts[if (isExtended) 3 else 2].substringAfterLast('/')
                        ),
                        desc = if (isExtended) parts[2] else null
                    )

                    "MD:" -> methods.getOrPut(parts[1].substringBeforeLast('/')) { mutableListOf() } += MappedMethod(
                        names = listOf(parts[1].substringAfterLast('/'), parts[3].substringAfterLast('/')),
                        desc = parts[2],
                    )

                    "PK:" -> { /* ignored */ }

                    else -> parseError("Invalid member type $t")
                }
            }
        }

        val missingClasses = methods.keys + fields.keys - classes.mapTo(hashSetOf()) { it.names.first() }
        classes += missingClasses.map { name ->
            MappedClass(
                names = listOf(name, name),
                fields = fields[name] ?: listOf(),
                methods = methods[name] ?: listOf()
            )
        }

        return SRGMappings(classes, isExtended)
    }

    override fun writeLazy(mappings: SRGMappings): Sequence<String> = sequence {
        require(mappings.isExtended == isExtended) { "Cannot write XSRG as SRG, or SRG as XSRG" }

        val mapping = mappings.asASMMapping(
            from = mappings.namespaces[0],
            to = mappings.namespaces[1],
            includeMethods = false,
            includeFields = false
        )

        for (c in mappings.classes) {
            yield("CL: ${c.names.first()} ${c.names.last()}")

            for (f in c.fields) {
                val ext = if (isExtended) " ${f.desc}" else ""
                val endExt = if (isExtended) " ${mapDesc(f.desc!!, mapping)}" else ""
                yield("FD: ${c.names[0]}/${f.names[0]}$ext ${c.names[1]}/${f.names[1]}$endExt")
            }

            for (m in c.methods) {
                val mappedDesc = mapMethodDesc(m.desc, mapping)
                yield("MD: ${c.names[0]}/${m.names[0]} ${m.desc} ${c.names[1]}/${m.names[1]} $mappedDesc")
            }
        }
    }
}