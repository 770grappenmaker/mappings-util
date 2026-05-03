package com.grappenmaker.mappings

import kotlin.test.Test
import kotlin.test.assertEquals

class TestInheritance {
    private val provider = LoaderInheritanceProvider(ClasspathLoaders.fromSystemLoader())

    @Test
    fun testParentsTransitive() {
        assertEquals("D, C, F, B, E".split(", "), provider.getParents("A").toList())
    }

    @Test
    fun testParentsSingle() {
        assertEquals(listOf("E"), provider.getParents("B").toList())
        assertEquals(listOf("F"), provider.getParents("C").toList())
    }

    @Test
    fun testParentsEmpty() {
        assertEquals(emptyList(), provider.getParents("D").toList())
        assertEquals(emptyList(), provider.getParents("E").toList())
        assertEquals(emptyList(), provider.getParents("F").toList())
    }
}