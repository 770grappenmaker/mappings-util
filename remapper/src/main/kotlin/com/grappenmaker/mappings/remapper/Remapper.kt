package com.grappenmaker.mappings.remapper

import com.grappenmaker.mappings.ClasspathLoader
import com.grappenmaker.mappings.ClasspathLoaders
import com.grappenmaker.mappings.ExperimentalJarRemapper
import com.grappenmaker.mappings.MappingsLoader
import com.grappenmaker.mappings.performRemap
import kotlinx.coroutines.runBlocking
import java.util.jar.JarFile
import kotlin.io.path.Path
import kotlin.io.path.absolute
import kotlin.io.path.exists
import kotlin.io.path.readLines
import kotlin.system.exitProcess

private const val parametersSeparator = "--"

@OptIn(ExperimentalJarRemapper::class)
fun main(args: Array<String>) {
    println()
    val sepIdx = args.indexOf(parametersSeparator)
    if (sepIdx < 0) printUsageFatal("error: use a $parametersSeparator before positional parameters")

    val options = args.take(sepIdx)
    val positionalParameters = args.drop(sepIdx + 1)
    if (parametersSeparator in positionalParameters) printUsageFatal("error: multiple $parametersSeparator were found")

    val longOptions = linkedSetOf<String>()
    val shortOptions = linkedSetOf<Char>()

    for (o in options) {
        if (o.isEmpty()) continue
        if (!o.startsWith('-')) fatalError("error: options have to start with a - character")
        if (o.length > 2 && o[1] != '-') fatalError("error: long options have to start with --")
        if (o.length == 2) shortOptions += o[1] else longOptions += o.drop(2)
    }

    fun option(long: String, short: Char) = longOptions.remove(long) || shortOptions.remove(short)

    val skipResources = option("skip-resources", 's')
    val force = option("force", 'f')
    val printStack = option("stacktrace", 'v')

    val notFoundOptions = longOptions + shortOptions
    if (notFoundOptions.isNotEmpty()) fatalError("unrecognized options: $notFoundOptions")

    if (positionalParameters.size < 5) printUsageFatal()
    val jars = positionalParameters.drop(5).map { JarFile(it.toPathOrFatal().toFile()) }

    runBlocking {
        try {
            performRemap {
                copyResources = !skipResources
                mappings = MappingsLoader.loadMappings(positionalParameters[2].toPathOrFatal().readLines())
                if (jars.isNotEmpty()) loader = ClasspathLoaders.fromJars(jars)

                task(
                    input = positionalParameters[0].toPathOrFatal(),
                    output = Path(positionalParameters[1]).absolute().also {
                        if (!it.parent.exists()) fatalError("error: no such file or directory: $it")
                        if (it.exists() && !force) fatalError("error: will not overwrite output without --force")
                    },
                    fromNamespace = positionalParameters[3],
                    toNamespace = positionalParameters[4],
                )
            }
        } catch (e: Throwable) {
            if (printStack) e.printStackTrace() else fatalError("something went wrong (--stacktrace): ${e.message}")
        }
    }

    jars.forEach { it.close() }
}

private fun printUsageFatal(msg: String? = null): Nothing {
    if (msg != null) println(msg)
    printUsage()
    exitProcess(-1)
}

private fun printUsage() {
    println("Usage: [-s | --skip-resources] [-f | --force] [-v | --stacktrace] " +
            "-- <input> <output> <mappings> <from> <to> [classpath...]")
}

private fun fatalError(msg: String): Nothing {
    println(msg)
    exitProcess(-1)
}

private fun String.toPathOrFatal() = Path(this).also { if (!it.exists()) fatalError("error: $this does not exist") }