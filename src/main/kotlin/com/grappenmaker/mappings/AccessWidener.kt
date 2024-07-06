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
 *
 * @property owner The internal/JVMS name of the owner of the represented member
 * @property name The name of the represented member
 * @property desc The descriptor of the represented member
 */
public data class AccessedMember(val owner: String, val name: String, val desc: String)

/**
 * Represents an access widener file
 *
 * @property version The version (part of the file format but undocumented elsewhere) of the file
 * @property namespace The namespace the names of the members in this file are in
 * @property classes All masks for each "touched" class in the file
 * @property methods All masks for each "touched" method in the file
 * @property fields All masks for each "touched" field in the file
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
 *
 * @property value The integer value representing this mask
 */
@JvmInline
public value class AccessMask(public val value: Int) : Iterable<AccessType> {
    /**
     * Combines this [AccessMask] and [other] to produce a new [AccessMask] that can be interpreted as first applying
     * this [AccessMask] and then the [other].
     */
    public operator fun plus(other: AccessMask?): AccessMask =
        if (other == null) this else AccessMask(value or other.value)

    /**
     * Combines this [AccessMask] and [other] to produce a new [AccessMask] that can be interpreted as first applying
     * this [AccessMask] and then the [other] [AccessType].
     */
    public operator fun plus(other: AccessType?): AccessMask =
        if (other == null) this else AccessMask(value or other.mask)

    /**
     * Excludes all set [AccessType]s in the [other] [AccessMask] from this [AccessMask], producing a new [AccessMask]
     * that therefore contains all [AccessType]s in this [AccessMask] that are not in the [other] [AccessMask]
     */
    public operator fun minus(other: AccessMask?): AccessMask =
        if (other == null) this else AccessMask(value and other.value.inv())

    /**
     * Removes an [AccessType] from this [AccessMask], producing a new [AccessMask] that therefore contains all
     * [AccessType]s in this [AccessMask] that are not equal to [other]. If [other] is not in this [AccessMask],
     * an identical [AccessMask] will be returned.
     */
    public operator fun minus(other: AccessType?): AccessMask =
        if (other == null) this else AccessMask(value and other.mask.inv())

    /**
     * Tests whether a given [AccessType] is contained in this [AccessMask]
     */
    public operator fun contains(type: AccessType): Boolean = (value and type.mask) != 0

    /**
     * Returns an [Iterator] that yields all [AccessType]s in this [AccessMask]
     */
    override fun iterator(): Iterator<AccessType> = iterator {
        AccessType.entries.forEach { if (it in this@AccessMask) yield(it) }
    }

    /**
     * Utility for constructing an [AccessMask]
     */
    public companion object {
        /**
         * An [AccessMask] without any [AccessType]s containing it
         */
        public val EMPTY: AccessMask = AccessMask(0)
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
 *
 * @property mask The value that is equivalent to an [AccessMask] as an integer instead of a value class
 */
public enum class AccessType(public val mask: Int) {
    /**
     * Means that a member should be made accessible / public
     */
    ACCESSIBLE(0b001),

    /**
     * Means that a method or class should be made non-final and overridable/extendable. Invalid on fields.
     */
    EXTENDABLE(0b010),

    /**
     * Means that a field should be made non-final (mutable). Invalid on classes and methods.
     * Note: there is no reason for the mappings format to merge this and [EXTENDABLE] to fix the incompatibility
     * and simplify the format a little, it just is not the case.
     */
    MUTABLE(0b100);

    /**
     * Allows for easy lookup of [AccessType]s by a given [String]
     */
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

    for ((lineIdx, line) in lines.drop(1).withIndex()) {
        fun parseError(msg: String): Nothing = error("Access widener parse error at line ${lineIdx + 1}: $msg")

        val commentless = line.removeComment()
        if (commentless.isEmpty()) continue
        if (commentless.first().isWhitespace()) parseError("Leading whitespace is not allowed")

        val parts = commentless.parts()
        check(parts.size >= 2) { "Invalid entry $parts" }

        val accessPart = parts.first()
        val accessType = (AccessType.getOrNull(if (version >= 2) accessPart.removePrefix("transitive-") else accessPart)
            ?: parseError("Invalid access type $accessPart"))

        val access = accessType.toMask()

        val partsLeft = parts.drop(2)
        when (val type = parts[1]) {
            "class" -> {
                val name = partsLeft.singleOrNull() ?: parseError("Expected <name> after class, got $partsLeft")
                if (accessType == AccessType.MUTABLE) parseError("Cannot make class $name mutable")
                classes[name] = access + classes[name]
            }

            "method", "field" -> {
                if (partsLeft.size != 3) parseError("Expected <owner> <name> <desc> after $type, got $partsLeft")

                val isMethod = type == "method"
                val target = if (isMethod) methods else fields
                val (owner, name, desc) = partsLeft

                if (accessType == AccessType.MUTABLE && isMethod) parseError("Cannot make method $name$desc mutable")
                if (accessType == AccessType.EXTENDABLE && !isMethod) parseError("Cannot make field $name$desc extendable")

                val member = AccessedMember(owner, name, desc)
                target[member] = access + target[member]
            }

            else -> parseError("Invalid type $type (expected one of {class, method, field})")
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
 *
 * @property namespace The namespace the names of the members in this tree are in
 * @property classes All of the "touched" classes for each internal/JVMS class name
 */
public data class AccessWidenerTree(
    val namespace: String,
    val classes: Map<String, AccessedClass>,
)

/**
 * Similar to [AccessedMember], but without an `owner` field. Used in an [AccessedClass]
 *
 * @property name The name of the represented member
 * @property desc The descriptor of the represented member
 */
public data class MemberIdentifier(val name: String, val desc: String)

/**
 * Part of an [AccessWidenerTree]
 *
 * @property mask The mask that was configured for this class
 * @property methods All of the "touched" methods for each name-descriptor pair
 * @property fields All of the "touched" fields for each name-descriptor pair
 * @property propagated A mask of [AccessType]s that are propagated down to the class as a result of wideners in members
 * @property total The mask that should be applied to this class
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
    val allOwners = fieldsByOwner.keys + methodsByOwner.keys + classes.keys

    fun Map<String, List<Pair<AccessedMember, AccessMask>>>.transform(c: String) =
        getValue(c).associate { (member, memberMask) -> MemberIdentifier(member.name, member.desc) to memberMask }

    return AccessWidenerTree(
        namespace,
        allOwners.associateWith { c ->
            AccessedClass(classes[c] ?: AccessMask.EMPTY, methodsByOwner.transform(c), fieldsByOwner.transform(c))
        }
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

private fun throwEmptyAWsError(): Nothing =
    throw IllegalArgumentException("iterable/sequence was empty, cannot create an AccessWidener")

/**
 * Combines [AccessWidener]s using the [AccessWidener.plus] operator, producing a new [AccessWidener] that,
 * when applied, produces the same result as applying all of the wideners sequentially. If this [Iterable]
 * would be considered empty (its [Iterator.hasNext] would return false on the first iteration),
 * [IllegalArgumentException] is thrown
 *
 * @throws IllegalArgumentException if the [Iterable] is empty.
 */
public fun Iterable<AccessWidener>.join(): AccessWidener =
    reduceOrNull { acc, curr -> acc + curr } ?: throwEmptyAWsError()

/**
 * Combines this sequence of [AccessWidener]s using the [AccessWidener.plus] operator, producing a new [AccessWidener]
 * that, when applied, produces the same result as applying all of the wideners sequentially. If this [Sequence]
 * would be considered empty, [IllegalArgumentException] is thrown
 *
 * @throws IllegalArgumentException if the [Iterable] is empty.
 */
public fun Sequence<AccessWidener>.join(): AccessWidener =
    reduceOrNull { acc, curr -> acc + curr } ?: throwEmptyAWsError()