@file:OptIn(ExperimentalTypeInference::class)

package com.grappenmaker.mappings

import org.objectweb.asm.ClassReader
import org.objectweb.asm.tree.ClassNode
import java.util.jar.JarFile
import kotlin.experimental.ExperimentalTypeInference

/**
 * Transforms this [Mappings] structure to a generic mappings implementation that maps between [from] and [to].
 */
public fun Mappings.extractNamespaces(from: String, to: String): Mappings {
    val fromIndex = namespace(from)
    val toIndex = namespace(to)
    val remapper = MappingsRemapper(this, namespaces.first(), from, shouldRemapDesc = false) { null }

    return GenericMappings(
        namespaces = listOf(from, to),
        classes = classes.map { c ->
            c.copy(
                names = listOf(c.names[fromIndex], c.names[toIndex]),
                fields = c.fields.map {
                    it.copy(
                        names = listOf(it.names[fromIndex], it.names[toIndex]),
                        desc = remapper.mapDesc(it.desc)
                    )
                },
                methods = c.methods.map {
                    it.copy(
                        names = listOf(it.names[fromIndex], it.names[toIndex]),
                        desc = remapper.mapMethodDesc(it.desc)
                    )
                }
            )
        }
    )
}

/**
 * Swaps out the names for the namespaces in this [Mappings] data structure.
 */
public fun Mappings.renameNamespaces(to: List<String>): Mappings {
    require(to.size == namespaces.size) { "namespace length does not match" }
    return GenericMappings(to, classes)
}

/**
 * Swaps out the names for the namespaces in this [Mappings] data structure.
 */
public fun Mappings.renameNamespaces(vararg to: String): Mappings = renameNamespaces(to.toList())

/**
 * Swaps out the names for the namespaces in this [Mappings] data structure, by reordering. All names in [order] should
 * appear in the [Mappings.namespaces]. Duplicate names are allowed.
 */
public fun Mappings.reorderNamespaces(vararg order: String): Mappings = reorderNamespaces(order.toList())

/**
 * Swaps out the names for the namespaces in this [Mappings] data structure, by reordering. All names in [order] should
 * appear in the [Mappings.namespaces]. Duplicate names are allowed.
 */
public fun Mappings.reorderNamespaces(order: List<String>): Mappings {
    require(order.size == namespaces.size) { "namespace length does not match" }

    val indices = order.map {
        namespaces.indexOf(it).also { i ->
            require(i != -1) { "Namespace $it missing in namespaces: $namespaces" }
        }
    }

    val remapper = MappingsRemapper(this, namespaces.first(), order.first()) { null }

    return GenericMappings(
        namespaces = order,
        classes = classes.map { c ->
            c.copy(
                names = indices.map { c.names[it] },
                fields = c.fields.map { f ->
                    f.copy(
                        names = indices.map { f.names[it] },
                        desc = remapper.mapDesc(f.desc)
                    )
                },
                methods = c.methods.map { m ->
                    m.copy(
                        names = indices.map { m.names[it] },
                        desc = remapper.mapMethodDesc(m.desc)
                    )
                },
            )
        },
    )
}

/**
 * Calculates the set of elements that are in [this] set but not in [other] and vice-versa
 */
private fun <T> Set<T>.disjointTo(other: Set<T>) = (this - other) + (other - this)

/**
 * Joins together this [Mappings] with [otherMappings], by matching on [intermediateNamespace].
 * If [requireMatch] is true, this method will throw an exception when no method or field or class is found
 */
public fun Mappings.join(
    otherMappings: Mappings,
    intermediateNamespace: String,
    requireMatch: Boolean = false,
): Mappings {
    fun <T> Set<T>.assertEqual(other: Set<T>, name: String) {
        val disjoint = disjointTo(other)
        require(disjoint.isEmpty()) { "${disjoint.size} $name are missing (requireMatch)" }
    }

    val firstIntermediaryId = namespace(intermediateNamespace)
    val secondIntermediaryId = otherMappings.namespace(intermediateNamespace)
    val firstByName = classes.associateBy { it.names[firstIntermediaryId] }
    val secondByName = otherMappings.classes.associateBy { it.names[secondIntermediaryId] }

    if (requireMatch) firstByName.keys.assertEqual(secondByName.keys, "classes")

    val otherNamespaces = (namespaces + otherMappings.namespaces).filterNot { it == intermediateNamespace }.distinct()
    val firstNs = otherNamespaces.mapNotNull { n -> namespaces.indexOf(n).takeIf { it != -1 } }
    val secondNs = otherNamespaces.mapNotNull { n -> otherMappings.namespaces.indexOf(n).takeIf { it != -1 } }
    val orderedNs = firstNs.map { namespaces[it] } +
            intermediateNamespace +
            secondNs.map { otherMappings.namespaces[it] }

    fun <T : Mapped> updateName(on: T?, intermediateName: String, ns: List<Int>) =
        if (on != null) ns.map(on.names::get) else ns.map { intermediateName }

    fun <T : Mapped> updateNames(first: T?, intermediateName: String, second: T?) =
        updateName(first, intermediateName, firstNs) + intermediateName + updateName(second, intermediateName, secondNs)

    val firstBaseRemapper = MappingsRemapper(
        mappings = this,
        from = namespaces.first(),
        to = intermediateNamespace,
        shouldRemapDesc = false
    ) { null }

    val secondBaseRemapper = MappingsRemapper(
        mappings = otherMappings,
        from = otherMappings.namespaces.first(),
        to = intermediateNamespace,
        shouldRemapDesc = false
    ) { null }

    val finalizeRemapper = MappingsRemapper(
        mappings = this,
        from = namespaces.first(),
        to = orderedNs.first(),
        shouldRemapDesc = false
    ) { null }

    val classesToConsider = firstByName.keys + secondByName.keys

    return GenericMappings(
        namespaces = orderedNs,
        classes = classesToConsider.map { intermediateName ->
            val firstClass = firstByName[intermediateName]
            val secondClass = secondByName[intermediateName]

            // TODO: DRY
            val firstFieldsByName = firstClass?.fields?.associateBy { it.names[firstIntermediaryId] } ?: emptyMap()
            val secondFieldsByName = secondClass?.fields?.associateBy { it.names[secondIntermediaryId] } ?: emptyMap()

            val firstMethodsBySig = firstClass?.methods?.associateBy {
                it.names[firstIntermediaryId] + firstBaseRemapper.mapMethodDesc(it.desc)
            } ?: emptyMap()

            val secondMethodsBySig = secondClass?.methods?.associateBy {
                it.names[secondIntermediaryId] + secondBaseRemapper.mapMethodDesc(it.desc)
            } ?: emptyMap()

            if (requireMatch) {
                firstFieldsByName.keys.assertEqual(secondFieldsByName.keys, "fields")
                firstMethodsBySig.keys.assertEqual(secondMethodsBySig.keys, "methods")
            }

            val fieldsToConsider = firstFieldsByName.keys + secondFieldsByName.keys
            val methodsToConsider = firstMethodsBySig.keys + secondMethodsBySig.keys

            MappedClass(
                names = updateNames(firstClass, intermediateName, secondClass),
                comments = (firstClass?.comments ?: emptyList()) + (secondClass?.comments ?: emptyList()),
                fields = fieldsToConsider.map { intermediateFieldName ->
                    val firstField = firstFieldsByName[intermediateFieldName]
                    val secondField = secondFieldsByName[intermediateFieldName]

                    MappedField(
                        names = updateNames(firstField, intermediateFieldName, secondField),
                        comments = (firstField?.comments ?: emptyList()) + (secondField?.comments ?: emptyList()),
                        desc = (firstField ?: secondField)?.desc?.let(finalizeRemapper::mapDesc)
                    )
                },
                methods = methodsToConsider.map { sig ->
                    val intermediateMethodName = sig.substringBefore('(')
                    val desc = sig.drop(intermediateMethodName.length)
                    val firstMethod = firstMethodsBySig[sig]
                    val secondMethod = secondMethodsBySig[sig]

                    MappedMethod(
                        names = updateNames(firstMethod, intermediateMethodName, secondMethod),
                        comments = (firstMethod?.comments ?: emptyList()) + (secondMethod?.comments ?: emptyList()),
                        desc = finalizeRemapper.mapMethodDesc(desc),
                        parameters = emptyList(),
                        variables = emptyList(),
                    )
                }
            )
        }
    )
}

/**
 * Joins together a list of [Mappings].
 * Note: all namespaces are kept, in order to be able to reduce the mappings nicely without a lot of overhead.
 * If you want to exclude certain namespaces, use [Mappings.filterNamespaces]
 *
 * @see [Mappings.join]
 */
public fun List<Mappings>.join(
    intermediateNamespace: String,
    requireMatch: Boolean = false
): Mappings = reduce { acc, curr -> acc.join(curr, intermediateNamespace, requireMatch) }

/**
 * Filters these [Mappings] to only contain namespaces that are in [allowed]
 */
public fun Mappings.filterNamespaces(vararg allowed: String): Mappings = filterNamespaces(allowed.toSet())

/**
 * Filters these [Mappings] to only contain namespaces that are in [allowed]
 */
public fun Mappings.filterNamespaces(allowed: Set<String>, allowDuplicates: Boolean = false): Mappings {
    val indices = mutableListOf<Int>()
    val seen = hashSetOf<String>()
    namespaces.intersect(allowed).forEachIndexed { idx, n -> if (allowDuplicates || seen.add(n)) indices += idx }

    fun <T : Mapped> T.update() = indices.map(names::get)

    return GenericMappings(
        namespaces = indices.map { namespaces[it] },
        classes = classes.map { c ->
            c.copy(
                names = c.update(),
                fields = c.fields.map { it.copy(names = it.update()) },
                methods = c.methods.map { it.copy(names = it.update()) },
            )
        }
    )
}

/**
 * Removes all duplicate namespace usages in this [Mappings]
 */
public fun Mappings.deduplicateNamespaces(): Mappings = filterNamespaces(namespaces.toSet())

/**
 * Filters classes matching the [predicate]
 */
public inline fun Mappings.filterClasses(predicate: (MappedClass) -> Boolean): Mappings =
    GenericMappings(namespaces, classes.filter(predicate))

/**
 * Maps classes according to the given [block]
 */
public inline fun Mappings.mapClasses(block: (MappedClass) -> MappedClass): Mappings =
    GenericMappings(namespaces, classes.map(block))

/**
 * Attempts to recover field descriptors that are missing because the original mappings format did not specify them.
 * [bytesProvider] is responsible for providing all of the classes that might be referenced in this [Mappings] object,
 * such that the descriptors can be recovered based on named (fields can be uniquely identified by an owner-name pair).
 */
@OverloadResolutionByLambdaReturnType
public fun Mappings.recoverFieldDescriptors(bytesProvider: (name: String) -> ByteArray?): Mappings =
    recoverFieldDescriptors { name ->
        bytesProvider(name)?.let { b -> ClassNode().also { ClassReader(b).accept(it, 0) } }
    }

@JvmName("recoverDescsByNode")
@OverloadResolutionByLambdaReturnType
public fun Mappings.recoverFieldDescriptors(nodeProvider: (name: String) -> ClassNode?): Mappings = GenericMappings(
    namespaces,
    classes.map { oc ->
        val node by lazy { nodeProvider(oc.names.first()) }
        val fieldsByName by lazy { node?.fields?.associateBy { it.name } ?: emptyMap() }

        oc.copy(fields = oc.fields.mapNotNull { of ->
            of.copy(desc = of.desc ?: (fieldsByName[of.names.first()]?.desc ?: return@mapNotNull null))
        })
    }
)

/**
 * See [recoverFieldDescs]. [file] is a jar file resource (caller is responsible for closing it) that contains the
 * classes that are referenced in the generic overload.
 */
public fun Mappings.recoverFieldDescriptors(file: JarFile): Mappings = recoverFieldDescriptors a@{
    file.getInputStream(file.getJarEntry("$it.class") ?: return@a null).readBytes()
}

/**
 * Removes redundant or straight up incorrect data from this [Mappings] object, by looking at the inheritance
 * information present in class files, presented by the [bytesProvider].
 *
 * Redundant information is one of the following:
 * - Overloads are given a mapping again. Since overloads share the same name, they cannot have different info,
 * therefore this information is duplicate.
 * - Abstract methods are populated to interfaces (this can happen when using proguard mappings). This is usually
 * straight up wrong information, when a mapped method entry is not present on the actual class.
 * - a method being a data method ([MappedMethod.isData])
 */
public fun Mappings.removeRedundancy(bytesProvider: (name: String) -> ByteArray?): Mappings = GenericMappings(
    namespaces,
    classes.map { oc ->
        val name = oc.names.first()
        val ourSigs = hashSetOf<String>()
        val superSigs = hashSetOf<String>()

        walkInheritance(bytesProvider, name).forEach { curr ->
            val target = if (curr == name) ourSigs else superSigs
            bytesProvider(curr)?.let { b -> ClassNode().also { ClassReader(b).accept(it, 0) } }
                ?.methods?.forEach { m -> target += "${m.name}${m.desc}" }
        }

        oc.copy(methods = oc.methods.filter {
            val sig = "${it.names.first()}${it.desc}"
            sig in ourSigs && sig !in superSigs && !it.isData()
        })
    }
)

/**
 * See [recoverFieldDescs]. [file] is a jar file resource (caller is responsible for closing it) that contains the
 * classes that are referenced in the generic overload. Calls with identical names are being cached by this function,
 * the caller is not responsible for this.
 */
public fun Mappings.removeRedundancy(file: JarFile): Mappings {
    val cache = hashMapOf<String, ByteArray?>()

    return removeRedundancy a@{
        cache.getOrPut(it) { file.getInputStream(file.getJarEntry("$it.class") ?: return@a null).readBytes() }
    }
}