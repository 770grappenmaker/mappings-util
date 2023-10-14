package com.grappenmaker.mappings

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
 * Joins together this [Mappings] with [otherMappings], by matching on [intermediateNamespace].
 * Creates mappings with namespaces [namespace], [intermediateNamespace], [otherNamespace], in that order.
 */
public fun Mappings.join(
    namespace: String,
    otherMappings: Mappings,
    otherNamespace: String,
    intermediateNamespace: String
): Mappings {
    val firstId = namespace(namespace)
    val firstIntermediaryId = namespace(intermediateNamespace)
    val secondIntermediaryId = otherMappings.namespace(intermediateNamespace)
    val secondId = otherMappings.namespace(otherNamespace)
    val bySecondName = otherMappings.classes.associateBy { it.names[secondIntermediaryId] }

    return GenericMappings(
        namespaces = listOf(namespace, intermediateNamespace, otherNamespace),
        classes = classes.map { originalClass ->
            val intermediateName = originalClass.names[firstIntermediaryId]
            val matching = bySecondName[intermediateName]
                ?: error("No matching class found for ${originalClass.names}!")

            val fieldsByName = matching.fields.associateBy { it.names[firstIntermediaryId] }
            val methodsByName = matching.methods.associateBy { it.names[firstIntermediaryId] }

            MappedClass(
                names = listOf(originalClass.names[firstId], intermediateName, matching.names[secondId]),
                comments = originalClass.comments + matching.comments,
                fields = originalClass.fields.map {
                    val intermediateFieldName = it.names[firstIntermediaryId]
                    val matchingField = fieldsByName[intermediateFieldName]
                        ?: error("No matching field found for ${it.names}!")

                    it.copy(
                        names = listOf(it.names[firstId], intermediateFieldName, matchingField.names[secondId]),
                        comments = it.comments + matchingField.comments,
                    )
                },
                methods = originalClass.methods.map {
                    val intermediateMethodName = it.names[firstIntermediaryId]
                    val matchingMethod = methodsByName[intermediateMethodName]
                        ?: error("No matching method found for ${it.names}!")

                    it.copy(
                        names = listOf(it.names[firstId], intermediateMethodName, matchingMethod.names[secondId]),
                        comments = it.comments + matchingMethod.comments,
                    )
                }
            )
        }
    )
}