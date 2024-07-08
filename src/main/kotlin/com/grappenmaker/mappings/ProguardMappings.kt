package com.grappenmaker.mappings

import org.objectweb.asm.Type

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
 * which is written as 1:1 when serialized.
 */
public data object ProguardMappingsFormat : MappingsFormat<ProguardMappings> {
    private val testRegex = """^(\w|\.)+ -> (\w|\.)+:$""".toRegex()
    private const val indent = "    "

    override fun detect(lines: List<String>): Boolean {
        return (lines.firstOrNull { !it.startsWith('#') } ?: return false).matches(testRegex)
    }

    private fun String.asResourceName() = replace('.', '/')
    private fun String.asBinaryName() = replace('/', '.')

    private fun String.parseType(): Type = when {
        endsWith("[]") -> Type.getType("[${removeSuffix("[]").parseType().descriptor}")
        else -> when (this) {
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
    }

    private fun Type.unparse(): String = when (this) {
        Type.VOID_TYPE -> "void"
        Type.INT_TYPE -> "int"
        Type.BOOLEAN_TYPE -> "boolean"
        Type.SHORT_TYPE -> "short"
        Type.LONG_TYPE -> "long"
        Type.FLOAT_TYPE -> "float"
        Type.DOUBLE_TYPE -> "double"
        Type.CHAR_TYPE -> "char"
        else -> when {
            sort == Type.ARRAY -> elementType.unparse() + "[]".repeat(dimensions)
            else -> className
        }
    }

    override fun parse(lines: Iterator<String>): ProguardMappings {
        var state: ProguardState = MappingState()

        for ((idx, line) in lines.withIndex()) {
            if (line.isBlank()) continue

            val commentIdx = line.indexOf('#').takeIf { it >= 0 } ?: line.length
            val content = line.take(commentIdx).trimEnd()
            val comment = line.drop(commentIdx + 1).trimStart()

            if (content.isNotEmpty()) state = with (LineAndNumber(content, idx + 1)) { state.update() }
            if (comment.isNotEmpty()) state.comment(comment)
        }

        state = state.end()

        return ProguardMappings(
            (state as? MappingState
                ?: error("Did not finish walking tree, parsing failed (ended in $state)")).classes
        )
    }

    private sealed interface ProguardState {
        context(LineAndNumber)
        fun update(): ProguardState
        fun end(): ProguardState
        fun comment(msg: String)
    }

    context(LineAndNumber)
    private fun String.asMapping(): List<String> {
        val result = split(" -> ")
        if (result.size != 2) parseError("expected mapping (2 values, got ${result.size}): $this")
        return result
    }

    private class MappingState : ProguardState {
        val classes = mutableListOf<MappedClass>()
        val comments = mutableListOf<String>()

        context(LineAndNumber)
        override fun update(): ProguardState {
            if (line.startsWith(indent)) parseError("Invalid top level indent")
            return ClassState(this, line.removeSuffix(":").asMapping().map { it.asResourceName() }, comments.toList())
                .also { comments.clear() }
        }

        override fun end() = this

        override fun comment(msg: String) {
            comments += msg
        }
    }

    private class ClassState(
        val owner: MappingState,
        val names: List<String>,
        val comments: List<String>
    ) : ProguardState {
        val fields = mutableListOf<MappedField>()
        val methods = mutableListOf<MappedMethod>()
        val memberComments = mutableListOf<String>()

        context(LineAndNumber)
        override fun update(): ProguardState {
            if (!line.startsWith(indent)) {
                end()
                return owner.update()
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
                    desc = Type.getMethodType(returnType, *params.toTypedArray()).descriptor,
                    comments = memberComments.toList()
                )
            } else {
                val (desc, name) = trimmed.asMapping()
                val (type, rest) = desc.split(' ')

                fields += MappedField(
                    names = listOf(rest, name),
                    desc = type.parseType().descriptor,
                    comments = memberComments.toList()
                )
            }

            memberComments.clear()

            return this
        }

        override fun end(): ProguardState {
            owner.classes += MappedClass(names, comments, fields, methods)
            return owner.end()
        }

        override fun comment(msg: String) {
            memberComments += msg
        }
    }

    override fun writeLazy(mappings: ProguardMappings): Sequence<String> = sequence {
        for (c in mappings.classes) {
            c.comments.forEach { yield("# $it") }
            yield("${c.names[0].asBinaryName()} -> ${c.names[1].asBinaryName()}:")

            for (f in c.fields) {
                f.comments.forEach { yield("$indent# $it") }
                yield("$indent${Type.getType(f.desc!!).unparse()} ${f.names[0]} -> ${f.names[1]}")
            }

            for (m in c.methods) {
                m.comments.forEach { yield("$indent# $it") }

                val type = Type.getMethodType(m.desc)
                val args = type.argumentTypes.joinToString(",") { it.unparse() }
                yield("${indent}1:1:${type.returnType.unparse()} ${m.names[0]}($args) -> ${m.names[1]}")
            }
        }
    }
}