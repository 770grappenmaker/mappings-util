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
 * Returns a simple mapping representing all of the [Mappings], mapping between the namespaces [from] and [to].
 * If [includeMethods] is true, then methods will be included in the mapping.
 * If [includeFields] is true, then fields will be included in the mapping.
 */
public fun Mappings.asASMMapping(
    from: String,
    to: String,
    includeMethods: Boolean = true,
    includeFields: Boolean = true,
): Map<String, String> = buildMap {
    val fromIndex = namespaces.indexOf(from)
    val toIndex = namespaces.indexOf(to)

    require(fromIndex >= 0) { "Namespace $from does not exist!" }
    require(toIndex >= 0) { "Namespace $to does not exist!" }

    val shouldRemapDesc = fromIndex != 0
    val descClassMap = mutableMapOf<String, String>()

    classes.forEach { clz ->
        val owner = clz.names[fromIndex]
        put(owner, clz.names[toIndex])
        if (includeMethods && shouldRemapDesc) descClassMap[clz.names.first()] = clz.names[fromIndex]
        if (includeFields) clz.fields.forEach { put("$owner.${it.names[fromIndex]}", it.names[toIndex]) }
    }

    if (includeMethods) classes.forEach { clz ->
        val owner = clz.names[fromIndex]
        clz.methods.forEach {
            val desc = if (shouldRemapDesc) mapMethodDesc(it.desc, descClassMap) else it.desc
            put("$owner.${it.names[fromIndex]}$desc", it.names[toIndex])
        }
    }
}

/**
 * Returns whether the methods represented by this [MappedMethod] is a data method, that is, if this method
 * is [hashCode], [toString], [equals] or an initializer, like a constructor or init {} block
 * (this means that the jvm names are &lt;init&gt; and &lt;clinit&gt;, respectively)
 */
public fun MappedMethod.isData(): Boolean = desc == "(Ljava/lang/Object;)Z" && names.first() == "equals" ||
        desc == "()I" && names.first() == "hashCode" ||
        desc == "()Ljava/lang/String;" && names.first() == "toString" ||
        names.first() == "<init>" || names.first() == "<clinit>"

internal fun <T> Iterator<T>.nextOrNull(): T? = if (hasNext()) next() else null