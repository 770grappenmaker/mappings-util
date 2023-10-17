package com.grappenmaker.mappings

import java.util.jar.JarFile

/**
 * Provides default implementations for the classpath loaders in [MappingsRemapper]
 */
public object ClasspathLoaders {
    /**
     * Attempts to load classes using resources in [loader]
     */
    public fun fromLoader(loader: ClassLoader): ((name: String) -> ByteArray?) =
        { loader.getResourceAsStream("$it.class")?.readBytes() }

    /**
     * Attempts to load classes using resources in the system class loader
     */
    public fun fromSystemLoader(): ((name: String) -> ByteArray?) = fromLoader(ClassLoader.getSystemClassLoader())

    /**
     * Attempts to load classes from [jars], the caller is responsible for closing those jar files
     * when they are no longer relevant / used in the remapper
     */
    public fun fromJars(jars: List<JarFile>): ((name: String) -> ByteArray?) {
        val index = jars.flatMap { f -> f.entries().asSequence().map { it.name to (f to it) } }.toMap()
        return { name -> index[name]?.let { (f, e) -> f.getInputStream(e).readBytes() } }
    }

    /**
     * Combines several [loaders] into a single classpath loader
     */
    public fun compound(loaders: List<(String) -> ByteArray>): ((name: String) -> ByteArray?) =
        { name -> loaders.firstNotNullOfOrNull { it(name) } }
}