package com.grappenmaker.mappings

import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.FieldVisitor
import org.objectweb.asm.Handle
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes.*
import org.objectweb.asm.commons.Remapper
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.InvokeDynamicInsnNode
import org.objectweb.asm.tree.MethodInsnNode

/**
 * Represents a field or method that is being widened
 */
public data class AccessedMember(val owner: String, val name: String, val desc: String)

/**
 * Represents an access widener file
 */
public data class AccessWidener(
    val version: Int,
    val namespace: String,
    val classes: Map<String, AccessMask>,
    val methods: Map<AccessedMember, AccessMask>,
    val fields: Map<AccessedMember, AccessMask>,
)

/**
 * A bitmask representing a set of [AccessType]s
 */
@JvmInline
public value class AccessMask(public val value: Int) : Iterable<AccessType> {
    public operator fun plus(other: AccessMask?): AccessMask =
        if (other == null) this else AccessMask(value or other.value)

    public operator fun plus(other: AccessType?): AccessMask =
        if (other == null) this else AccessMask(value or other.mask)

    public operator fun minus(other: AccessMask?): AccessMask =
        if (other == null) this else AccessMask(value and other.value.inv())

    public operator fun minus(other: AccessType?): AccessMask =
        if (other == null) this else AccessMask(value and other.mask.inv())

    public operator fun contains(type: AccessType): Boolean = (value and type.mask) != 0
    override fun iterator(): Iterator<AccessType> = iterator {
        AccessType.entries.forEach { if (it in this@AccessMask) yield(it) }
    }
}

/**
 * Converts a set of [AccessType]s to an [AccessMask] representing it
 */
public fun Set<AccessType>.toMask(): AccessMask = AccessMask(fold(0) { acc, curr -> acc or curr.mask })

/**
 * Converts some sort of collection of [AccessMask]s to a resultant [AccessMask] combining them
 */
public fun Iterable<AccessMask>.join(): AccessMask = reduce { acc, curr -> acc + curr }

/**
 * A type of access that can be "widened" on a class, field or method
 */
public enum class AccessType(public val mask: Int) {
    ACCESSIBLE(0b001), EXTENDABLE(0b010), MUTABLE(0b100);

    public companion object {
        /**
         * Returns an [AccessType] associated with a given [name], throws [IllegalArgumentException] when [name] is
         * not associated with any [AccessType]
         */
        public operator fun invoke(name: String): AccessType = enumValueOf<AccessType>(name.uppercase())

        /**
         * Returns an [AccessType] associated with a given [name], or `null` when [name] is not associated with
         * any [AccessType]
         */
        public fun getOrNull(name: String): AccessType? = runCatching { invoke(name) }.getOrNull()
    }

    /**
     * Creates an [AccessMask] containing only this [AccessType]
     */
    public fun toMask(): AccessMask = AccessMask(mask)
}

private val whitespaceRegex = """\s+""".toRegex()
private fun String.splitWhitespace() = split(whitespaceRegex)
private fun String.splitWhitespaceStrict() = split('\t', ' ')

/**
 * Reads a file represented by [lines] as an [AccessWidener]
 */
public fun loadAccessWidener(lines: List<String>): AccessWidener {
    require(lines.isNotEmpty()) { "Empty file" }

    val (id, versionPart, namespace) = lines.first().splitWhitespace()
    check(id == "accessWidener") { "Invalid accessWidener file" }
    check(versionPart.first() == 'v') { "Expected access widener version to start with 'v'" }

    val version = versionPart.drop(1).singleOrNull()?.digitToIntOrNull()
        ?: error("Invalid access widener version $versionPart")

    fun String.parts() = if (version < 2) splitWhitespace() else splitWhitespaceStrict()
    fun String.removeComment() = substringBefore('#').let { if (version < 2) it.trim() else it }.trimEnd()

    val classes = hashMapOf<String, AccessMask>()
    val methods = hashMapOf<AccessedMember, AccessMask>()
    val fields = hashMapOf<AccessedMember, AccessMask>()

    for (line in lines.drop(1)) {
        val commentless = line.removeComment()
        if (commentless.isEmpty()) continue
        if (commentless.first().isWhitespace()) error("Leading whitespace is not allowed")

        val parts = commentless.parts()
        check(parts.size >= 2) { "Invalid entry $parts" }

        val accessPart = parts.first()
        val accessType = (AccessType.getOrNull(if (version >= 2) accessPart.removePrefix("transitive-") else accessPart)
            ?: error("Invalid access type $accessPart"))

        val access = accessType.toMask()

        val partsLeft = parts.drop(2)
        when (val type = parts[1]) {
            "class" -> {
                val name = partsLeft.singleOrNull() ?: error("Expected <name> after class, got $partsLeft")
                if (accessType == AccessType.MUTABLE) error("Cannot make class $name mutable")
                classes[name] = access + classes[name]
            }

            "method", "field" -> {
                check(partsLeft.size == 3) { "Expected <owner> <name> <desc> after $type, got $partsLeft" }

                val isMethod = type == "method"
                val target = if (isMethod) methods else fields
                val (owner, name, desc) = partsLeft

                if (accessType == AccessType.MUTABLE && isMethod) error("Cannot make method $name$desc mutable")
                if (accessType == AccessType.EXTENDABLE && !isMethod) error("Cannot make field $name$desc extendable")

                val member = AccessedMember(owner, name, desc)
                target[member] = access + target[member]
            }

            else -> error("Invalid type $type (expected one of {class, method, field})")
        }
    }

    return AccessWidener(version, namespace, classes, methods, fields)
}

/**
 * Remaps this [AccessWidener] using [mappings] to a new namespace [toNamespace] to produce a new [AccessWidener]
 */
public fun AccessWidener.remap(mappings: Mappings, toNamespace: String): AccessWidener =
    if (toNamespace == namespace) this
    else remap(MappingsRemapper(mappings, namespace, toNamespace) { null }, toNamespace)

/**
 * Remaps this [AccessWidener] using a [remapper] to a new namespace [toNamespace] to produce a new [AccessWidener]
 */
public fun AccessWidener.remap(remapper: Remapper, toNamespace: String): AccessWidener {
    if (toNamespace == namespace) return this

    fun AccessedMember.remap() = AccessedMember(
        owner = remapper.map(owner),
        name = remapper.mapMethodName(owner, name, desc), // abusing the hotfix for fabric-remapper
        desc = if (desc.startsWith('(')) remapper.mapMethodDesc(desc) else remapper.mapDesc(desc)
    )

    return copy(
        namespace = toNamespace,
        classes = classes.mapKeys { (k) -> remapper.map(k) },
        methods = methods.mapKeys { (k) -> k.remap() },
        fields = fields.mapKeys { (k) -> k.remap() },
    )
}

/**
 * Writes this [AccessWidener] structure to a file representing this [AccessWidener]
 */
public fun AccessWidener.write(): List<String> = buildList {
    fun line(vararg parts: String) = add(parts.joinToString("\t"))

    line("accessWidener", "v$version", namespace)
    classes.forEach { (k, v) -> v.forEach { line(it.name.lowercase(), "class", k) } }

    fun Map<AccessedMember, AccessMask>.write(name: String) =
        forEach { (k, v) -> v.forEach { line(it.name.lowercase(), name, k.owner, k.name, k.desc) } }

    fields.write("field")
    methods.write("method")
}

/**
 * A tree-like structure that is easier to use when access widening one or more classes
 */
public data class AccessWidenerTree(
    val namespace: String,
    val classes: Map<String, AccessedClass>,
)

/**
 * Similar to [AccessedMember], but without an `owner` field. Used in an [AccessedClass]
 */
public data class MemberIdentifier(val name: String, val desc: String)

/**
 * Part of an [AccessWidenerTree]
 */
public data class AccessedClass(
    val mask: AccessMask,
    val methods: Map<MemberIdentifier, AccessMask>,
    val fields: Map<MemberIdentifier, AccessMask>,
) {
    val propagated: AccessMask get() = (methods.values + fields.values).join() - AccessType.MUTABLE
    val total: AccessMask get() = mask + propagated
}

/**
 * Converts an [AccessWidener] to an [AccessWidenerTree]
 */
public fun AccessWidener.toTree(): AccessWidenerTree {
    val fieldsByOwner = fields.toList().groupBy { it.first.owner }.withDefault { emptyList() }
    val methodsByOwner = methods.toList().groupBy { it.first.owner }.withDefault { emptyList() }

    fun Map<String, List<Pair<AccessedMember, AccessMask>>>.transform(c: String) =
        getValue(c).associate { (member, memberMask) -> MemberIdentifier(member.name, member.desc) to memberMask }

    return AccessWidenerTree(
        namespace,
        classes.mapValues { (c, m) -> AccessedClass(m, methodsByOwner.transform(c), fieldsByOwner.transform(c)) }
    )
}

/**
 * Applies an [AccessWidenerTree] to a given [ClassNode]
 */
public fun ClassNode.applyWidener(tree: AccessWidenerTree) {
    val classTree = tree.classes[name] ?: return
    val classAccess = access

    operator fun AccessApplicator.invoke(mask: AccessMask?, originalAccess: Int, name: String) =
        mask?.fold(originalAccess) { acc, curr -> apply(curr, acc, name, classAccess) } ?: originalAccess

    fun shouldWiden(owner: String, memberName: String, desc: String) =
        memberName != "<init>" && owner == name && MemberIdentifier(memberName, desc) in classTree.methods

    access = ClassApplicator(classTree.total, access, name)
    innerClasses.forEach { it.access = ClassApplicator(tree.classes[it.name]?.total, it.access, it.name) }
    fields.forEach {
        it.access = FieldApplicator(classTree.fields[MemberIdentifier(it.name, it.desc)], it.access, it.name)
    }

    methods.forEach {
        it.access = MethodApplicator(classTree.methods[MemberIdentifier(it.name, it.desc)], it.access, it.name)
        it.instructions.forEach { insn ->
            when (insn) {
                // DRY
                is InvokeDynamicInsnNode -> insn.bsmArgs = insn.bsmArgs.map { arg ->
                    if (arg is Handle && arg.tag == H_INVOKESPECIAL && shouldWiden(arg.owner, arg.name, arg.desc)) {
                        Handle(H_INVOKEVIRTUAL, arg.owner, arg.name, arg.desc, arg.isInterface)
                    } else arg
                }.toTypedArray()

                is MethodInsnNode -> if (
                    insn.opcode == INVOKESPECIAL &&
                    shouldWiden(insn.owner, insn.name, insn.desc)
                ) insn.opcode = INVOKEVIRTUAL
            }
        }
    }

    if (AccessType.EXTENDABLE in classTree.mask) permittedSubclasses.clear()
}

/**
 * [ClassVisitor] that can handle access widening tasks
 */
public class AccessWidenerVisitor(
    parent: ClassVisitor?,
    private val tree: AccessWidenerTree
) : ClassVisitor(ASM9, parent) {
    private var classAccess = 0
    private var className: String? = null
    private var classTree: AccessedClass? = null

    override fun visit(
        version: Int,
        access: Int,
        name: String,
        signature: String?,
        superName: String?,
        interfaces: Array<String>?
    ) {
        classAccess = access
        className = name
        classTree = tree.classes[name]

        val newAccess = ClassApplicator(classTree?.total, access, name)
        super.visit(version, newAccess, name, signature, superName, interfaces)
    }

    override fun visitInnerClass(name: String, outerName: String?, innerName: String?, access: Int) {
        val newAccess = ClassApplicator(tree.classes[name]?.total, access, name)
        super.visitInnerClass(name, outerName, innerName, newAccess)
    }

    override fun visitField(
        access: Int,
        name: String,
        descriptor: String,
        signature: String?,
        value: Any?
    ): FieldVisitor? {
        val newAccess = FieldApplicator(classTree?.fields?.get(MemberIdentifier(name, descriptor)), access, name)
        return super.visitField(newAccess, name, descriptor, signature, value)
    }

    override fun visitMethod(
        access: Int,
        name: String,
        descriptor: String,
        signature: String?,
        exceptions: Array<String>?
    ): MethodVisitor {
        val newAccess = MethodApplicator(classTree?.methods?.get(MemberIdentifier(name, descriptor)), access, name)
        return MethodWrapper(super.visitMethod(newAccess, name, descriptor, signature, exceptions))
    }

    override fun visitPermittedSubclass(permittedSubclass: String?) {
        val mask = classTree?.mask
        if (mask != null && AccessType.EXTENDABLE in mask) return

        super.visitPermittedSubclass(permittedSubclass)
    }

    private operator fun AccessApplicator.invoke(mask: AccessMask?, access: Int, name: String) =
        mask?.fold(access) { acc, curr -> apply(curr, acc, name, classAccess) } ?: access

    // bad code xd
    private inner class MethodWrapper(parent: MethodVisitor?) : MethodVisitor(ASM9, parent) {
        fun shouldWiden(owner: String, name: String, desc: String) = name != "<init>" && owner == className &&
                classTree?.let { MemberIdentifier(name, desc) in it.methods } == true

        override fun visitMethodInsn(
            opcode: Int,
            owner: String,
            name: String,
            descriptor: String,
            isInterface: Boolean
        ) {
            val newOp = if (opcode == INVOKESPECIAL && shouldWiden(owner, name, descriptor)) INVOKEVIRTUAL else opcode
            super.visitMethodInsn(newOp, owner, name, descriptor, isInterface)
        }

        override fun visitInvokeDynamicInsn(
            name: String?,
            descriptor: String?,
            bootstrapMethodHandle: Handle?,
            vararg bootstrapMethodArguments: Any?
        ) {
            val newArgs = bootstrapMethodArguments.map { arg ->
                if (arg is Handle && arg.tag == H_INVOKESPECIAL && shouldWiden(arg.owner, arg.name, arg.desc)) {
                    Handle(H_INVOKEVIRTUAL, arg.owner, arg.name, arg.desc, arg.isInterface)
                } else arg
            }.toTypedArray()

            super.visitInvokeDynamicInsn(name, descriptor, bootstrapMethodHandle, *newArgs)
        }
    }
}

private sealed interface AccessApplicator {
    fun apply(type: AccessType, access: Int, name: String, classAccess: Int): Int
    fun Int.accessible() = this and (ACC_PRIVATE or ACC_PROTECTED).inv() or ACC_PUBLIC
    fun Int.extendable() = if (this and ACC_PUBLIC != 0) this else this and ACC_PRIVATE.inv() or ACC_PROTECTED
    fun Int.mutable() = this and ACC_FINAL.inv()
}

private data object ClassApplicator : AccessApplicator {
    override fun apply(type: AccessType, access: Int, name: String, classAccess: Int) = when (type) {
        AccessType.ACCESSIBLE -> access.accessible()
        AccessType.EXTENDABLE -> access.accessible().mutable()
        AccessType.MUTABLE -> error("Cannot make a class mutable")
    }
}

private data object MethodApplicator : AccessApplicator {
    override fun apply(type: AccessType, access: Int, name: String, classAccess: Int) = when (type) {
        AccessType.ACCESSIBLE -> accessibleMethod(access, name, classAccess).accessible()
        AccessType.EXTENDABLE -> access.extendable().mutable()
        AccessType.MUTABLE -> error("Cannot make a method mutable")
    }

    fun accessibleMethod(access: Int, name: String, classAccess: Int) = when {
        name == "<init>" || classAccess and ACC_INTERFACE != 0 || access and ACC_STATIC != 0 -> access
        (access and ACC_PRIVATE) != 0 -> access or ACC_FINAL
        else -> access
    }
}

private data object FieldApplicator : AccessApplicator {
    override fun apply(type: AccessType, access: Int, name: String, classAccess: Int) = when (type) {
        AccessType.ACCESSIBLE -> access.accessible()
        AccessType.EXTENDABLE -> error("Cannot make a field extendable")
        AccessType.MUTABLE ->
            if (classAccess and ACC_INTERFACE != 0 && access and ACC_STATIC != 0) access
            else access.mutable()
    }
}

/**
 * Combines this [AccessWidener] with [other], returning a new access widener that, when applied,
 * produces the same result as applying one widener and then the other.
 */
public operator fun AccessWidener.plus(other: AccessWidener): AccessWidener {
    check(namespace == other.namespace) { "Namespaces do not match! Expected $namespace, found ${other.namespace}" }

    fun <K> MutableMap<K, AccessMask>.merge(other: Map<K, AccessMask>) =
        other.forEach { (k, v) -> this[k] = v + this[k] }

    return AccessWidener(
        minOf(version, other.version),
        namespace,
        classes.toMutableMap().apply { merge(other.classes) },
        methods.toMutableMap().apply { merge(other.methods) },
        fields.toMutableMap().apply { merge(other.fields) }
    )
}

/**
 * Combines [AccessWidener]s using the [AccessWidener.plus] operator, producing a new [AccessWidener] that,
 * when applied, produces the same result as applying all of the wideners sequentially
 */
public fun Iterable<AccessWidener>.join(): AccessWidener = reduce { acc, curr -> acc + curr }

/**
 * Combines this sequence of [AccessWidener]s using the [AccessWidener.plus] operator, producing a new [AccessWidener]
 * that, when applied, produces the same result as applying all of the wideners sequentially
 */
public fun Sequence<AccessWidener>.join(): AccessWidener = reduce { acc, curr -> acc + curr }