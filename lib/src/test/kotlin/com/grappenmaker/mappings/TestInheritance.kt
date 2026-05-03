package com.grappenmaker.mappings

import kotlin.test.Test
import kotlin.test.assertEquals

class TestInheritance {
    private val provider = LoaderInheritanceProvider(ClasspathLoaders.fromSystemLoader())

    @Test
    fun `transitive dependencies should be picked up`() {
        assertEquals("D, C, F, B, E".split(", "), provider.getParents("A").toList())
    }

    @Test
    fun `shallow dependencies should be picked up`() {
        assertEquals(listOf("E"), provider.getParents("B").toList())
        assertEquals(listOf("F"), provider.getParents("C").toList())
    }

    @Test
    fun `lack of parents should not throw`() {
        assertEquals(emptyList(), provider.getParents("D").toList())
        assertEquals(emptyList(), provider.getParents("E").toList())
        assertEquals(emptyList(), provider.getParents("F").toList())
    }
}