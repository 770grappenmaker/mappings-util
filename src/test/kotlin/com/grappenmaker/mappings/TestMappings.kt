package com.grappenmaker.mappings

import org.junit.jupiter.api.Test
import org.objectweb.asm.Opcodes.*
import org.objectweb.asm.tree.ClassNode
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class TestMappings {
    private val testDocument = "test.tiny".getResource().lines()
    private val proguardTestDocument = "test.proguard".getResource().lines()

    private val undetectableTests = listOf(
        "test.csrg" to CSRGMappingsFormat,
        "test.recaf" to RecafMappingsFormat
    )

    private val allTests = listOf(
        "test.tiny", "test.tsrg", "test.xsrg",
        "test-v1.tiny", "test.proguard", "test.enigma"
    )

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
                        ),
                        MappedMethod(
                            names = listOf("d", "anotherAction"),
                            desc = "()Le;",
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

    @Test
    fun `mappings parse error`() {
        val erroneousDocument = buildList<String> {
            addAll(testDocument)
            this[1] = this[1].replaceFirstChar { 'b' }
        }

        val errorMsg = "Parsing failed at line 2: Invalid top-level member type b"
        val fail = assertFailsWith<IllegalArgumentException> { MappingsLoader.loadMappings(erroneousDocument) }
        assertEquals(errorMsg, fail.message)
    }

    private fun <T : Mappings> MappingsFormat<T>.test(doc: List<String>) {
        val parsed = parse(doc)
        println(parsed)
        val written = write(parsed)
        assertEquals(parsed, parse(written))
        if (this != ProguardMappingsFormat) assertEqualMappings(doc, written)
    }

    @Test
    fun `parse and write to self`() {
        allTests.forEach { test ->
            val doc = test.getResource().lines()
            val format = MappingsLoader.findMappingsFormat(doc)
            format.test(doc)
        }

        undetectableTests.forEach { (k, v) -> v.test(k.getResource().trim().lines()) }
    }

    private fun assertEqualMappings(expected: List<String>, actual: List<String>) {
        assertEquals(expected.first(), actual.first())

        val left = expected.filterTo(mutableListOf()) { it.isNotEmpty() }
        val right = actual.filterTo(mutableListOf()) { it.isNotEmpty() }

        for (element in left) assert(right.remove(element)) { "\"$element\" was not in actual but was in expected" }
        assert(right.isEmpty()) { "actual contains lines $right, were not found in expected" }
    }

    @Test
    fun `proguard and tiny are the same`() {
        val proguard = MappingsLoader.loadMappings(proguardTestDocument)
        val expected = MappingsLoader.loadMappings(testDocument)
        assertEquals(expected.asGenericMappings(), proguard.renameNamespaces("official", "named").asGenericMappings())
    }

    @Test
    fun `compacting and decompacting works`() {
        val mappings = MappingsLoader.loadMappings(testDocument).removeComments()
        val toTest = mappings.asCompactedMappings()
        val actual = CompactedMappingsFormat.parse(toTest.write())
        assertEquals(toTest, actual)
    }
}