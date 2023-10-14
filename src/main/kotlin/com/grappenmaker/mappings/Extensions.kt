package com.grappenmaker.mappings

import org.objectweb.asm.commons.SimpleRemapper

/**
 * Returns the index of a namespace named [name], but throws an [IllegalStateException] when [name] is not in the
 * [Mappings.namespaces].
 */
public fun Mappings.namespace(name: String): Int =
    namespaces.indexOf(name).also { if (it == -1) error("Invalid namespace $name") }

/**
 * Returns an asm [SimpleRemapper] for remapping references between namespaces [from] and [to] disregarding inheritance
 * and lambdas. For proper remapping, you should use the [MappingsRemapper].
 */
public fun Mappings.asSimpleRemapper(from: String, to: String): SimpleRemapper = SimpleRemapper(asASMMapping(from, to))

/**
 * Returns the ASM-style string index representing a [MappedField]
 */
public fun MappedField.index(owner: String, namespace: Int): String = "$owner.${names[namespace]}"

/**
 * Returns the ASM-style string index representing a [MappedMethod]
 */
public fun MappedMethod.index(owner: String, namespace: Int): String = "$owner.${names[namespace]}$desc"

/**
 * Returns a simple mapping representing all of the [Mappings], mapping between the namespaces [from] and [to].
 */
public fun Mappings.asASMMapping(from: String, to: String): Map<String, String> = buildMap {
    val fromIndex = namespaces.indexOf(from)
    val toIndex = namespaces.indexOf(to)

    require(fromIndex >= 0) { "Namespace $from does not exist!" }
    require(toIndex >= 0) { "Namespace $to does not exist!" }

    classes.forEach { clz ->
        val owner = clz.names[fromIndex]
        put(owner, clz.names[toIndex])
        clz.fields.forEach { put(it.index(owner, fromIndex), it.names[toIndex]) }
        clz.methods.forEach { put(it.index(owner, fromIndex), it.names[toIndex]) }
    }
}