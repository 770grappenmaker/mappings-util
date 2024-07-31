package com.grappenmaker.mappings

import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

/**
 * Represents the bitmask of access flags that a member may not have to be considered inheritable,
 * see [InheritanceProvider.getDeclaredMethods]
 */
public const val INHERITABLE_MASK: Int = 0b11010

/**
 * Represents an entry point for tasks like remapping and transformations to gather inheritance information from the
 * classpath. Its simple design allows implementations to fetch information from different kinds of resources, like
 * the classpath (see [LoaderInheritanceProvider]).
 */
public interface InheritanceProvider {
    /**
     * Returns the direct parents of a class with a given [internalName], that is, it returns an iterable
     * of its super class (if any) and its superinterfaces, in that order.
     */
    public fun getDirectParents(internalName: String): Iterable<String>

    /**
     * Returns the internal names of the parents of a class with a given [internalName], in depth-first order,
     * interfaces first.
     *
     * Suppose we have the following class definitions:
     *
     * class A extends B implements C, D
     * class B extends E
     *
     * Then, calling this method on A should return {D, C, E} ({C, D, E} is also valid).
     *
     * The default implementation performs depth-first search with pruning over [getDirectParents].
     * Implementations are welcome to optimize it for their specific use-case.
     */
    public fun getParents(internalName: String): Iterable<String> = sequence {
        val queue = ArrayDeque<String>()
        val seen = hashSetOf<String>()
        queue.addLast(internalName)

        while (queue.isNotEmpty()) {
            val curr = queue.removeLast()
            yield(curr)
            getDirectParents(internalName).forEach { if (seen.add(it)) queue.addLast(it) }
        }
    }.drop(1).asIterable()

    /**
     * Returns the **signatures** of the methods declared in a class with a given [internalName].
     *
     * A signature is a string that represents a method by concatenating its name with its JVMS descriptor
     * For example: valueOf(Ljava/lang/String;)Lsome/EnumType;
     *
     * A declared method is a method that is directly declared in the bytecode of the class.
     *
     * If [filterInheritable] is true, this method should only return the signatures of methods that are non-private,
     * non-static, non-final. That is, access & (ACC_PRIVATE | ACC_STATIC | ACC_FINAL) == 0, where access is the
     * bitset of access flags as defined by the JVMS. The convienience constant [INHERITABLE_MASK] can be used for
     * this computation as well, by comparing access & [INHERITABLE_MASK] == 0
     */
    // TODO: find out if we want to put this into a datastructure
    // the thing is that these signatures are probably not supposed to be parsed, rather compared
    // therefore it would probably be nicest not to force api consumers to use those data structures
    public fun getDeclaredMethods(internalName: String, filterInheritable: Boolean): Iterable<String>
}

/**
 * An [InheritanceProvider] that delegates to another given [InheritanceProvider], [delegate], and remembers its results.
 */
public class MemoizedInheritanceProvider(private val delegate: InheritanceProvider) : InheritanceProvider {
    private val inheritanceMemo = hashMapOf<String, List<String>>()
    private val inheritableMethodsMemo = hashMapOf<String, List<String>>()
    private val declaredMethodsMemo = hashMapOf<String, List<String>>()

    override fun getDirectParents(internalName: String): Iterable<String> =
        inheritanceMemo.getOrPut(internalName) { delegate.getDirectParents(internalName).toList() }

    override fun getDeclaredMethods(internalName: String, filterInheritable: Boolean): Iterable<String> {
        val target = if (filterInheritable) inheritableMethodsMemo else declaredMethodsMemo
        return target.getOrPut(internalName) { delegate.getDeclaredMethods(internalName, filterInheritable).toList() }
    }
}

/**
 * An [InheritanceProvider] that delegates to a [ClasspathLoader], [loader], to extract inheritance information
 */
public class LoaderInheritanceProvider(private val loader: ClasspathLoader) : InheritanceProvider {
    override fun getDirectParents(internalName: String): Iterable<String> {
        val bytes = loader(internalName) ?: return emptyList()
        val reader = ClassReader(bytes)

        return sequence {
            if (reader.superName != null) yield(reader.superName)
            yieldAll(reader.interfaces.iterator())
        }.asIterable()
    }

    override fun getDeclaredMethods(internalName: String, filterInheritable: Boolean): Iterable<String> {
        val bytes = loader(internalName) ?: return emptyList()

        val result = mutableListOf<String>()
        val visitor = object : ClassVisitor(Opcodes.ASM9) {
            override fun visitMethod(
                access: Int,
                name: String,
                descriptor: String,
                signature: String?,
                exceptions: Array<out String>?
            ): MethodVisitor? {
                if (!filterInheritable || access and INHERITABLE_MASK == 0) result += (name + descriptor)
                return null
            }
        }

        ClassReader(bytes).accept(visitor, ClassReader.SKIP_CODE or ClassReader.SKIP_DEBUG)
        return result
    }
}

/**
 * Wraps [this] [InheritanceProvider] into a new provider that remembers the results of calls
 */
public fun InheritanceProvider.memoized(): InheritanceProvider = MemoizedInheritanceProvider(this)

/**
 * Wraps [this] [ClasspathLoader] into a [InheritanceProvider] that uses [this] to extract inheritance information
 */
public fun ClasspathLoader.asInheritanceProvider(): InheritanceProvider = LoaderInheritanceProvider(this)