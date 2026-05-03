package com.grappenmaker.mappings

public fun Sequence<String>.writeTo(output: Appendable) {
    for (line in this) output.appendLine(line)
}