@file:OptIn(KspExperimental::class)

package com.grappenmaker.mappings

import com.google.devtools.ksp.KspExperimental
import com.google.devtools.ksp.containingFile
import com.google.devtools.ksp.getAnnotationsByType
import com.google.devtools.ksp.processing.*
import com.google.devtools.ksp.symbol.*
import com.google.devtools.ksp.symbol.Modifier.*
import com.squareup.kotlinpoet.*
import com.squareup.kotlinpoet.ksp.*

class MappingsRelocatorProvider : SymbolProcessorProvider {
    override fun create(environment: SymbolProcessorEnvironment) = MappingsRelocatorProcessor(environment.codeGenerator)
}

private const val originalMonoPackage = "com.grappenmaker.mappings"

class MappingsRelocatorProcessor(private val generator: CodeGenerator) : SymbolProcessor {
    private val KSAnnotated.level
        get() = getAnnotationsByType(Relocated::class).singleOrNull()?.level ?: DeprecationLevel.WARNING

    private fun maxOf(lhs: DeprecationLevel, rhs: DeprecationLevel) = if (lhs > rhs) lhs else rhs

    override fun process(resolver: Resolver): List<KSAnnotated> {
        val symbols = resolver.getSymbolsWithAnnotation(Relocated::class.java.name)
        val toProcess = symbols.toMutableList()
        for (symbol in symbols) if (symbol is KSFile) toProcess += symbol.declarations

        val groups = toProcess.groupBy { it.containingFile }
        for ((file, children) in groups) {
            if (file == null || file.packageName.asString() == originalMonoPackage) continue

            val baseDeprecation = file.level
            val builder = FileSpec.builder(originalMonoPackage, file.fileName.removeSuffix(".kt"))
                .indent("    ")
                .addFileComment("This file contains auto-generated binary compatibility-preserving delegates\n")
                .addFileComment("Do not modify!")

            children.fold(builder) { acc, curr ->
                curr.process(acc, maxOf(baseDeprecation, curr.level))
            }.build().writeTo(generator, aggregating = false)
        }

        return (groups[null] ?: emptyList()).filterNot { it is KSFile }
    }

    private fun <T : Annotatable.Builder<T>> T.deprecate(
        replaceWith: String? = null,
        newMemberName: String? = null,
        minLevel: DeprecationLevel
    ) = addAnnotation(
        AnnotationSpec.builder(Deprecated::class)
            .addMember("message = %S", "This member has been deprecated in favour of its relocated counterpart")
            .addMember("level = %M", MemberName(ClassName("kotlin", "DeprecationLevel"), minLevel.name))
            .apply {
                if (replaceWith != null) addMember(
                    "replaceWith = %L",
                    AnnotationSpec.builder(ReplaceWith::class)
                        .addMember("expression = %S", replaceWith)
                        .apply { if (newMemberName != null) addMember("imports = [%S]", newMemberName) }
                        .build()
                )
            }.build()
    )

    private val KSModifierListOwner.isVisible
        get() = PRIVATE !in modifiers && INTERNAL !in modifiers && PROTECTED !in modifiers

    private fun Iterable<Modifier>.asKModifiers() = mapNotNull { it.toKModifier() }
    private fun Iterable<KSTypeParameter>.asTypeVariables() = map { it.toTypeVariableName() }

    private val KSValueParameter.modifiers: Sequence<Modifier>
        get() = sequence {
            if (isVararg) yield(VARARG)
            if (isNoInline) yield(NOINLINE)
            if (isCrossInline) yield(CROSSINLINE)
        }

    private fun KSValueParameter.asSpec() = ParameterSpec.builder(
        name?.asString() ?: error("Value parameter unexpectedly has no name"),
        type.toTypeName(),
        modifiers.asIterable().asKModifiers()
    ).apply {
        if (hasDefault) defaultValue(
            "%M(%S)",
            MemberName("kotlin", "TODO"),
            "The default value of ${name?.asString()} is unknown due to KSP limitations, please migrate!"
        )
    }.build()

    private fun CodeBlock.Builder.composeDelegation(
        func: KSFunctionDeclaration,
        statement: Boolean,
    ): CodeBlock.Builder {
        val isExtension = func.extensionReceiver != null
        val formats = buildList {
            add(
                if (statement) MemberName(func.packageName.asString(), func.simpleName.asString(), isExtension)
                else func.simpleName.asString()
            )
            addAll(func.typeParameters.map { it.name.asString() })
            addAll(func.parameters.map { it.name?.asString() })
        }.toTypedArray()

        return addStatement(buildString {
            if (statement) append("return ")
            if (isExtension) append("this.")
            if (statement) append("%M") else append("%N")

            val typeVarsIter = func.typeParameters.iterator()
            if (typeVarsIter.hasNext()) {
                append("<%N")
                typeVarsIter.next()
                for (unused in typeVarsIter) append(", %N")
                append('>')
            }

            append('(')
            val valueVarsIter = func.parameters.iterator()
            if (valueVarsIter.hasNext()) {
                append("%N")
                valueVarsIter.next()
                for (unused in valueVarsIter) append(", %N")
            }
            append(')')
        }, *formats)
    }

    private fun TypeAliasSpec.Builder.common(declaration: KSDeclaration, minLevel: DeprecationLevel) =
        addTypeVariables(declaration.typeParameters.asTypeVariables()).deprecate(
            replaceWith = declaration.simpleName.asString(),
            newMemberName = declaration.qualifiedName?.asString(),
            minLevel
        )

    private fun KSAnnotated.process(
        builder: FileSpec.Builder,
        minLevel: DeprecationLevel,
    ): FileSpec.Builder = builder.apply {
        if (containingFile == null || (this@process is KSModifierListOwner && !isVisible)) return@apply

        when (this@process) {
            is KSTypeAlias -> addTypeAlias(
                TypeAliasSpec
                    .builder(simpleName.asString(), type.toTypeName())
                    .common(this@process, minLevel)
                    .build()
            )

            is KSClassDeclaration -> if (classKind != ClassKind.ANNOTATION_CLASS) addTypeAlias(
                TypeAliasSpec
                    .builder(simpleName.asString(), toClassName())
                    .common(this@process, minLevel)
                    .build()
            )

            is KSFunctionDeclaration -> addFunction(
                FunSpec.builder(simpleName.asString())
                    .apply { extensionReceiver?.let { receiver(it.toTypeName()) } }
                    .apply { returnType?.let { returns(it.toTypeName()) } }
                    .apply { this@process.parameters.forEach { addParameter(it.asSpec()) } }
                    .addTypeVariables(typeParameters.asTypeVariables())
                    .addModifiers(modifiers.asKModifiers())
                    .addCode(CodeBlock.builder().composeDelegation(this@process, statement = true).build())
                    .deprecate(
                        replaceWith = CodeBlock.builder().composeDelegation(this@process, statement = false)
                            .build().toString().trimEnd(),
                        newMemberName = qualifiedName?.asString(),
                        minLevel = if (this@process.parameters.any { it.hasDefault }) maxOf(
                            minLevel,
                            DeprecationLevel.ERROR
                        ) else minLevel
                    )
                    .build()
            )

            is KSPropertyDeclaration -> {
                val extensionType = extensionReceiver?.toTypeName()
                val delegateArgs = buildList {
                    if (extensionType != null) add(extensionType)
                    add(
                        MemberName(
                            this@process.packageName.asString(),
                            simpleName.asString(),
                            extensionReceiver != null
                        )
                    )
                }

                addProperty(
                    PropertySpec.builder(simpleName.asString(), type.toTypeName(), modifiers.asKModifiers())
                        .apply { if (extensionType != null) receiver(extensionType) }
                        .delegate(
                            buildString {
                                if (extensionType != null) append("%T")
                                append("::%M")
                            },
                            *delegateArgs.toTypedArray()
                        )
                        .deprecate(
                            replaceWith = (if (extensionReceiver != null) "this." else "") + simpleName.asString(),
                            newMemberName = qualifiedName?.asString(),
                            minLevel
                        )
                        .build()
                )
            }

            else -> error("Cannot relocate KSAnnotated ${javaClass.simpleName}!")
        }
    }
}

@Retention
@Target(
    AnnotationTarget.FILE,
    AnnotationTarget.TYPEALIAS,
    AnnotationTarget.CLASS,
    AnnotationTarget.ANNOTATION_CLASS,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY,
)
annotation class Relocated(val level: DeprecationLevel = DeprecationLevel.WARNING)