@file:OptIn(ExperimentalTypeInference::class)

package com.grappenmaker.mappings

import org.objectweb.asm.ClassReader
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.ClassNode
import java.util.jar.JarFile
import kotlin.experimental.ExperimentalTypeInference

/**
 * Transforms this [Mappings] structure to a generic mappings implementation that maps between [from] and [to].
 */
@Deprecated(
    message = "This function is redundant",
    replaceWith = ReplaceWith(
        expression = "this.reorderNamespaces(from, to)",
        "com.grappenmaker.mappings.reorderNamespaces"
    )
)
public fun Mappings.extractNamespaces(from: String, to: String): Mappings {
    val fromIndex = namespace(from)
    val toIndex = namespace(to)
    val remapper = MappingsRemapper(this, namespaces.first(), from) { null }

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
 *
 * @sample samples.Mappings.rename
 */
public fun Mappings.renameNamespaces(to: List<String>): Mappings {
    require(to.size == namespaces.size) { "namespace length does not match" }
    return GenericMappings(to, classes)
}

/**
 * Swaps out the names for the namespaces in this [Mappings] data structure.
 *
 * @sample samples.Mappings.rename
 */
public fun Mappings.renameNamespaces(vararg to: String): Mappings = renameNamespaces(to.toList())

/**
 * Swaps out the names for the namespaces in this [Mappings] data structure, by reordering. Duplicate names are allowed,
 * in which case mapped entries are duplicated to fit the new [order]. If a name in [Mappings.namespaces] does not
 * appear in [order], its mapping entries will be missing in the returned [Mappings].
 *
 * @sample samples.Mappings.reorder
 */
public fun Mappings.reorderNamespaces(vararg order: String): Mappings = reorderNamespaces(order.toList())

/**
 * Swaps out the names for the namespaces in this [Mappings] data structure, by reordering. Duplicate names are allowed,
 * in which case mapped entries are duplicated to fit the new [order]. If a name in [Mappings.namespaces] does not
 * appear in [order], its mapping entries will be missing in the returned [Mappings]. If a namespace in [order]
 * does not exist in this [Mappings], an [IllegalArgumentException] will be thrown.
 *
 * @sample samples.Mappings.reorder
 */
public fun Mappings.reorderNamespaces(order: List<String>): Mappings {
    if (order.isEmpty()) return EmptyMappings

    val indices = order.map { n ->
        namespaces.indexOf(n).also { if (it < 0) throw IllegalArgumentException("Namespace $n was not found") }
    }

    val map = asASMMapping(namespaces.first(), order.first(), includeMethods = false, includeFields = false)

    return GenericMappings(
        namespaces = order,
        classes = classes.map { c ->
            c.copy(
                names = indices.map { c.names[it] },
                fields = c.fields.map { f ->
                    f.copy(
                        names = indices.map { f.names[it] },
                        desc = f.desc?.let { mapDesc(it, map) }
                    )
                },
                methods = c.methods.map { m ->
                    m.copy(
                        names = indices.map { m.names[it] },
                        desc = mapMethodDesc(m.desc, map)
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
 * Joins together this [Mappings] with [otherMappings], by matching on [intermediateNamespace], producing new [Mappings]
 * that contain all entries from this [Mappings] and [otherMappings].
 * If [requireMatch] is true, this method will throw an exception when no method or field or class is found
 *
 * @sample samples.Mappings.join
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

    // Initialize lookups for quicker access during join
    val firstIntermediaryId = namespace(intermediateNamespace)
    val secondIntermediaryId = otherMappings.namespace(intermediateNamespace)

    require(firstIntermediaryId >= 0) {
        "The left-hand-side of a Mappings join did not contain the intermediate namespace $intermediateNamespace"
    }

    require(secondIntermediaryId >= 0) {
        "The right-hand-side of a Mappings join did not contain the intermediate namespace $intermediateNamespace"
    }

    val firstByName = classes.associateBy { it.names[firstIntermediaryId] }
    val secondByName = otherMappings.classes.associateBy { it.names[secondIntermediaryId] }

    // Check if ALL classes that are in one Mappings are in the other if required
    if (requireMatch) firstByName.keys.assertEqual(secondByName.keys, "classes")

    // Figure out which namespaces that are not the intermediate namespace should be considered
    val otherNamespaces = (namespaces + otherMappings.namespaces).filterNot { it == intermediateNamespace }.distinct()

    // Reorder the namespaces such that all namespaces that are in the lhs are first, then intermediate, then rhs
    // This does mean that, if there are overlapping namespaces, there will be duplicates
    // TODO: reconsider this
    val firstNs = otherNamespaces.mapNotNull { n -> namespaces.indexOf(n).takeIf { it != -1 } }
    val secondNs = otherNamespaces.mapNotNull { n -> otherMappings.namespaces.indexOf(n).takeIf { it != -1 } }
    val orderedNs = firstNs.map { namespaces[it] } +
            intermediateNamespace +
            secondNs.map { otherMappings.namespaces[it] }

    fun <T : Mapped> updateName(on: T?, intermediateName: String, ns: List<Int>) =
        if (on != null) ns.map(on.names::get) else ns.map { intermediateName }

    fun <T : Mapped> updateNames(first: T?, intermediateName: String, second: T?) =
        updateName(first, intermediateName, firstNs) + intermediateName + updateName(second, intermediateName, secondNs)

    // Create some remappers that will "bridge the gaps" between different descs in mapped things
    val firstBaseMap = asASMMapping(
        from = namespaces.first(),
        to = intermediateNamespace,
        includeMethods = false,
        includeFields = false
    )

    val secondBaseMap = otherMappings.asASMMapping(
        from = otherMappings.namespaces.first(),
        to = intermediateNamespace,
        includeMethods = false,
        includeFields = false
    )

    val finalMap = asASMMapping(
        from = namespaces.first(),
        to = orderedNs.first(),
        includeMethods = false,
        includeFields = false
    )

    val classesToConsider = firstByName.keys + secondByName.keys

    // Perform join by doing name lookups and remaps
    return GenericMappings(
        namespaces = orderedNs,
        classes = classesToConsider.map { intermediateName ->
            val firstClass = firstByName[intermediateName]
            val secondClass = secondByName[intermediateName]

            // TODO: DRY
            val firstFieldsByName = firstClass?.fields?.associateBy { it.names[firstIntermediaryId] } ?: emptyMap()
            val secondFieldsByName = secondClass?.fields?.associateBy { it.names[secondIntermediaryId] } ?: emptyMap()

            val firstMethodsBySig = firstClass?.methods?.associateBy {
                it.names[firstIntermediaryId] + mapMethodDesc(it.desc, firstBaseMap)
            } ?: emptyMap()

            val secondMethodsBySig = secondClass?.methods?.associateBy {
                it.names[secondIntermediaryId] + mapMethodDesc(it.desc, secondBaseMap)
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
                        desc = (firstField ?: secondField)?.desc?.let { mapDesc(it, finalMap) }
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
                        desc = mapMethodDesc(desc, finalMap),
                        // Parameter and variable information will sadly be lost since there is no "good" way to merge
                        parameters = emptyList(),
                        variables = emptyList(),
                    )
                }
            )
        }
    )
}

/**
 * Joins together an [Iterable] of [Mappings], producing new [Mappings] that contain all entries from all [Mappings].
 * Note: all namespaces are kept, in order to be able to reduce the mappings nicely without a lot of overhead.
 * If you want to exclude certain namespaces, use [Mappings.filterNamespaces]. If this [Iterable]
 * would be considered empty (its [Iterator.hasNext] would return false on the first iteration), [EmptyMappings] is
 * returned.
 *
 * The order of these namespaces will be confusing due to the way [Mappings.join] orders namespaces. Use
 * [Mappings.reorderNamespaces] to ensure the resulting [Mappings] will be properly namespaced, if required.
 *
 * @see [Mappings.join]
 * @sample samples.Mappings.joinList
 */
public fun Iterable<Mappings>.join(
    intermediateNamespace: String,
    requireMatch: Boolean = false
): Mappings = reduceOrNull { acc, curr -> acc.join(curr, intermediateNamespace, requireMatch) } ?: EmptyMappings

/**
 * Filters these [Mappings] to only contain namespaces for which the [predicate] returns `true`.
 * If [allowDuplicates] is true, the returned [Mappings] will also have duplicate namespaces removed.
 *
 * The functionality of this function differs slightly from that of [Mappings.reorderNamespaces], in that this function
 * acts as a filter, which means that order will be preserved as is present in this [Mappings].
 *
 * @sample samples.Mappings.filter
 */
public inline fun Mappings.filterNamespaces(
    allowDuplicates: Boolean = false,
    predicate: (String) -> Boolean
): Mappings {
    // Could be slightly optimized since the filterNamespaces(Set) impl already does a similar thing
    // Though, this would mean we have to invoke the predicate with potentially duplicate entries
    // Also, sets could be somewhat expensive because of the low entry count
    // I should probably let it go since it is really not that important to optimize here
    val set = hashSetOf<String>()
    val seen = hashSetOf<String>()
    namespaces.forEach { if (seen.add(it) && predicate(it)) set.add(it) }

    return filterNamespaces(set, allowDuplicates)
}

/**
 * Filters these [Mappings] to only contain namespaces that are in [allowed]. If [allowDuplicates] is true, the returned
 * [Mappings] will also have duplicate namespaces removed.
 *
 * The functionality of this function differs slightly from that of [Mappings.reorderNamespaces], in that this function
 * acts as a filter, which means that order will be preserved as is present in this [Mappings], and if some namespace in
 * [allowed] does not exist, no error will be thrown.
 *
 * @sample samples.Mappings.filter
 */
public fun Mappings.filterNamespaces(vararg allowed: String, allowDuplicates: Boolean = false): Mappings =
    filterNamespaces(allowed.toSet(), allowDuplicates)

/**
 * Filters these [Mappings] to only contain namespaces that are in [allowed]. If [allowDuplicates] is true, the returned
 * [Mappings] will also have duplicate namespaces removed.
 *
 * The functionality of this function differs slightly from that of [Mappings.reorderNamespaces], in that this function
 * acts as a filter, which means that order will be preserved as is present in this [Mappings], and if some namespace in
 * [allowed] does not exist, no error will be thrown.
 *
 * @sample samples.Mappings.filter
 */
public fun Mappings.filterNamespaces(allowed: Set<String>, allowDuplicates: Boolean = false): Mappings {
    if (allowed.isEmpty() || namespaces.isEmpty()) return EmptyMappings

    val indices = mutableListOf<Int>()
    val seen = hashSetOf<String>()
    namespaces.forEachIndexed { idx, n -> if (n in allowed && (allowDuplicates || seen.add(n))) indices += idx }

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
 * Removes all duplicate namespace usages in this [Mappings].
 *
 * @sample samples.Mappings.dedup
 */
public fun Mappings.deduplicateNamespaces(): Mappings = filterNamespaces(namespaces.toSet())

/**
 * Filters classes matching the [predicate]
 */
public inline fun Mappings.filterClasses(predicate: (MappedClass) -> Boolean): Mappings =
    GenericMappings(namespaces, classes.filter(predicate))

/**
 * Maps classes according to the given [predicate]
 */
public inline fun Mappings.mapClasses(predicate: (MappedClass) -> MappedClass): Mappings =
    GenericMappings(namespaces, classes.map(predicate))

/**
 * Attempts to recover field descriptors that are missing because the original mappings format did not specify them.
 * [bytesProvider] is responsible for providing all of the classes that might be referenced in this [Mappings] object,
 * such that the descriptors can be recovered based on named (fields can be uniquely identified by an owner-name pair).
 * When field descriptors were not found, field mappings will not be passed onto the new [Mappings] as field mappings
 * without descriptors will be considered invalid.
 */
@OverloadResolutionByLambdaReturnType
public fun Mappings.recoverFieldDescriptors(bytesProvider: (name: String) -> ByteArray?): Mappings =
    recoverFieldDescriptors { name ->
        bytesProvider(name)?.let { b -> ClassNode().also { ClassReader(b).accept(it, 0) } }
    }

/**
 * Attempts to recover field descriptors that are missing because the original mappings format did not specify them.
 * [nodeProvider] is responsible for providing all of the [ClassNode]s that might be referenced in this [Mappings] object,
 * such that the descriptors can be recovered based on named (fields can be uniquely identified by an owner-name pair).
 * When field descriptors were not found, field mappings will not be passed onto the new [Mappings] as field mappings
 * without descriptors will be considered invalid.
 */
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
 * See [recoverFieldDescriptors]. [file] is a jar file resource (caller is responsible for closing it) that contains the
 * classes that are referenced in the generic overload.
 */
public fun Mappings.recoverFieldDescriptors(file: JarFile): Mappings = recoverFieldDescriptors a@{
    file.getInputStream(file.getJarEntry("$it.class") ?: return@a null).readBytes()
}

/**
 * Removes redundant or straight up incorrect data from this [Mappings] object, by looking at the inheritance
 * information present in class files, presented by the [loader].
 *
 * Redundant information is one of the following:
 * - Overloads are given a mapping again. Since overloads share the same name, they cannot have different info,
 * therefore this information is duplicate.
 * - Abstract methods are populated to interfaces (this can happen when using proguard mappings). This is usually
 * straight up wrong information, when a mapped method entry is not present on the actual class.
 * - a method being a data method ([MappedMethod.isData])
 *
 * @sample samples.Mappings.redundancy
 */
public fun Mappings.removeRedundancy(loader: ClasspathLoader): Mappings =
    if (namespaces.isEmpty()) EmptyMappings else GenericMappings(
        namespaces,
        classes.map { oc ->
            val name = oc.names.first()
            val ourSigs = hashSetOf<String>()
            val superSigs = hashSetOf<String>()

            walkInheritance(loader, name).forEach { curr ->
                val target = if (curr == name) ourSigs else superSigs
                val bytes = loader(curr)

                if (bytes != null) {
                    val methods = ClassNode().also { ClassReader(bytes).accept(it, 0) }.methods
                    val toConsider = if (curr == name) methods
                    else methods.filter { it.access and Opcodes.ACC_PRIVATE == 0 }

                    target += toConsider.map { it.name + it.desc }
                }
            }

            oc.copy(methods = oc.methods.filter {
                val sig = it.names.first() + it.desc
                sig in ourSigs && sig !in superSigs && !it.isData()
            })
        }
    )

/**
 * See [removeRedundancy]. [file] is a jar file resource (caller is responsible for closing it) that contains the
 * classes that are referenced in the generic overload. Calls with identical names are being cached by this function,
 * the caller is not responsible for this.
 *
 * @sample samples.Mappings.redundancy
 */
public fun Mappings.removeRedundancy(file: JarFile): Mappings =
    removeRedundancy(ClasspathLoaders.fromJar(file).memoized())