package com.grappenmaker.mappings

/**
 * Writes a [Sequence] of lines to an [output] stream, separated by newlines
 */
public fun Sequence<String>.writeTo(output: Appendable) {
    for (line in this) output.appendLine(line)
}