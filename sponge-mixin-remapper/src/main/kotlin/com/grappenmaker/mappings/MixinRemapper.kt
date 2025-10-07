@file:OptIn(ExperimentalJarRemapper::class)

package com.grappenmaker.mappings.remap

import com.grappenmaker.mappings.AnnotationContext
import com.grappenmaker.mappings.ClasspathLoader
import com.grappenmaker.mappings.ClasspathLoaders
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.commons.Remapper

/**
 * Amends the [JarRemapper] to also remap SpongePowered Mixin annotated classes. The given [classLoader] is used to
 * instantiate annotation classes. It is totally valid to use the default, the bootstrap classloader. If necessary,
 * an additional [classpathLoader] can be passed to load `org.spongepowered.mixin` classes, which are required to read
 * the annotations.
 */
public fun JarRemapper.remapMixins(classLoader: ClassLoader? = null, classpathLoader: ClasspathLoader? = null) {
    extension(MixinExtension(classLoader, classpathLoader))
}

internal class MixinExtension(
    private val classLoader: ClassLoader?,
    private val classpathLoader: ClasspathLoader?
) : RemapperExtension {
    override fun createVisitor(loader: ClasspathLoader, remapper: Remapper): MixinJarRemapper {
        val totalLoader = if (classpathLoader != null) ClasspathLoaders.compound(loader, classpathLoader) else loader
        return MixinJarRemapper(AnnotationContext(classLoader, totalLoader))
    }
}

internal class MixinJarRemapper(private val context: AnnotationContext) : JarClassVisitor {
    override fun visit(name: String, parent: ClassVisitor): ClassVisitor? {
        TODO("Not yet implemented")
    }
}