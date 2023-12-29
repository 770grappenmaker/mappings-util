package com.grappenmaker.mappings

import org.objectweb.asm.commons.SimpleRemapper

/**
 * Represent any type of SRG mappings, whether this data structure represents XSRG or regular SRG is governed
 * by [isExtended].
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

    override fun parse(lines: List<String>): SRGMappings {
        val parted = lines.map { it.split(" ") }
        val fields = parted.collect("FD") { parts ->
            val from = parts[0]
            val to = parts[if (isExtended) 2 else 1]

            MappedField(
                names = listOf(from.substringAfterLast('/'), to.substringAfterLast('/')),
                comments = listOf(),
                desc = if (isExtended) parts[1] else null
            )
        }

        val methods = parted.collect("MD") { (from, fromDesc, to) ->
            MappedMethod(
                names = listOf(from.substringAfterLast('/'), to.substringAfterLast('/')),
                comments = listOf(),
                desc = fromDesc,
                parameters = listOf(),
                variables = listOf()
            )
        }

        val classEntries = parted.filter { (type) -> type == "CL:" }

        // Make sure we do not forget about orphaned ones
        // (sometimes mappings do not specify mappings for the class but they do for some entries)
        val missingClasses = methods.keys + fields.keys - classEntries.map { (_, from) -> from }.toSet()
        val classes = classEntries.map { (_, from, to) ->
            MappedClass(
                names = listOf(from, to),
                comments = listOf(),
                fields = fields[from] ?: listOf(),
                methods = methods[from] ?: listOf()
            )
        } + missingClasses.map { name ->
            MappedClass(
                names = listOf(name, name),
                comments = listOf(),
                fields = fields[name] ?: listOf(),
                methods = methods[name] ?: listOf()
            )
        }

        return SRGMappings(classes, isExtended)
    }

    private inline fun <T> List<List<String>>.collect(type: String, mapper: (List<String>) -> T) =
        filter { (t) -> t == "$type:" }
            .groupBy { (_, from) -> from.substringBeforeLast('/') }
            .mapValues { (_, entries) -> entries.map { mapper(it.drop(1)) } }

    override fun write(mappings: SRGMappings): List<String> {
        require(mappings.isExtended == isExtended) { "Cannot write XSRG as SRG, or SRG as XSRG" }

        val classesPart = mappings.classes.map { "CL: ${it.names.first()} ${it.names.last()}" }
        val fieldsPart = mappings.classes.flatMap { c ->
            c.fields.map {
                val ext = if (isExtended) " ${it.desc}" else ""
                "FD: ${c.names.first()}/${it.names.first()}$ext ${c.names.last()}/${it.names.last()} ${it.desc}"
            }
        }

        val methodsParts = mappings.classes.flatMap { c ->
            c.methods.map {
                "MD: ${c.names.first()}/${it.names.first()} ${it.desc} ${c.names.last()}/${it.names.last()} ${it.desc}"
            }
        }

        return classesPart + fieldsPart + methodsParts
    }
}