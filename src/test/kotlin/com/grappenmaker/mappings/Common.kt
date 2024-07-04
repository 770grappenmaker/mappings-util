package com.grappenmaker.mappings

private val testsClassLoader = (object {}).javaClass.classLoader
fun String.getResource() = testsClassLoader.getResourceAsStream(this)?.readBytes()?.decodeToString()
    ?: error("Could not find resource $this")