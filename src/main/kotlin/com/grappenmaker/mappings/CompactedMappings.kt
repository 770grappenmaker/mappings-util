package com.grappenmaker.mappings

import java.io.*

/**
 * Represents a compacted mappings file. See [CompactedMappingsFormat]
 *
 * @property version The version of this mappings file. Currently, this is always 1.
 */
public data class CompactedMappings(
    override val namespaces: List<String>,
    override val classes: List<MappedClass>,
    val version: Int = 1,
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
 * Represents the compacted mappings format. Note: this does not supports the usual [MappingsFormat] capability,
 * since this is the only mappings format that has a binary representation. Compacted mappings should be explicitly
 * handled through this object
 */
public data object CompactedMappingsFormat {
    private const val magic = "ACMF"
    private const val magicEncoded = 1094929734

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
    private fun InputStream.readDesc(): String {
        var isMethod = false
        var isObj = false
        val res = mutableListOf<Char>()

        while (true) {
            val curr = read().toChar()
            res += curr

            when (curr) {
                '(' -> isMethod = true
                ')' -> {
                    isMethod = false
                    continue
                }
            }

            if (isMethod) continue

            when (curr) {
                '[' -> continue
                'L' -> isObj = true
                ';' -> isObj = false
            }

            if (!isObj) break
        }

        return res.joinToString("") { it.toString() }
    }

    private fun DataInputStream.readMapped(ns: Int): SimpleMapped = SimpleMapped(
        names = List(ns) { readString() }.fixNames(),
        desc = readDesc()
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
        require(version == 1) { "Version 1 expected, found $version" }

        val namespaces = List(read()) { readString() }
        val classes = List(readInt()) {
            val names = List(namespaces.size) { readString() }.fixNames()
            val mapped = List(readVarInt()) { readMapped(namespaces.size) }
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

    private fun DataOutput.writeMapped(mapped: SimpleMapped) {
        mapped.names.unfixNames().forEach { writeString(it) }
        write(mapped.desc.encodeToByteArray())
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

    /**
     * Writes some [mappings] to a buffer that is an equivalent representation of the [mappings]
     */
    public fun writeTo(mappings: CompactedMappings, stream: OutputStream): Unit = with(DataOutputStream(stream)) {
        require(mappings.version == 1) { "Version 1 expected, found ${mappings.version}" }
        writeInt(magicEncoded)
        write(mappings.version)

        write(mappings.namespaces.size)
        mappings.namespaces.forEach { writeString(it) }

        writeInt(mappings.classes.size)
        mappings.classes.forEach { c ->
            c.names.unfixNames().forEach { writeString(it) }

            writeVarInt(c.methods.size + c.fields.size)
            c.methods.forEach { writeMapped(it.asMapped()) }
            c.fields.forEach { writeMapped(it.asMapped()) }
        }
    }
}