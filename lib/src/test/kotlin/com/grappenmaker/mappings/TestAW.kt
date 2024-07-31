package com.grappenmaker.mappings

import org.objectweb.asm.Opcodes.*
import org.objectweb.asm.tree.ClassNode
import kotlin.test.Test
import kotlin.test.assertEquals

class TestAW {
    private val testDocument = "test.accesswidener".getResource().lines()
    private val otherTestDocument = "test2.accesswidener".getResource().lines()
    private val testDocumentMapped = "test-mapped.accesswidener".getResource().lines()
    private val testMappings = "test.tiny".getResource().lines()

    @Test
    fun `parse widener`() {
        assertEquals(AccessWidener(
            version = 2,
            namespace = "official",
            classes = mapOf("a" to AccessMask(0b001)),
            fields = mapOf(AccessedMember("a", "b", "Ld;") to AccessMask(0b100)),
            methods = mapOf(
                AccessedMember("a", "c", "()Le;") to AccessMask(0b011),
                AccessedMember("a", "d", "()Le;") to AccessMask(0b010),
            ),
        ), loadAccessWidener(testDocument))
    }

    @Test
    fun `widener to tree`() {
        assertEquals(AccessWidenerTree(
            namespace = "official",
            classes = mapOf("a" to AccessedClass(
                mask = AccessMask(0b001),
                fields = mapOf(MemberIdentifier("b", "Ld;") to AccessMask(0b100)),
                methods = mapOf(
                    MemberIdentifier("c", "()Le;") to AccessMask(0b011),
                    MemberIdentifier("d", "()Le;") to AccessMask(0b010),
                ),
            ))
        ), loadAccessWidener(testDocument).toTree())
    }

    @Test
    fun `widener to tree no classes`() {
        assertEquals(AccessWidenerTree(
            namespace = "official",
            classes = mapOf("a" to AccessedClass(
                mask = AccessMask(0b000),
                fields = mapOf(MemberIdentifier("b", "Ld;") to AccessMask(0b100)),
                methods = mapOf(
                    MemberIdentifier("c", "()Le;") to AccessMask(0b011),
                    MemberIdentifier("d", "()Le;") to AccessMask(0b010),
                ),
            ))
        ), loadAccessWidener(testDocument).copy(classes = emptyMap()).toTree())
    }

    @Test
    fun `apply widener to visitor`() {
        val node = ClassNode()
        with(node) {
            visit(V1_8, ACC_PRIVATE or ACC_FINAL, "a", null, "java/lang/Object", null)
            with(visitMethod(ACC_PRIVATE or ACC_FINAL, "c", "()Le;", null, null)) { visitEnd() }
            with(visitMethod(ACC_PRIVATE or ACC_FINAL, "d", "()Le;", null, null)) { visitEnd() }
            with(visitField(ACC_PUBLIC or ACC_FINAL, "b", "Ld;", null, null)) { visitEnd() }
            visitEnd()
        }

        val targetNode = ClassNode()
        node.accept(AccessWidenerVisitor(targetNode, loadAccessWidener(testDocument).toTree()))

        val byName = targetNode.methods.associateBy { it.name }
        val c by byName
        val d by byName

        assertEquals(ACC_PUBLIC, targetNode.access)
        assertEquals(ACC_PUBLIC, c.access)
        assertEquals(ACC_PROTECTED, d.access)
        assertEquals(ACC_PUBLIC, targetNode.fields.single().access)
    }

    @Test
    fun `apply widener to node`() {
        val node = ClassNode()
        with(node) {
            visit(V1_8, ACC_PRIVATE or ACC_FINAL, "a", null, "java/lang/Object", null)
            with(visitMethod(ACC_PRIVATE or ACC_FINAL, "c", "()Le;", null, null)) { visitEnd() }
            with(visitMethod(ACC_PRIVATE or ACC_FINAL, "d", "()Le;", null, null)) { visitEnd() }
            with(visitField(ACC_PUBLIC or ACC_FINAL, "b", "Ld;", null, null)) { visitEnd() }
            visitEnd()
        }

        node.applyWidener(loadAccessWidener(testDocument).toTree())

        val byName = node.methods.associateBy { it.name }
        val c by byName
        val d by byName

        assertEquals(ACC_PUBLIC, node.access)
        assertEquals(ACC_PUBLIC, c.access)
        assertEquals(ACC_PROTECTED, d.access)
        assertEquals(ACC_PUBLIC, node.fields.single().access)
    }

    @Test
    fun `write widener`() = assertEqualAWs(testDocument, loadAccessWidener(testDocument).write())

    @Test
    fun `remap widener`() {
        val mappings = MappingsLoader.loadMappings(testMappings)
        assertEqualAWs(loadAccessWidener(testDocument).remap(mappings, "named").write(), testDocumentMapped)
    }

    @Test
    fun `merge widener`() {
        val lhs = loadAccessWidener(testDocument)
        val rhs = loadAccessWidener(otherTestDocument)
        val merged = lhs + rhs

        assertEquals(AccessWidener(
            version = 2,
            namespace = "official",
            classes = mapOf("a" to AccessMask(0b011)),
            fields = mapOf(AccessedMember("a", "b", "Ld;") to AccessMask(0b101)),
            methods = mapOf(
                AccessedMember("a", "c", "()Le;") to AccessMask(0b011),
                AccessedMember("a", "d", "()Le;") to AccessMask(0b011),
            ),
        ), merged)
    }

    // could consider comparing unserialized forms but thats too boring
    private fun assertEqualAWs(expected: List<String>, actual: List<String>) {
        assertEquals(expected.first(), actual.first())

        fun List<String>.stripComments() = mapTo(mutableListOf()) { it.substringBefore('#').trimEnd() }

        val left = expected.drop(1).stripComments()
        val right = actual.drop(1).stripComments()

        for (element in left) assert(right.remove(element)) { "[$element] was not in actual but was in expected" }
        assert(right.isEmpty()) { "actual contains lines $right, were not found in expected" }
    }
}