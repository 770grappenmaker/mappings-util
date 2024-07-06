package com.grappenmaker.mappings

import org.objectweb.asm.ClassReader

// DFS-inspired inheritance tree search
internal inline fun walkInheritance(crossinline loader: ClasspathLoader, start: String) = sequence {
    val queue = ArrayDeque<String>()
    val seen = hashSetOf<String>()
    queue.addLast(start)

    while (queue.isNotEmpty()) {
        val curr = queue.removeLast()
        yield(curr)

        val bytes = loader(curr) ?: continue
        val reader = ClassReader(bytes)

        reader.superName?.let { if (seen.add(it)) queue.addLast(it) }
        queue += reader.interfaces.filter { seen.add(it) }
    }
}