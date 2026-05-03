package com.grappenmaker.mappings

import com.grappenmaker.mappings.format.BasicSRGParser
import com.grappenmaker.mappings.format.CSRGMappingsFormat
import com.grappenmaker.mappings.format.EnigmaMappingsFormat
import com.grappenmaker.mappings.format.MappingsLoader
import com.grappenmaker.mappings.format.ProguardMappingsFormat
import com.grappenmaker.mappings.format.RecafMappingsFormat
import com.grappenmaker.mappings.format.TSRGMappingsFormat
import com.grappenmaker.mappings.format.TSRGV2MappingsFormat
import com.grappenmaker.mappings.format.TinyMappingsV2Format
import com.grappenmaker.mappings.format.TinyMappingsWriter
import com.grappenmaker.mappings.format.XSRGMappingsFormat
import com.grappenmaker.mappings.format.parse
import com.grappenmaker.mappings.format.writeLazy
import kotlin.io.path.Path
import kotlin.io.path.exists
import kotlin.io.path.inputStream
import kotlin.io.path.isDirectory
import kotlin.io.path.outputStream
import kotlin.system.exitProcess

fun eprintln() = System.err.println()
fun eprintln(msg: String) = System.err.println(msg)

fun main(args: Array<String>) {
    if (args.size < 2) {
        eprintln("Usage: <mappings> <format> [output]")
        eprintln("where <format> is one of: ")
        for (format in MappingsLoader.allMappingsFormats) println("  - \"${format.identifier}\"")

        eprintln()
        eprintln("If [output] is missing or -, defaults to stdout")
        exitProcess(1)
    }

    val outFormat = MappingsLoader.allMappingsFormats.find { it.identifier == args[1] }
    if (outFormat == null) {
        eprintln("Could not find mappings format matching identifier ${args[1]}")
        eprintln("Specify one of:")
        for (format in MappingsLoader.allMappingsFormats) println("  - \"${format.identifier}\"")
        exitProcess(1)
    }

    val inputPath = Path(args[0])
    if (!inputPath.exists()) {
        eprintln("Input file does not exist!")
        exitProcess(1)
    }

    val output = when {
        args.size <= 2 -> System.out
        args[2].trim() == "-" -> System.out
        else -> {
            val outputPath = Path(args[2])
            if (!outputPath.parent.exists()) {
                eprintln("No such file or directory: ${outputPath.parent}")
                exitProcess(1)
            }

            if (outputPath.isDirectory()) {
                eprintln("Output path is directory: $outputPath")
                exitProcess(1)
            }

            outputPath.outputStream()
        }
    }

    val input = inputPath.inputStream().bufferedReader()
    val buf = CharArray(1024)
    input.mark(buf.size)
    input.read(buf)

    val head = buf.concatToString()
    val format = MappingsLoader.findMappingsFormatOrNull(head.lines())

    if (format == null) {
        eprintln("Could not detect mappings format from the header.")
        eprintln("Excerpt:")
        eprintln(head)
        input.close()
        exitProcess(1)
    }

    input.reset()
    val mappings = format.parse(input.lineSequence())
    val bufferedWriter = output.bufferedWriter()
    when (outFormat) {
        is TinyMappingsWriter -> mappings.asTinyMappings(v2 = outFormat is TinyMappingsV2Format).writeLazy()
        is BasicSRGParser -> mappings.asSRGMappings(extended = outFormat is XSRGMappingsFormat).writeLazy()
        is ProguardMappingsFormat -> mappings.asProguardMappings().writeLazy()
        is TSRGMappingsFormat -> mappings.asTSRGMappings(v2 = outFormat is TSRGV2MappingsFormat).writeLazy()
        is CSRGMappingsFormat -> mappings.asCSRGMappings().writeLazy()
        is EnigmaMappingsFormat -> mappings.asEnigmaMappings().writeLazy()
        is RecafMappingsFormat -> mappings.asRecafMappings().writeLazy()
    }.writeTo(bufferedWriter)

    bufferedWriter.flush()
    output.close()
    input.close()
}