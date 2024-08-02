@file:OptIn(ExperimentalJarRemapper::class)
@file:Relocated

package com.grappenmaker.mappings.remap

import com.grappenmaker.mappings.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import org.objectweb.asm.commons.Remapper
import java.nio.file.Path
import java.util.*
import java.util.jar.JarEntry
import java.util.jar.JarInputStream
import java.util.jar.JarOutputStream
import kotlin.io.path.inputStream
import kotlin.io.path.outputStream

/**
 * Marker annotation that declares that a certain type is part of the jar remapper DSL
 */
@DslMarker
@Retention
public annotation class JarRemapperDSL

/**
 * Marker annotation that declares that the annotated type is experimental Jar Remapper API
 */
@RequiresOptIn("Jar Remapper DSL is experimental and not at all guaranteed to work")
@Retention
public annotation class ExperimentalJarRemapper

/**
 * A data structure that specifies what the [JarRemapper] should do with a specific jar file
 *
 * @property input the file that will be read by the jar remapper, remapped into [output]
 * @property output the file that will be written by the jar remapper
 * @property fromNamespace the namespace the [input] file is in
 * @property toNamespace the namespace the [output] file will be written in
 */
@ExperimentalJarRemapper
public data class JarRemapTask(
    val input: Path,
    val output: Path,
    val fromNamespace: String,
    val toNamespace: String
)

/**
 * A functional interface that allows users of the [JarRemapper] to annotate or mutate resources in jar files
 * that are not classes
 */
@JarRemapperDSL
@ExperimentalJarRemapper
public fun interface JarResourceVisitor {
    /**
     * Called whenever a resource is being copied over from an input jar file by the [JarRemapper], where [name] is
     * the name of the resource being copied, and [file] the original buffer read from the input jar file.
     * The [ByteArray] that this [JarResourceVisitor] returns will be passed onto the next [JarResourceVisitor]
     * in the chain, and at the end the newly created buffer will be written to the output jar file. Returning null
     * here means that this [JarResourceVisitor] believes the file should not be copied over and should be discarded.
     */
    public fun visit(name: String, file: ByteArray): ByteArray?
}

/**
 * A functional interface that allows users of the [JarRemapper] to annotate or mutate class files with ASM
 * during remapping
 */
@JarRemapperDSL
@ExperimentalJarRemapper
public fun interface JarClassVisitor {
    /**
     * Called whenever a class file with UNMAPPED [name] is being remapped from an input jar file by the [JarRemapper].
     * The [ClassVisitor] that this [JarClassVisitor] returns will be passed onto the next [JarClassVisitor]
     * in the chain, and at the end the resulting [ClassVisitor] will be visited during remapping with the original
     * class file in the input jar file. Calls that are made to the [parent] remapper will eventually be passed on to
     * a ClassWriter, the output of which will be written to the output jar file. Returning null here means that
     * this [JarClassVisitor] is not interested in visiting this class
     */
    public fun visit(name: String, parent: ClassVisitor): ClassVisitor?
}

internal val signatureResourceVisitor = JarResourceVisitor { name, file ->
    if (name.endsWith(".RSA") || name.endsWith(".SF")) null else file
}

/**
 * Helper / DSL for creating an [JarRemapper]
 */
@JarRemapperDSL
@ExperimentalJarRemapper
public class JarRemapper {
    /**
     * The [Mappings] that will be used to remap classes in input jar files
     */
    public var mappings: Mappings = EmptyMappings
    private var tasks = mutableListOf<JarRemapTask>()

    /**
     * The [ClasspathLoader] that should be used to request class files from classpath / environment files,
     * that are not present in input jars. Note that this [ClasspathLoader] will be wrapped into a new one,
     * including input jars and a memoization layer. It is therefore not recommended to add memoization layers
     * in this [ClasspathLoader]. The loader should be thread-safe.
     */
    public var loader: ClasspathLoader = { null }

    private var classVisitors = mutableListOf<JarClassVisitor>()
    private var resourceVisitors = mutableListOf(signatureResourceVisitor)

    /**
     * Determines whether the [JarRemapper] will copy non-classfile resources from input jars into output jars
     */
    public var copyResources: Boolean = true

    /**
     * Adds a new remapping task to the [JarRemapper]. The [input] jar file will be read, remapped, and written
     * to an [output] file. The [input] jar file is expected to be in the [fromNamespace], and will be remapped
     * into the [toNamespace].
     */
    public fun task(input: Path, output: Path, fromNamespace: String, toNamespace: String) {
        tasks += JarRemapTask(input, output, fromNamespace, toNamespace)
    }

    /**
     * Adds a [JarClassVisitor] to the pipeline of visitors that will be applied to each remapped class
     *
     * @see [JarClassVisitor]
     */
    public fun visitClasses(visitor: JarClassVisitor) {
        classVisitors += visitor
    }

    /**
     * Adds a [JarResourceVisitor] to the pipeline of visitors that will be applied to each copied resource
     *
     * @see [JarResourceVisitor]
     */
    public fun visitResources(visitor: JarResourceVisitor) {
        resourceVisitors += visitor
    }

    /**
     * Performs all configured tasks
     */
    public suspend fun perform() {
        val commonMemo = Collections.synchronizedMap(hashMapOf<String, ByteArray?>())
        val commonLoader = loader.memoizedTo(commonMemo)

        // toList to copy, to ensure no unexpected concurrency weirdness
        val context = Context(
            mappings, tasks, commonLoader, classVisitors.toList(),
            resourceVisitors.toList(), copyResources
        )

        supervisorScope {
            with(context) { tasks.toList().forEach { launch { it.start() } } }
        }
    }

    private class Context(
        val mappings: Mappings,
        tasks: List<JarRemapTask>,
        val loader: ClasspathLoader,
        val classVisitors: List<JarClassVisitor>,
        val resourceVisitors: List<JarResourceVisitor>,
        val copyResources: Boolean,
    ) {
        init {
            for (task in tasks) {
                fun String.checkNs() = check(this in mappings.namespaces) { "Namespace $this not found for task $task" }

                task.fromNamespace.checkNs()
                task.toNamespace.checkNs()
            }
        }

        val sharedMaps = tasks
            .mapTo(hashSetOf()) { it.fromNamespace to it.toNamespace }
            .associateWith { (f, t) -> mappings.asASMMapping(f, t) }

        private fun ByteArray.remap(remapper: Remapper): Pair<ByteArray, String> {
            val reader = ClassReader(this)
            val writer = ClassWriter(reader, 0)

            val originalName = reader.className
            var writtenName = remapper.map(originalName)
            val inner: ClassVisitor = object : ClassVisitor(Opcodes.ASM9, writer) {
                override fun visit(
                    version: Int,
                    access: Int,
                    name: String,
                    signature: String?,
                    superName: String?,
                    interfaces: Array<String>?
                ) {
                    writtenName = name
                    super.visit(version, access, name, signature, superName, interfaces)
                }
            }

            val outer = classVisitors.fold(inner) { acc, curr -> curr.visit(originalName, acc) ?: acc }
            reader.accept(LambdaAwareRemapper(outer, remapper), 0)

            return writer.toByteArray() to writtenName
        }

        fun JarRemapTask.start() {
            val (classes, resources) = JarInputStream(input.inputStream(), false).use { stream ->
                generateSequence { stream.nextJarEntry }
                    .map { it.name to stream.readBytes() }
                    .partition { (n) -> n.endsWith(".class") }
                    .toList()
            }

            JarOutputStream(output.outputStream()).use { out ->
                fun write(name: String, bytes: ByteArray) {
                    out.putNextEntry(JarEntry(name))
                    out.write(bytes)
                }

                if (copyResources) resources.forEach { (k, v) ->
                    var buffer = v
                    for (visitor in resourceVisitors) buffer = visitor.visit(k, buffer) ?: return@forEach
                    write(k, buffer)
                }

                val lookup = classes.associate { (k, v) -> k.dropLast(6) to v }
                val map = sharedMaps.getValue(fromNamespace to toNamespace)
                val totalLoader = ClasspathLoaders.compound(ClasspathLoaders.fromLookup(lookup), loader)
                val remapper = LoaderSimpleRemapper(map, totalLoader)

                lookup.forEach { (_, original) ->
                    val (bytes, writtenName) = original.remap(remapper)
                    write("$writtenName.class", bytes)
                }
            }
        }
    }
}

/**
 * Performs a remap using [JarRemapper.perform]. Configuration for it can be provided in the [builder]
 */
@ExperimentalJarRemapper
public suspend inline fun performRemap(builder: JarRemapper.() -> Unit) {
    JarRemapper().also(builder).perform()
}