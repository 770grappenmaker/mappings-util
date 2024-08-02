@file:Relocated

package com.grappenmaker.mappings.remap

import com.grappenmaker.mappings.*
import org.objectweb.asm.*
import org.objectweb.asm.commons.ClassRemapper
import org.objectweb.asm.commons.MethodRemapper
import org.objectweb.asm.commons.Remapper
import org.objectweb.asm.tree.*
import java.io.File
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream

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
 * @property mappings the mappings used for remapping
 * @property from the namespace to remap from
 * @property to the namespace to remap to
 * @property loader returns class file buffers that can be used to perform inheritance lookups
 * @see [ClasspathLoaders] for default implementations of [loader]
 */
public class MappingsRemapper(
    public val mappings: Mappings,
    public val from: String,
    public val to: String,
    private val loader: ClasspathLoader
) : LoaderSimpleRemapper(mappings.asASMMapping(from, to), loader) {
    /**
     * Returns a [MappingsRemapper] that reverses the changes of this [MappingsRemapper].
     *
     * Note that [loader] is by default set to the already passed loader, but it might be incorrect
     * depending on the implementation of [loader] in the original [MappingsRemapper]. Make sure to pass a new
     * implementation if inheritance data matters to you and the original [loader] could not handle different
     * namespaced names.
     */
    public fun reverse(loader: ClasspathLoader = this.loader): MappingsRemapper =
        MappingsRemapper(mappings, to, from, loader = loader)
}

/**
 * A [Remapper] that can use a [InheritanceProvider] for inheritance information to apply a certain [map], that should
 * be in an equivalent format as the one produced by [Mappings.asASMMapping].
 *
 * @param memoizeInheritance if `true`, the results of the [InheritanceProvider] will be memoized, see [InheritanceProvider.memoized]
 */
public open class LoaderSimpleRemapper(
    private val map: Map<String, String>,
    inheritanceProvider: InheritanceProvider,
    memoizeInheritance: Boolean = true,
) : Remapper() {
    private val backingProvider = if (memoizeInheritance) inheritanceProvider.memoized() else inheritanceProvider

    // For backwards compatibility, this still exists, I guess it serves as a nice utility function as well
    public constructor(map: Map<String, String>, loader: ClasspathLoader, memoizeInheritance: Boolean = true) :
            this(map, LoaderInheritanceProvider(loader), memoizeInheritance)

    override fun map(internalName: String): String = map[internalName] ?: if ('$' in internalName) {
        val dollarIdx = internalName.lastIndexOf('$')
        if (dollarIdx < 0) internalName else map(internalName.take(dollarIdx)) + '$' + internalName.drop(dollarIdx + 1)
    } else internalName

    override fun mapMethodName(owner: String, name: String, desc: String): String {
        if (name == "<init>" || name == "<clinit>") return name

        // Source: https://github.com/FabricMC/tiny-remapper/blob/d14e8f99800e7f6f222f820bed04732deccf5109/src/main/java/net/fabricmc/tinyremapper/AsmRemapper.java#L74
        return if (desc.startsWith('(')) walk(owner, name) { map["$it.$name$desc"] }
        else mapFieldName(owner, name, desc)
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
    ) = inheritanceProvider(owner).firstNotNullOfOrNull(applicator) ?: name

    private fun inheritanceProvider(owner: String) = sequence {
        yield(owner)
        yieldAll(backingProvider.getParents(owner))
    }
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
    loader: ClasspathLoader = { null },
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
            loader = ClasspathLoaders.compound(ClasspathLoaders.fromJar(jar).memoizedTo(commonCache), loader)
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

/**
 * Utility that can apply a [Remapper] to an [AbstractInsnNode], remapping it in place.
 * If [lambdaAware] is `true`, the remapping will behave like a [LambdaAwareRemapper]
 */
public fun AbstractInsnNode.remap(remapper: Remapper, lambdaAware: Boolean = true) {
    visibleTypeAnnotations?.remap(remapper)
    invisibleTypeAnnotations?.remap(remapper)

    when (this) {
        is FrameNode -> {
            // DRY?
            local?.mapInPlace { if (it is String) remapper.mapType(it) else it }
            stack?.mapInPlace { if (it is String) remapper.mapType(it) else it }
        }

        is FieldInsnNode -> {
            name = remapper.mapFieldName(owner, name, desc)
            desc = remapper.mapDesc(desc)
            owner = remapper.map(owner)
        }

        is MethodInsnNode -> {
            name = remapper.mapMethodName(owner, name, desc)
            desc = remapper.mapMethodDesc(desc)
            owner = remapper.mapType(owner)
        }

        is InvokeDynamicInsnNode -> {
            name = if (
                lambdaAware &&
                bsm.owner == "java/lang/invoke/LambdaMetafactory" &&
                (bsm.name == "metafactory" || bsm.name == "altMetafactory")
            ) remapper.mapMethodName(
                Type.getReturnType(desc).internalName,
                name,
                (bsmArgs.first() as Type).descriptor
            ) else remapper.mapInvokeDynamicMethodName(name, desc)

            desc = remapper.mapMethodDesc(desc)
            bsm = remapper.mapValue(bsm) as Handle
            bsmArgs.mapInPlace { remapper.mapValue(it) }
        }

        is TypeInsnNode -> desc = remapper.mapType(desc)
        is LdcInsnNode -> cst = remapper.mapValue(cst)
        is MultiANewArrayInsnNode -> desc = remapper.mapDesc(desc)
    }
}

/**
 * Utility that can apply a [Remapper] to a [MethodNode] inside of an owner represented by [ownerName],
 * remapping it in place.
 * If [lambdaAware] is `true`, the remapping will behave like a [LambdaAwareRemapper]
 */
public fun MethodNode.remap(ownerName: String, remapper: Remapper, lambdaAware: Boolean = true) {
    name = remapper.mapMethodName(ownerName, name, desc)
    desc = remapper.mapMethodDesc(desc)
    signature = signature?.let { remapper.mapSignature(it, false) }
    exceptions.mapInPlace { remapper.map(it) }
    visibleAnnotations?.remap(remapper)
    invisibleAnnotations?.remap(remapper)
    visibleTypeAnnotations?.remap(remapper)
    invisibleTypeAnnotations?.remap(remapper)
    invisibleLocalVariableAnnotations?.remap(remapper)
    visibleLocalVariableAnnotations?.remap(remapper)
    invisibleParameterAnnotations?.remap(remapper)
    visibleParameterAnnotations?.remap(remapper)
    annotationDefault = annotationDefault?.let { remapAnnotationValue(it, remapper) }
    tryCatchBlocks.forEach { tcb ->
        tcb.type = tcb.type?.let { remapper.map(it) }
        tcb.visibleTypeAnnotations?.remap(remapper)
        tcb.invisibleTypeAnnotations?.remap(remapper)
    }

    localVariables?.forEach { lv ->
        lv.desc = remapper.mapDesc(lv.desc)
        lv.signature = lv.signature?.let { remapper.mapSignature(it, true) }
    }

    instructions.forEach { it.remap(remapper, lambdaAware) }
}

/**
 * Utility that can apply a [Remapper] to a [FieldNode] inside of an owner represented by [ownerName],
 * remapping it in place
 */
public fun FieldNode.remap(ownerName: String, remapper: Remapper) {
    name = remapper.mapFieldName(ownerName, name, desc)
    desc = remapper.mapDesc(desc)
    value = remapper.mapValue(value)
    visibleAnnotations?.remap(remapper)
    invisibleAnnotations?.remap(remapper)
    visibleTypeAnnotations?.remap(remapper)
    invisibleTypeAnnotations?.remap(remapper)
    signature = signature?.let { remapper.mapSignature(it, true) }
}

/**
 * Utility that can apply a [Remapper] to a [ClassNode], remapping it in place.
 * If [lambdaAware] is `true`, the remapping will behave like a [LambdaAwareRemapper]
 */
public fun ClassNode.remap(remapper: Remapper, lambdaAware: Boolean = true) {
    // this would have been a lot simpler if there was an AnnotationHolder interface or something
    visibleAnnotations?.remap(remapper)
    invisibleAnnotations?.remap(remapper)
    visibleTypeAnnotations?.remap(remapper)
    invisibleTypeAnnotations?.remap(remapper)

    methods.forEach { it.remap(name, remapper, lambdaAware) }
    fields.forEach { it.remap(name, remapper) }

    innerClasses.forEach { ic ->
        ic.outerName = ic.outerName?.let { remapper.map(it) }
        ic.name = remapper.map(ic.name)
        if (ic.innerName != null) ic.innerName = ic.name.substringAfterLast('$')
    }

    if (outerMethod != null) {
        outerMethod = remapper.mapMethodName(outerClass!!, outerMethod, outerMethodDesc)
        outerMethodDesc = remapper.mapMethodDesc(outerMethodDesc)
    }

    outerClass = outerClass?.let { remapper.map(it) }

    superName = superName?.let { remapper.map(it) }
    interfaces.mapInPlace { remapper.map(it) }

    if (module != null) {
        module.name = remapper.map(module.name.replace('.', '/')).replace('/', '.')
        module.mainClass = module.mainClass?.let { remapper.map(it) }
        module.packages?.mapInPlace { remapper.mapPackageName(it.replace('/', '.')).replace('.', '/') }
        module.uses?.mapInPlace { remapper.map(it) }
    }

    signature = signature?.let { remapper.mapSignature(it, false) }
    nestHostClass = nestHostClass?.let { remapper.map(it) }
    nestMembers?.mapInPlace { remapper.map(it) }
    permittedSubclasses?.mapInPlace { remapper.map(it) }

    recordComponents?.forEach { rc ->
        rc.name = remapper.mapRecordComponentName(name, rc.name, rc.descriptor)
        rc.descriptor = remapper.mapDesc(rc.descriptor)
        rc.visibleAnnotations?.remap(remapper)
        rc.invisibleAnnotations?.remap(remapper)
        rc.visibleTypeAnnotations?.remap(remapper)
        rc.invisibleTypeAnnotations?.remap(remapper)
        rc.signature = rc.signature?.let { remapper.mapSignature(it, true) }
    }

    // do last so the original name is preserved for as long as we need it
    // boy do I hate mutability lol
    name = remapper.map(name)
}

/**
 * Remaps an array of lists of annotations, which is typically used to represent parameter annotations
 * @see MutableList.remap
 */
public fun Array<MutableList<out AnnotationNode>?>.remap(remapper: Remapper): Unit = forEach { it?.remap(remapper) }

/**
 * Remaps a list of annotations, which is typically used to represent a set of annotations that are applied / present
 * on a parameter, field, method, or class
 */
public fun MutableList<out AnnotationNode>.remap(remapper: Remapper): Unit = forEach { it.remap(remapper) }

// Gross descriptor because I don't want to make an extension on Any
private fun remapAnnotationValue(v: Any, remapper: Remapper): Any = when (v) {
    is Type -> v.map(remapper)
    is Array<*> -> {
        val desc = v[0] as String
        val name = v[1] as String
        val owner = desc.dropBoth(1)
        arrayOf("L${remapper.map(owner)};", remapper.mapFieldName(owner, name, desc))
    }

    is List<*> -> v.map { remapAnnotationValue(it ?: error("Invalid list entry null in annotation"), remapper) }
    is AnnotationNode -> v.remap(remapper)
    else -> v
}

/**
 * Remaps an [AnnotationNode] using a given [remapper]
 */
public fun AnnotationNode.remap(remapper: Remapper) {
    values = values?.chunked(2)?.flatMap { (k, v) ->
        // TODO: use mapMethodName instead, and try to detect descriptor of annotation value?
        listOf(remapper.mapAnnotationAttributeName(desc, k as String), remapAnnotationValue(v, remapper))
    }

    desc = "L${remapper.map(desc.dropBoth(1))};" // assuming valid annotation of course
}

private fun String.dropBoth(n: Int) = substring(n, length - n)
private fun <T> MutableList<T>.mapInPlace(block: (T) -> T) = forEachIndexed { idx, v -> this[idx] = block(v) }
private fun <T> Array<T>.mapInPlace(block: (T) -> T) = forEachIndexed { idx, v -> this[idx] = block(v) }

// Lower level util

// Why is mapType private?
/**
 * Remaps a given [Type] using a given [remapper]
 */
public fun Type.map(remapper: Remapper): Type = when (sort) {
    Type.ARRAY -> Type.getType("[".repeat(dimensions) + elementType.map(remapper).descriptor)
    Type.OBJECT -> remapper.map(internalName)?.let { Type.getObjectType(it) } ?: this
    Type.METHOD -> Type.getMethodType(remapper.mapMethodDesc(descriptor))
    else -> this
}

/**
 * Remaps a given [Type], using a map of class [names]
 */
public fun Type.map(names: Map<String, String>): Type = when (sort) {
    Type.ARRAY -> Type.getType("[".repeat(dimensions) + elementType.map(names).descriptor)
    Type.OBJECT -> names[internalName]?.let { Type.getObjectType(it) } ?: this
    Type.METHOD -> Type.getMethodType(mapMethodDesc(descriptor, names))
    else -> this
}

/**
 * Remaps a given method descriptor [desc], using a map of class [names]
 */
public fun mapMethodDesc(desc: String, names: Map<String, String>): String = buildString {
    append('(')
    for (type in Type.getArgumentTypes(desc)) append(type.map(names).descriptor)
    append(')')

    val returnType = Type.getReturnType(desc)
    append(if (returnType.isPrimitive) returnType.descriptor else returnType.map(names).descriptor)
}

/**
 * Remaps a given descriptor [desc], using a map of class [names]
 */
public fun mapDesc(desc: String, names: Map<String, String>): String = Type.getType(desc).map(names).descriptor

/**
 * Whether a given [Type] represents a JVM primitive type
 */
public val Type.isPrimitive: Boolean get() = sort != Type.OBJECT && sort != Type.METHOD && sort != Type.ARRAY