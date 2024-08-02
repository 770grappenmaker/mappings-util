import com.google.devtools.ksp.gradle.KspTaskJvm
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.commons.Remapper
import org.objectweb.asm.commons.SimpleRemapper
import org.objectweb.asm.tree.ClassNode
import kotlin.io.path.createDirectories
import kotlin.io.path.writeBytes
import kotlin.metadata.KmClass
import kotlin.metadata.Visibility
import kotlin.metadata.jvm.JvmMetadataVersion
import kotlin.metadata.jvm.KotlinClassMetadata
import kotlin.metadata.visibility

plugins {
    id("com.google.devtools.ksp")
    id("kotlin-convention")
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(project(":relocator"))
    ksp(project(":relocator"))
}

tasks {
    afterEvaluate {
        val main by sourceSets.getting
        val kspKotlin by getting(KspTaskJvm::class)
        val compileKotlin by getting(KotlinCompile::class)

        val generateStubs by registering(GenerateStubs::class) {
            dependsOn(compileKotlin)
            mapping = kspKotlin.destination.map { it.resolve("resources").resolve("incompatible-types.txt") }
            compilerOutput = compileKotlin.destinationDirectory
            output = layout.buildDirectory.get().dir("classes").dir("stubs").dir(main.name)
        }

        (main.output.classesDirs as ConfigurableFileCollection).from(generateStubs)
    }
}

abstract class GenerateStubs : DefaultTask() {
    @get:InputFile
    abstract val mapping: RegularFileProperty

    @get:InputDirectory
    abstract val compilerOutput: DirectoryProperty

    @get:OutputDirectory
    abstract val output: DirectoryProperty

    @TaskAction
    fun generate() {
        val file = mapping.get().asFile
        if (!file.exists()) return

        val compilerOutputDir = compilerOutput.get().asFile
        val outputDir = output.get().asFile

        val mapping = file.useLines { lines ->
            lines.map { it.trim() }.filter { it.isNotEmpty() }.associate {
                val parts = it.split('=')
                parts[1] to parts[0]
            }
        }

        val remapper = SimpleRemapper(mapping)

        for ((internalOriginalName, internalStubName) in mapping) {
            val stubOutput = outputDir.resolveClass(internalStubName).toPath()
            val originalClassFile = compilerOutputDir.resolveClass(internalOriginalName)

            if (!originalClassFile.exists()) error("Relocated member was never compiled: $internalOriginalName")
            stubOutput.also { it.parent.createDirectories() }.writeBytes(
                generateStub(internalStubName, internalOriginalName, originalClassFile, remapper)
            )
        }
    }

    private fun generateStub(
        internalStubName: String,
        internalOriginalName: String,
        originalClassFile: File,
        remapper: Remapper,
    ): ByteArray {
        // old-fashioned bytecode weaving baby
        val reader = ClassReader(originalClassFile.inputStream())
        val node = ClassNode()
        reader.accept(node, ClassReader.SKIP_CODE or ClassReader.SKIP_DEBUG)

        return with(ClassWriter(0)) {
            visit(
                /* version = */ Opcodes.V1_8,
                /* access = */ node.access or Opcodes.ACC_SYNTHETIC,
                /* name = */ internalStubName,
                /* signature = */ null,
                /* superName = */ remapper.map(node.superName) ?: node.superName,
                /* interfaces = */ node.interfaces.map { remapper.map(it) ?: it }
                    .toTypedArray().takeIf { it.isNotEmpty() }
            )

            visitMetadata(KotlinClassMetadata.Class(
                KmClass().apply {
                    name = "${internalStubName}_Compat"
                    visibility = Visibility.INTERNAL
                },
                JvmMetadataVersion.LATEST_STABLE_SUPPORTED,
                flags = 0
            ).write(), 3)

            for (field in node.fields) visitField(
                /* access = */ field.access,
                /* name = */ field.name,
                /* descriptor = */ remapper.mapDesc(field.desc),
                /* signature = */ null,
                /* value = */ null
            ).visitEnd()

            for (method in node.methods) with(
                visitMethod(
                    /* access = */ method.access,
                    /* name = */ method.name,
                    /* descriptor = */ remapper.mapMethodDesc(method.desc),
                    /* signature = */ null,
                    /* exceptions = */ null
                )
            ) {
                var locals = Type.getArgumentCount(method.desc)
                if (method.access and Opcodes.ACC_STATIC == 0) locals++

                visitCode()
                visitInsn(Opcodes.ACONST_NULL)
                visitInsn(Opcodes.ATHROW)
                visitMaxs(1, locals)
                visitEnd()
            }

            with(visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null)) {
                visitCode()

                visitTypeInsn(Opcodes.NEW, "java/lang/UnsupportedOperationException")
                visitInsn(Opcodes.DUP)
                visitLdcInsn(
                    "This class is deprecated in favour of $internalOriginalName, please update your dependencies!"
                )

                visitMethodInsn(
                    /* opcode = */ Opcodes.INVOKESPECIAL,
                    /* owner = */ "java/lang/UnsupportedOperationException",
                    /* name = */ "<init>",
                    /* descriptor = */ "(Ljava/lang/String;)V",
                    /* isInterface = */ false
                )

                visitInsn(Opcodes.ATHROW)

                visitMaxs(3, 0)
                visitEnd()
            }

            visitEnd()
            toByteArray()
        }
    }

    private fun ClassVisitor.visitMetadata(
        annotation: Metadata,
        kind: Int = annotation.kind,
    ) = with(visitAnnotation("Lkotlin/Metadata;", true)) {
        visit("mv", annotation.metadataVersion)
        visit("xi", annotation.extraInt)
        visit("xs", annotation.extraString)
        visit("k", kind)
        visit("bv", annotation.bytecodeVersion)
        visit("pn", annotation.packageName)
        with(visitArray("d1")) { annotation.data1.forEach { visit(null, it) }; visitEnd() }
        with(visitArray("d2")) { annotation.data2.forEach { visit(null, it) }; visitEnd() }
        visitEnd()
    }

    private fun File.resolveClass(internalName: String) =
        "$internalName.class".split('/').fold(this) { acc, curr -> acc.resolve(curr) }
}