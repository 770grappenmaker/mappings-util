package com.grappenmaker.mappings

import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.commons.ClassRemapper
import org.objectweb.asm.commons.Remapper
import org.objectweb.asm.commons.SimpleRemapper
import java.util.jar.JarFile

/**
 * An alias for a function that returns class file buffers given an internal/JVMS class name
 */
public typealias ClasspathLoader = (name: String) -> ByteArray?

// Optimization (presumably)
private fun List<ClasspathLoader>.flatten(): List<ClasspathLoader> = flatMap { loader ->
    if (loader is CompoundClasspathLoader) loader.loaders.flatten() else listOf(loader)
}

private data class CompoundClasspathLoader(val loaders: List<ClasspathLoader>) : ClasspathLoader {
    override fun invoke(name: String) = loaders.firstNotNullOfOrNull { it(name) }
}

/**
 * Provides default implementations for the classpath loaders in [MappingsRemapper]
 */
public object ClasspathLoaders {
    /**
     * Attempts to load classes using resources in [loader]
     */
    public fun fromLoader(loader: ClassLoader): ClasspathLoader =
        { loader.getResourceAsStream("$it.class")?.readBytes() }

    /**
     * Attempts to load classes using resources in the system class loader
     */
    public fun fromSystemLoader(): ClasspathLoader = fromLoader(ClassLoader.getSystemClassLoader())

    /**
     * Attempts to load classes from [jars], the caller is responsible for closing those jar files
     * when they are no longer relevant / used in the remapper
     */
    public fun fromJars(jars: List<JarFile>): ClasspathLoader {
        val index = jars.flatMap { f ->
            f.entries().asSequence()
                .filter { it.name.endsWith(".class") }
                .map { it.name.dropLast(6) to (f to it) }
        }.toMap()

        return { index[it]?.let { (f, e) -> f.getInputStream(e).readBytes() } }
    }

    /**
     * Combines several [loaders] into a single classpath loader,
     * that queries each loader in order, returning the first nonnull value
     */
    public fun compound(loaders: List<ClasspathLoader>): ClasspathLoader = CompoundClasspathLoader(loaders.flatten())

    /**
     * Combines several [loaders] into a single classpath loader,
     * that queries each loader in order, returning the first nonnull value
     */
    public fun compound(vararg loaders: ClasspathLoader): ClasspathLoader =
        CompoundClasspathLoader(loaders.toList().flatten())
}

/**
 * Composes [this] to a new classpath loader, that caches the results of [this]
 */
public fun ClasspathLoader.memoized(): ClasspathLoader = memoizedTo(hashMapOf())

/**
 * Composes [this] to a new classpath loader, that caches the results of [this] inside of a given [memo]
 */
public fun ClasspathLoader.memoizedTo(memo: MutableMap<String, ByteArray?>): ClasspathLoader =
    { memo.getOrPut(it) { this(it) } }

/**
 * Composes [this] to create a new classpath loader, that maps bytes given by [this] using a [remapper]
 */
public fun ClasspathLoader.remapping(remapper: Remapper): ClasspathLoader = { this(it)?.remapForLoader(remapper) }

private fun ByteArray.remapForLoader(remapper: Remapper): ByteArray {
    val writer = ClassWriter(0)
    ClassReader(this).accept(ClassRemapper(writer, remapper), 0)
    return writer.toByteArray()
}

/**
 * Composes [this] to create a new classpath loader, that maps names before passing them to [this]
 * between namespaces [from] and [to] using given [mappings]. It also alters the returned class file's class name
 * references to match the [to] namespace. Method and field names remain untouched.
 */
public fun ClasspathLoader.remappingNames(
    mappings: Mappings,
    from: String,
    to: String
): ClasspathLoader {
    val names = if (from != to) mappings.asASMMapping(
        to, from,
        includeMethods = false,
        includeFields = false
    ) else emptyMap()

    val mapper = SimpleRemapper(names.entries.associate { (k, v) -> v to k })
    return { this(names[it] ?: it)?.remapForLoader(mapper) }
}