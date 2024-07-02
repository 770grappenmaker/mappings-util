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
public open class LambdaAwareRemapper(
    parent: ClassVisitor,
    remapper: Remapper
) : ClassRemapper(Opcodes.ASM9, parent, remapper) {
    override fun createMethodRemapper(parent: MethodVisitor): MethodRemapper =
        LambdaAwareMethodRemapper(parent, remapper)
}

/**
 * A [MethodRemapper] that is aware of the remapping of Invoke Dynamic instructions for lambdas.
 */
public open class LambdaAwareMethodRemapper(
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
 * (the implementor may choose to cache them) to resolve mapping data.
 *
 * Maps between [from] and [to] namespaces. If [shouldRemapDesc] is true (which it is by default if the [from]
 * namespace is not the first namespace in the mappings), this [MappingsRemapper] will remap the descriptors
 * of methods before passing them on to the mappings, in order to find the correct overload.
 *
 * [loader] should return the bytes for a class file with a given internal name, whether that is in a jar file,
 * this JVMs system class loader, or another resource. If [loader] returns `null`, the remapper considers the
 * class file not present/missing/irrelevant.
 *
 * @see [ClasspathLoaders] for default implementations of [loader]
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

    override fun map(internalName: String): String = map[internalName] ?: if ('$' in internalName) {
        map(internalName.substringBefore('$')) + '$' + internalName.substringAfter('$')
    } else internalName

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

    override fun mapSignature(signature: String?, typeSignature: Boolean): String? =
        if (signature?.isEmpty() == true) null else super.mapSignature(signature, typeSignature)

    private inline fun walk(
        owner: String,
        name: String,
        applicator: (owner: String) -> String?
    ) = walkInheritance(loader, owner).firstNotNullOfOrNull(applicator) ?: name

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
 * If inheritance info from external sources matters, you should pass a classpath [loader].
 * If additional class processing is required, an additional [visitor] can be passed.
 */
public fun remapJar(
    mappings: Mappings,
    input: File,
    output: File,
    from: String = "official",
    to: String = "named",
    loader: ((name: String) -> ByteArray?) = { null },
    visitor: ((parent: ClassVisitor) -> ClassVisitor)? = null,
): Unit = JarFile(input).use { jar ->
    val commonCache = mutableMapOf<String, ByteArray?>()

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
            loader = ClasspathLoaders.compound(ClasspathLoaders.fromJars(listOf(jar)).memoizedTo(commonCache), loader)
        )

        classes.forEach { entry ->
            val original = jar.getInputStream(entry).readBytes().also { commonCache[entry.name.dropLast(6)] = it }
            val reader = ClassReader(original)
            val writer = ClassWriter(reader, 0)

            var writtenName = remapper.map(reader.className)
            val inner = if (visitor != null) object : ClassVisitor(Opcodes.ASM9, writer) {
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
            } else writer

            val outer = if (visitor != null) visitor(inner) else inner
            reader.accept(LambdaAwareRemapper(outer, remapper), 0)
            write("$writtenName.class", writer.toByteArray())
        }
    }
}

/**
 * This function provides a shorthand for [remapJar] when using physical [files] representing a classpath,
 * and also takes cares of delegating to the system loader if necessary.
 */
public fun remapJar(
    mappings: Mappings,
    input: File,
    output: File,
    from: String = "official",
    to: String = "named",
    files: List<File>,
    visitor: ((parent: ClassVisitor) -> ClassVisitor) = { it },
) {
    val jars = files.map(::JarFile)
    val loader = ClasspathLoaders.compound(
        ClasspathLoaders.fromSystemLoader(),
        ClasspathLoaders.fromJars(jars).memoized()
    )

    remapJar(mappings, input, output, from, to, loader, visitor)
    jars.forEach { it.close() }
}