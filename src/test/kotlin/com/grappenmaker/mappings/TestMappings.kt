package com.grappenmaker.mappings

import org.junit.jupiter.api.Test
import org.objectweb.asm.Opcodes.*
import org.objectweb.asm.tree.ClassNode
import kotlin.test.assertEquals
import kotlin.test.assertIs

class TestMappings {
    private val testDocument = "test.tiny".getResource().lines()

    @Test
    fun `parse tiny mappings`() {
        val mappings = MappingsLoader.loadMappings(testDocument)
        assertIs<TinyMappings>(mappings)
        assertEquals(TinyMappings(
            namespaces = listOf("official", "named"),
            classes = listOf(
                MappedClass(
                    names = listOf("a", "Main"),
                    comments = listOf("Test comment"),
                    fields = listOf(
                        MappedField(
                            names = listOf("b", "state"),
                            comments = listOf("Electric boogaloo"),
                            desc = "Ld;"
                        )
                    ),
                    methods = listOf(
                        MappedMethod(
                            names = listOf("c", "action"),
                            comments = listOf("Crazy", "Two comments!"),
                            desc = "()Le;",
                            parameters = emptyList(),
                            variables = emptyList()
                        ),
                        MappedMethod(
                            names = listOf("d", "anotherAction"),
                            comments = emptyList(),
                            desc = "()Le;",
                            parameters = emptyList(),
                            variables = emptyList()
                        )
                    )
                ),
                MappedClass(listOf("d", "SomeState"), emptyList(), emptyList(), emptyList()),
                MappedClass(listOf("e", "SomeOtherState"), emptyList(), emptyList(), emptyList()),
            ),
            isV2 = true
        ), mappings)
    }

    @Test
    fun `apply mappings to node`() {
        // TODO: too simple of an example
        val mappings = MappingsLoader.loadMappings(testDocument)
        val remapper = MappingsRemapper(mappings, "official", "named") { null }

        val node = ClassNode()
        with(node) {
            visit(V1_8, ACC_PRIVATE or ACC_FINAL, "a", null, "java/lang/Object", null)
            with(visitMethod(ACC_PRIVATE or ACC_FINAL, "c", "()Le;", null, null)) { visitEnd() }
            with(visitMethod(ACC_PRIVATE or ACC_FINAL, "d", "()Le;", null, null)) { visitEnd() }
            with(visitField(ACC_PUBLIC or ACC_FINAL, "b", "Ld;", null, null)) { visitEnd() }
            visitEnd()
        }

        node.remap(remapper)
        assertEquals("Main", node.name)

        val field = node.fields.single()
        assertEquals("state", field.name)
        assertEquals("LSomeState;", field.desc)

        val methodC = node.methods[0]
        assertEquals("action", methodC.name)
        assertEquals("()LSomeOtherState;", methodC.desc)

        val methodD = node.methods[1]
        assertEquals("anotherAction", methodD.name)
        assertEquals("()LSomeOtherState;", methodD.desc)
    }
}