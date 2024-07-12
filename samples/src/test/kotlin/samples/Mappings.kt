package samples

import com.grappenmaker.mappings.*
import com.grappenmaker.mappings.Mappings
import kotlin.test.*

typealias Sample = Test

class Mappings {
    @Sample
    fun rename() {
        val mappings = GenericMappings(
            namespaces = listOf("official", "named"),
            classes = listOf(MappedClass(
                names = listOf("a", "b"),
            ))
        )

        val renamed = mappings.renameNamespaces("original", "remapped")
        assertEquals(
            GenericMappings(
                namespaces = listOf("original", "remapped"),
                classes = listOf(MappedClass(
                    names = listOf("a", "b"),
                ))
            ), renamed
        )
    }

    @Sample
    fun reorder() {
        val mappings = GenericMappings(
            namespaces = listOf("official", "named"),
            classes = listOf(MappedClass(
                names = listOf("a", "b"),
            ))
        )

        val renamed = mappings.reorderNamespaces("named", "official", "named")
        assertEquals(
            GenericMappings(
                namespaces = listOf("named", "official", "named"),
                classes = listOf(MappedClass(
                    names = listOf("b", "a", "b"),
                ))
            ), renamed
        )
    }

    @Sample
    fun join() {
        val someMappings = GenericMappings(
            namespaces = listOf("official", "intermediary"),
            classes = listOf(MappedClass(
                names = listOf("a", "b"),
            ))
        )

        val otherMappings = GenericMappings(
            namespaces = listOf("intermediary", "named"),
            classes = listOf(MappedClass(
                names = listOf("b", "c"),
            ))
        )

        val joined = someMappings.join(otherMappings, intermediateNamespace = "intermediary")

        assertEquals(
            GenericMappings(
                namespaces = listOf("official", "intermediary", "named"),
                classes = listOf(MappedClass(
                    names = listOf("a", "b", "c"),
                ))
            ), joined
        )
    }

    @Sample
    fun joinList() {
        val someMappings = GenericMappings(
            namespaces = listOf("official", "intermediary"),
            classes = listOf(MappedClass(
                names = listOf("a", "b"),
            ))
        )

        val otherMappings = GenericMappings(
            namespaces = listOf("intermediary", "named"),
            classes = listOf(MappedClass(
                names = listOf("b", "c"),
            ))
        )

        val moreMappings = GenericMappings(
            namespaces = listOf("intermediary", "obfuscated"),
            classes = listOf(MappedClass(
                names = listOf("b", "e"),
            ))
        )

        val joined = listOf(someMappings, otherMappings, moreMappings).join(intermediateNamespace = "intermediary")

        assertEquals(
            GenericMappings(
                namespaces = listOf("official", "named", "intermediary", "obfuscated"),
                classes = listOf(MappedClass(
                    names = listOf("a", "c", "b", "e"),
                ))
            ), joined
        )

        assertEquals(emptyList<Mappings>().join(intermediateNamespace = "intermediary"), EmptyMappings)
    }

    @Sample
    fun filter() {
        val mappings = GenericMappings(
            namespaces = listOf("official", "intermediary", "named"),
            classes = listOf(MappedClass(
                names = listOf("a", "b", "c"),
            ))
        )

        val filtered = mappings.filterNamespaces("named", "official", "another")
        assertEquals(
            GenericMappings(
                namespaces = listOf("official", "named"),
                classes = listOf(MappedClass(
                    names = listOf("a", "c"),
                ))
            ), filtered
        )
    }

    @Sample
    fun dedup() {
        val mappings = GenericMappings(
            namespaces = listOf("official", "named", "named"),
            classes = listOf(MappedClass(
                names = listOf("a", "b", "b"),
            ))
        )

        val deduped = mappings.deduplicateNamespaces()
        assertEquals(
            GenericMappings(
                namespaces = listOf("official", "named"),
                classes = listOf(MappedClass(
                    names = listOf("a", "b"),
                ))
            ), deduped
        )
    }

    @Sample
    fun conversions() {
        val mappings = GenericMappings(
            namespaces = listOf("official", "named"),
            classes = listOf(MappedClass(
                names = listOf("a", "b"),
            ))
        )

        mappings.asTinyMappings(v2 = true) // or false
        mappings.asSRGMappings(extended = true) // or false
        mappings.asTSRGMappings(v2 = true) // or false
        mappings.asProguardMappings()
        mappings.asCompactedMappings()
    }

    @Sample
    fun redundancy() {
        val mappings = GenericMappings(
            namespaces = listOf("official", "named"),
            classes = listOf(MappedClass(
                names = listOf("a", "b"),
                methods = listOf(
                    MappedMethod(
                        names = listOf("hashCode", "hashCode"),
                        desc = "()I"
                    ),
                    MappedMethod(
                        names = listOf("toString", "toString"),
                        desc = "()Ljava/lang/String;"
                    ),
                    MappedMethod(
                        names = listOf("equals", "equals"),
                        desc = "(Ljava/lang/Object;)Z"
                    ),
                    MappedMethod(
                        names = listOf("sameName", "sameName"),
                        desc = "()V"
                    ),
                    MappedMethod(
                        names = listOf("<init>", "<init>"),
                        desc = "()V"
                    ),
                )
            ))
        )

        val simplified = mappings.removeRedundancy { null }
        assertEquals(
            GenericMappings(
                namespaces = listOf("official", "named"),
                classes = listOf(MappedClass(
                    names = listOf("a", "b"),
                    methods = emptyList()
                ))
            ), simplified
        )
    }
}