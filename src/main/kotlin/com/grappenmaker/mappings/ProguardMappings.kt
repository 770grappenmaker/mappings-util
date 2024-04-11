package com.grappenmaker.mappings

import org.objectweb.asm.Type
import java.net.URL

/**
 * Represents Proguard debug deobfuscation mappings. Note that Proguard only supports two mappings namespaces.
 * Line number information is ignored by the parser.
 */
public data class ProguardMappings(override val classes: List<MappedClass>) : Mappings {
    override val namespaces: List<String> = listOf("named", "official")

    init {
        assertValidDescs()
    }
}

/**
 * Writes [ProguardMappings] to a mappings file represented by a list of strings
 */
public fun ProguardMappings.write(): List<String> = ProguardMappingsFormat.write(this)

/**
 * Implements the Proguard mappings format, disregarding line number information,
 * which is written as 0:0 when serialized.
 */
public data object ProguardMappingsFormat : MappingsFormat<ProguardMappings> {
    private val testRegex = """^(\w|\.)+ -> (\w|\.)+:$""".toRegex()
    private const val indent = "    "

    override fun detect(lines: List<String>): Boolean {
        return (lines.firstOrNull { !it.startsWith('#') } ?: return false).matches(testRegex)
    }

    private fun String.asResourceName() = replace('.', '/')
    private fun String.asBinaryName() = replace('/', '.')

    private fun String.parseType() = when (this) {
        "void" -> Type.VOID_TYPE
        "int" -> Type.INT_TYPE
        "boolean" -> Type.BOOLEAN_TYPE
        "short" -> Type.SHORT_TYPE
        "long" -> Type.LONG_TYPE
        "float" -> Type.FLOAT_TYPE
        "double" -> Type.DOUBLE_TYPE
        "char" -> Type.CHAR_TYPE
        else -> Type.getObjectType(asResourceName())
    }

    private fun Type.unparse() = when (this) {
        Type.VOID_TYPE -> "void"
        Type.INT_TYPE -> "int"
        Type.BOOLEAN_TYPE -> "boolean"
        Type.SHORT_TYPE -> "short"
        Type.LONG_TYPE -> "long"
        Type.FLOAT_TYPE -> "float"
        Type.DOUBLE_TYPE -> "double"
        Type.CHAR_TYPE -> "char"
        else -> className
    }

    override fun parse(lines: List<String>): ProguardMappings {
        var state: ProguardState = MappingState()
        lines.filterNot { it.startsWith('#') }.forEach { state = state.update(it) }
        state = state.end()

        return ProguardMappings(
            (state as? MappingState
                ?: error("Did not finish walking tree, parsing failed (ended in $state)")).classes
        )
    }

    private sealed interface ProguardState {
        fun update(line: String): ProguardState
        fun end(): ProguardState
    }

    private fun String.asMapping(): List<String> {
        val result = split(" -> ")
        require(result.size == 2) { "invalid mappings: expected 2 values, got ${result.size}" }
        return result
    }

    private class MappingState : ProguardState {
        val classes = mutableListOf<MappedClass>()

        override fun update(line: String): ProguardState {
            require(!line.startsWith(indent)) { "invalid mappings: $line" }
            return ClassState(this, line.removeSuffix(":").asMapping().map { it.asResourceName() })
        }

        override fun end() = this
    }

    private class ClassState(val owner: MappingState, val names: List<String>) : ProguardState {
        val fields = mutableListOf<MappedField>()
        val methods = mutableListOf<MappedMethod>()

        override fun update(line: String): ProguardState {
            if (!line.startsWith(indent)) {
                end()
                return owner.update(line)
            }

            val trimmed = line.trim()

            if ('(' in trimmed) {
                val withoutJunk = trimmed.substringAfterLast(':')
                val (desc, name) = withoutJunk.asMapping()
                val (returnName, rest) = desc.split(' ')

                val params = rest.substringAfter('(').substringBefore(')').split(",")
                    .filter { it.isNotEmpty() }.map { it.parseType() }

                val returnType = returnName.parseType()

                methods += MappedMethod(
                    names = listOf(rest.substringBefore('('), name),
                    comments = emptyList(),
                    desc = Type.getMethodType(returnType, *params.toTypedArray()).descriptor,
                    parameters = emptyList(),
                    variables = emptyList()
                )
            } else {
                val (desc, name) = trimmed.asMapping()
                val (type, rest) = desc.split(' ')

                fields += MappedField(
                    names = listOf(rest, name),
                    comments = emptyList(),
                    desc = type.parseType().descriptor
                )
            }

            return this
        }

        override fun end(): ProguardState {
            owner.classes += MappedClass(names, emptyList(), fields, methods)
            return owner.end()
        }
    }

    override fun write(mappings: ProguardMappings): List<String> = mappings.classes.flatMap { c ->
        listOf("${c.names.first().asBinaryName()} -> ${c.names.last().asBinaryName()}:") + (c.methods.map { m ->
            val type = Type.getMethodType(m.desc)
            val args = type.argumentTypes.joinToString(",") { it.unparse() }
            "${indent}1:1:${type.returnType.unparse()} ${m.names.first()}($args) -> ${m.names.last()}"
        } + c.fields.map {
            "$indent${Type.getType(it.desc!!).unparse()} ${it.names.first()} -> ${it.names.last()}"
        })
    }
}