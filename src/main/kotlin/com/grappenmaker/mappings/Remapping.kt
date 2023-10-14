package com.grappenmaker.mappings

import org.objectweb.asm.*
import org.objectweb.asm.commons.ClassRemapper
import org.objectweb.asm.commons.MethodRemapper
import org.objectweb.asm.commons.Remapper
import java.io.File
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream

public class AccessWideningVisitor(parent: ClassVisitor) : ClassVisitor(Opcodes.ASM9, parent) {
    private fun Int.widen() = this and (Opcodes.ACC_PRIVATE or Opcodes.ACC_PROTECTED).inv() or Opcodes.ACC_PUBLIC
    private fun Int.removeFinal() = this and Opcodes.ACC_FINAL.inv()

    override fun visit(
        version: Int,
        access: Int,
        name: String,
        signature: String?,
        superName: String?,
        interfaces: Array<String>?
    ) {
        super.visit(version, access.widen().removeFinal(), name, signature, superName, interfaces)
    }

    override fun visitMethod(
        access: Int,
        name: String,
        descriptor: String,
        signature: String?,
        exceptions: Array<String>?
    ): MethodVisitor = super.visitMethod(access.widen().removeFinal(), name, descriptor, signature, exceptions)

    override fun visitField(
        access: Int,
        name: String,
        descriptor: String,
        signature: String?,
        value: Any?
    ): FieldVisitor = super.visitField(access.widen(), name, descriptor, signature, value)
}

/**
 * A [ClassRemapper] that is aware of the remapping of Invoke Dynamic instructions for lambdas.
 */
public class LambdaAwareRemapper(parent: ClassVisitor, remapper: Remapper) : ClassRemapper(Opcodes.ASM9, parent, remapper) {
    override fun createMethodRemapper(parent: MethodVisitor): MethodRemapper =
        LambdaAwareMethodRemapper(parent, remapper)
}

/**
 * A [MethodRemapper] that is aware of the remapping of Invoke Dynamic instructions for lambdas.
 */
public class LambdaAwareMethodRemapper(
    private val parent: MethodVisitor,
    remapper: Remapper
) : MethodRemapper(Opcodes.ASM9, parent, remapper) {
    override fun visitInvokeDynamicInsn(name: String, descriptor: String, handle: Handle, vararg args: Any) {
        val remappedName = if (
            handle.owner == "java/lang/invoke/LambdaMetafactory" &&
            (handle.name == "metafactory" || handle.name == "altMetafactory")
        ) {
            // Lambda, so we need to rename it... weird edge case, maybe ASM issue?
            // LambdaMetafactory just causes an IncompatibleClassChangeError if the lambda is invalid
            // Does it assume correct compile time? odd.
            remapper.mapMethodName(
                Type.getReturnType(descriptor).internalName,
                name,
                (args.first() as Type).descriptor
            )
        } else name

        parent.visitInvokeDynamicInsn(
            remappedName,
            remapper.mapMethodDesc(descriptor),
            remapper.mapValue(handle) as Handle,
            *args.map { remapper.mapValue(it) }.toTypedArray()
        )
    }
}

/**
 * A [Remapper] for [Mappings], which is capable of using inheritance information from classes
 * (the implementor may choose to cache them)
 * to resolve mapping data.
 */
public class MappingsRemapper(
    public val mappings: Mappings,
    public val from: String,
    public val to: String,
    private val shouldRemapDesc: Boolean = mappings.namespaces.indexOf(from) != 0,
    private val loader: (name: String) -> ByteArray?
) : Remapper() {
    private val map = mappings.asASMMapping(from, to)
    private val baseMapper by lazy {
        MappingsRemapper(mappings, from, mappings.namespaces.first(), shouldRemapDesc = false, loader)
    }

    override fun map(internalName: String): String = map[internalName] ?: internalName
    override fun mapMethodName(owner: String, name: String, desc: String): String {
        if (name == "<init>" || name == "<clinit>") return name

        // Source: https://github.com/FabricMC/tiny-remapper/blob/d14e8f99800e7f6f222f820bed04732deccf5109/src/main/java/net/fabricmc/tinyremapper/AsmRemapper.java#L74
        return if (desc.startsWith("(")) {
            val actualDesc = if (shouldRemapDesc) baseMapper.mapMethodDesc(desc) else desc
            walk(owner, name) { map["$it.$name$actualDesc"] }
        } else mapFieldName(owner, name, desc)
    }

    override fun mapFieldName(owner: String, name: String, desc: String?): String =
        walk(owner, name) { map["$it.$name"] }

    override fun mapRecordComponentName(owner: String, name: String, desc: String): String =
        mapFieldName(owner, name, desc)

    private inline fun walk(
        owner: String,
        name: String,
        applicator: (owner: String) -> String?
    ): String {
        val queue = ArrayDeque<String>()
        val seen = hashSetOf<String>()
        queue.addLast(owner)

        while (queue.isNotEmpty()) {
            val curr = queue.removeLast()
            val new = applicator(curr)
            if (new != null) return new

            val bytes = loader(curr) ?: continue
            val reader = ClassReader(bytes)

            reader.superName?.let { if (seen.add(it)) queue.addLast(it) }
            queue += reader.interfaces.filter { seen.add(it) }
        }

        return name
    }

    /**
     * Returns a [MappingsRemapper] that reverses the changes of this [MappingsRemapper].
     *
     * Note that [loader] is by default set to the already passed loader, but it might be incorrect
     * depending on the implementation of [loader] in the original [MappingsRemapper]. Make sure to pass a new
     * implementation if inheritance data matters to you and the original [loader] could not handle different
     * namespaced names.
     */
    public fun reverse(loader: (name: String) -> ByteArray? = this.loader): MappingsRemapper =
        MappingsRemapper(mappings, to, from, loader = loader)
}

/**
 * Remaps a .jar file [input] to an [output] file, using [mappings], between namespaces [from] and [to].
 * If inheritance info from classpath jars matters, you should pass all of the relevant [classpath] jars.
 */
public fun remapJar(
    mappings: Mappings,
    input: File,
    output: File,
    from: String = "official",
    to: String = "named",
    classpath: List<File> = listOf(),
) {
    val cache = hashMapOf<String, ByteArray?>()
    val jarsToUse = (classpath + input).map { JarFile(it) }
    val lookup = jarsToUse.flatMap { j ->
        j.entries().asSequence().filter { it.name.endsWith(".class") }
            .map { it.name.dropLast(6) to { j.getInputStream(it).readBytes() } }
    }.toMap()

    JarFile(input).use { jar ->
        JarOutputStream(output.outputStream()).use { out ->
            val (classes, resources) = jar.entries().asSequence().partition { it.name.endsWith(".class") }

            fun write(name: String, bytes: ByteArray) {
                out.putNextEntry(JarEntry(name))
                out.write(bytes)
            }

            resources.filterNot { it.name.endsWith(".RSA") || it.name.endsWith(".SF") }
                .forEach { write(it.name, jar.getInputStream(it).readBytes()) }

            val remapper = MappingsRemapper(
                mappings, from, to,
                loader = { name -> if (name in lookup) cache.getOrPut(name) { lookup.getValue(name)() } else null }
            )

            classes.forEach { entry ->
                val reader = ClassReader(jar.getInputStream(entry).readBytes())
                val writer = ClassWriter(reader, 0)
                reader.accept(LambdaAwareRemapper(writer, remapper), 0)

                write("${remapper.map(reader.className)}.class", writer.toByteArray())
            }
        }
    }

    jarsToUse.forEach { it.close() }
}