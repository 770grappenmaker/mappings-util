package com.grappenmaker.mappings

import java.io.*
import java.util.jar.JarFile
import kotlin.io.path.*

/**
 * Represents a compacted mappings file. See [CompactedMappingsFormat]
 *
 * @property version The version of this mappings file. Currently, this is either 1 or 2.
 */
public data class CompactedMappings(
    override val namespaces: List<String>,
    override val classes: List<MappedClass>,
    val version: Int = 2,
) : Mappings {
    init {
        assertValidDescs()
    }
}

/**
 * Writes [CompactedMappings] as its binary representation
 */
public fun CompactedMappings.write(): ByteArray = CompactedMappingsFormat.write(this)

/**
 * Writes some [mappings] to an [OutputStream] as an equivalent representation
 */
public fun CompactedMappings.writeTo(stream: OutputStream): Unit = CompactedMappingsFormat.writeTo(this, stream)

/**
 * Represents the compacted mappings format. Note: this does not support the usual [MappingsFormat] capability,
 * since this is the only mappings format that has a binary representation. Compacted mappings should be explicitly
 * handled through this object
 */
public data object CompactedMappingsFormat {
    private const val magic = "ACMF"
    private const val magicEncoded = 1094929734

    private val descReplacements = mapOf(
        "Ljava/lang/Object;" to 'A',
        "Ljava/lang/String;" to 'G',
        "Ljava/util/List;" to 'R',
    )

    private val inverseDescReplacements = descReplacements.entries.associate { (k, v) -> v to k }

    private fun List<String>.fixNames(): List<String> {
        if (isEmpty()) return emptyList()

        require(first().isNotEmpty()) { "first name is not allowed to be empty!" }
        val result = mutableListOf(first())

        drop(1).forEach { result += it.ifEmpty { result.last() } }
        return result
    }

    private data class SimpleMapped(val names: List<String>, val desc: String)

    private fun DataInputStream.readBytes(n: Int): ByteArray {
        require(n >= 0) { "Invalid amount of bytes $n" }
        if (n == 0) return byteArrayOf()

        val buf = ByteArray(n)
        read(buf, 0, buf.size)
        return buf
    }

    private fun DataInputStream.readString() = readBytes(read()).decodeToString()

    // Assumes the desc is somewhat correct, just detects the end
    private fun InputStream.readDesc(prefixes: List<String>) = buildString {
        var isMethod = false
        var isObj = false

        while (true) {
            val byte = read()
            val char = byte.toChar()

            val replacement = inverseDescReplacements[char]
            when {
                byte <= 0x1f -> append(
                    prefixes.getOrNull(byte) ?: error("Prefix index $byte requested, but not present in file")
                )

                !isObj && replacement != null -> append(replacement)
                else -> append(char)
            }

            when (char) {
                '(' -> isMethod = true
                ')' -> {
                    isMethod = false
                    continue
                }
            }

            when (char) {
                '[' -> continue
                'L' -> isObj = true
                ';' -> isObj = false
            }

            if (isMethod) continue
            if (!isObj) break
        }
    }

    private fun DataInputStream.readMapped(ns: Int, prefixes: List<String>): SimpleMapped = SimpleMapped(
        names = List(ns) { readString() }.fixNames(),
        desc = readDesc(prefixes)
    )

    private fun DataInputStream.readVarInt(): Int {
        var res = 0

        for (pos in 0..<(7 shl 2) step 7) {
            val curr = readByte().toInt()
            res = res or (curr and 0x7F shl pos)

            if (curr and 0x80 == 0) return res
        }

        error("varint too large")
    }

    /**
     * Parses some [CompactedMappings] that is an equivalent representation of the given buffer as [bytes]
     */
    public fun parse(bytes: ByteArray): CompactedMappings = parse(ByteArrayInputStream(bytes))

    /**
     * Parses some [CompactedMappings] that is an equivalent representation of a given [InputStream] (when fully read)
     */
    public fun parse(input: InputStream): CompactedMappings = with(DataInputStream(input)) {
        require(readInt() == magicEncoded) { "Invalid magic: expected $magic" }

        val version = read()
        require(version <= 2) { "Version <= 2 expected, found $version" }

        val namespaces = List(read()) { readString() }
        val prefixes = List(read()) { readString() }
        val classes = List(readInt()) {
            val names = List(namespaces.size) { readString().uncompact(prefixes) }.fixNames()
            val mapped = List(readVarInt()) { readMapped(namespaces.size, prefixes) }
            val (methods, fields) = mapped.partition { '(' in it.desc }

            MappedClass(
                names = names,
                fields = fields.map { (n, d) -> MappedField(n, emptyList(), d) },
                methods = methods.map { (n, d) -> MappedMethod(n, emptyList(), d, emptyList(), emptyList()) },
            )
        }

        CompactedMappings(namespaces, classes, version)
    }

    /**
     * see [fixNames]
     */
    @Suppress("DuplicatedCode") // would like to not unnecessarily couple this private api
    private fun List<String>.unfixNames(): List<String> {
        if (isEmpty()) return emptyList()

        val result = mutableListOf(first())
        var last = first()

        for (c in drop(1)) {
            result += if (c == last) "" else {
                last = c
                c
            }
        }

        return result
    }

    private fun DataOutput.writeString(string: String) {
        write(string.length)
        write(string.encodeToByteArray())
    }

    private fun String.compact(prefixes: List<String>) =
        prefixes.foldIndexed(this) { idx, acc, curr -> acc.replace(curr, idx.toChar().toString()) }

    private fun String.uncompact(prefixes: List<String>) = buildString {
        for (c in this@uncompact) {
            val code = c.code
            if (code <= 0x1f) append(
                prefixes.getOrNull(code) ?: error("Prefix index $code requested, but not present in file")
            ) else append(c)
        }
    }

    private fun DataOutput.writeMapped(mapped: SimpleMapped, prefixes: List<String>) {
        mapped.names.unfixNames().forEach { writeString(it) }

        val desc = descReplacements.entries.fold(mapped.desc) { acc, curr ->
            acc.replace(curr.key, curr.value.toString())
        }

        val descWithPrefixes = desc.compact(prefixes)
        write(descWithPrefixes.encodeToByteArray())
    }

    private fun MappedMethod.asMapped() = SimpleMapped(names, desc)
    private fun MappedField.asMapped() = SimpleMapped(names, desc!!)

    private fun DataOutput.writeVarInt(value: Int) {
        var curr = value

        while (true) {
            val mask = curr and 0x7F
            curr = curr ushr 7

            if (curr == 0) return writeByte(mask) else writeByte(mask or 0x80)
        }
    }

    /**
     * Writes some [mappings] to a buffer that is an equivalent representation of the [mappings]
     */
    public fun write(mappings: CompactedMappings): ByteArray =
        ByteArrayOutputStream().also { writeTo(mappings, it) }.toByteArray()

    private fun CompactedMappings.computeCompactedPrefixes(): List<String> {
        if (version < 2 || classes.size <= 1) return emptyList()

        // this is a guess
        val largerPrefix = classes.first().names.asSequence().withIndex().maxBy { it.value.length }.index
        val heatmap = hashMapOf<String, Int>()

        for (c in classes) {
            val name = c.names[largerPrefix]
            var idx = name.indexOf('/')

            while (idx >= 0) {
                val t = name.take(idx)
                heatmap[t] = (heatmap[t] ?: 0) + 1
                idx = name.indexOf('/', idx + 1)
            }
        }

        // 0x1f as upper bound because that is the last unicode value of an unprintable character in the first 0x7f
        return heatmap.entries.asSequence()
            .sortedByDescending { it.value }.take(0x1f).sortedByDescending { it.key.length }
            .map { it.key }.toList()
    }

    /**
     * Writes some [mappings] to an [OutputStream] as an equivalent representation
     */
    public fun writeTo(mappings: CompactedMappings, stream: OutputStream): Unit = with(DataOutputStream(stream)) {
        require(mappings.version <= 2) { "Version <= 2 expected, found ${mappings.version}" }
        writeInt(magicEncoded)
        write(mappings.version)

        write(mappings.namespaces.size)
        mappings.namespaces.forEach { writeString(it) }

        // compacted prefixes (empty if not v2 or higher)
        val prefixes = mappings.computeCompactedPrefixes()
        if (mappings.version >= 2) {
            write(prefixes.size)
            prefixes.forEach { writeString(it) }
        }

        writeInt(mappings.classes.size)
        mappings.classes.forEach { c ->
            c.names.unfixNames().forEach { writeString(it.compact(prefixes)) }

            writeVarInt(c.methods.size + c.fields.size)
            c.methods.forEach { writeMapped(it.asMapped(), prefixes) }
            c.fields.forEach { writeMapped(it.asMapped(), prefixes) }
        }
    }
}