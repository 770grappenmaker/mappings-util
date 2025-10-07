package com.grappenmaker.mappings

import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes.*
import org.objectweb.asm.Type
import org.objectweb.asm.tree.AnnotationNode
import org.objectweb.asm.tree.ClassNode
import java.io.ByteArrayInputStream
import java.io.InputStream
import kotlin.random.Random
import kotlin.random.nextUInt

internal class AnnotationContext(
    parent: ClassLoader?,
    private val classpathLoader: ClasspathLoader,
) : ClassLoader(parent) {
    private val annotationImplementationCache: MutableMap<String, Class<*>> = hashMapOf()
    private val annotationClassesCache: MutableMap<String, ClassNode> = hashMapOf()
    private fun createClass(bytes: ByteArray): Class<*> = defineClass(null, bytes, 0, bytes.size)

    private val systemClasses = setOf(
        "java.", "javax.", "org.xml.", "org.w3c.", "sun.", "jdk.", "com.sun.management."
    )

    override fun findClass(name: String): Class<*> {
        // should be handled because no override of loadClass, but just in case... we REALLY don't want to load these
        if (systemClasses.any { name.startsWith(it) }) return super.findClass(name)

        findLoadedClass(name)?.let { return it }
        getResourceAsStream("$name.class")?.let { return createClass(it.readBytes()) }

        return super.findClass(name)
    }

    override fun getResourceAsStream(name: String): InputStream? {
        if (name.endsWith(".class")) {
            val bytes = classpathLoader(name.dropLast(6))
            if (bytes != null) return ByteArrayInputStream(bytes)
        }

        return super.getResourceAsStream(name)
    }

    private fun attachValue(v: Any): Any = when (v) {
        is Type -> loadClass(v.className)
        is Array<*> -> {
            val (enumType, enumName) = v.filterIsInstance<String>()
            loadClass(enumType.replace('/', '.').drop(1).dropLast(1)).getDeclaredField(enumName)[null]
        }

        is AnnotationNode -> reflect(v)
        is List<*> -> v.map { attachValue(it!!) }
        else -> v
    }

    private fun reflect(node: AnnotationNode): Any {
        val annotationInternal = node.desc.descToInternal()
        val annotationClass = annotationClassesCache.getOrPut(annotationInternal) {
            val bytes = classpathLoader(node.desc.descToInternal())
                ?: error("Could not find annotation class in given ClasspathLoader")

            ClassNode().also { ClassReader(bytes).accept(it, ClassReader.SKIP_DEBUG) }
        }

        val type = annotationImplementationCache.getOrPut(node.desc) {
            createClass(annotationClass.implementAnnotation())
        }

        val instance = type.getConstructor().newInstance()
        val windows = (node.values ?: emptyList()).windowed(2, 2)
        val keys = windows.mapTo(hashSetOf()) { (a) -> a as String }

        for ((k, v) in windows) type.getField(k as String)[instance] = attachValue(v)
        for (defaults in annotationClass.methods) {
            if (defaults.name in keys) continue
            type.getField(defaults.name)[instance] = attachValue(defaults.annotationDefault)
        }

        return instance
    }

    inline fun <reified T : Annotation> get(node: AnnotationNode): T = reflect(node) as T
}

private fun String.descToInternal() = substring(1, lastIndex)

private inline fun MethodVisitor.implementMethod(block: MethodVisitor.() -> Unit) {
    visitCode()
    block()
    visitMaxs(-1, -1)
    visitEnd()
}

private fun ClassNode.implementAnnotation(): ByteArray {
    require(access and ACC_ANNOTATION != 0) { "Given ClassNode does not represent an annotation!" }
    return with(ClassWriter(ClassWriter.COMPUTE_MAXS)) {
        val implName = "${name.substringAfterLast('/')}\$impl\$${Random.nextUInt()}"
        visit(V1_8, ACC_PUBLIC or ACC_FINAL, implName, null, "java/lang/Object", arrayOf(name))

        visitMethod(ACC_PUBLIC or ACC_FINAL, "annotationType", "()Ljava/lang/Class;", null, null).implementMethod {
            visitLdcInsn(Type.getObjectType(implName))
            visitInsn(ARETURN)
        }

        visitMethod(ACC_PUBLIC, "<init>", "()V", null, null).implementMethod {
            visitVarInsn(ALOAD, 0)
            visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
            visitInsn(RETURN)
        }

        fun noop(name: String, desc: String) =
            visitMethod(ACC_PUBLIC or ACC_FINAL, name, desc, null, null).implementMethod {
                visitTypeInsn(NEW, "java/lang/UnsupportedOperationException")
                visitInsn(DUP)
                visitMethodInsn(INVOKESPECIAL, "java/lang/UnsupportedOperationException", "<init", "()V", false)
                visitInsn(ATHROW)
            }

        noop("equals", "(Ljava/lang/Object;)Z")
        noop("hashCode", "()I")
        noop("toString", "()Ljava/lang/String;")

        for (toImplement in methods) {
            if (toImplement.access and ACC_ABSTRACT == 0) continue

            val type = Type.getReturnType(toImplement.desc)
            val fieldDesc = type.descriptor

            visitField(ACC_PUBLIC, toImplement.name, fieldDesc, null, null).visitEnd()
            visitMethod(ACC_PUBLIC or ACC_FINAL, toImplement.name, toImplement.desc, null, null).implementMethod {
                visitVarInsn(ALOAD, 0)
                visitFieldInsn(GETFIELD, implName, toImplement.name, fieldDesc)
                visitInsn(type.getOpcode(IRETURN))
            }
        }

        toByteArray()
    }
}