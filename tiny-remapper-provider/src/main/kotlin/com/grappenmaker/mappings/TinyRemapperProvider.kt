package com.grappenmaker.mappings

import net.fabricmc.tinyremapper.IMappingProvider
import net.fabricmc.tinyremapper.IMappingProvider.MappingAcceptor
import net.fabricmc.tinyremapper.IMappingProvider.Member

/**
 * An [IMappingProvider] that can send [Mappings] to an [IMappingProvider.MappingAcceptor]. If [passParameters] is
 * `true`, [MappedParameter]s and [MappedLocal]s will be passed to the [IMappingProvider.MappingAcceptor] as well.
 *
 * @param sourceNamespace the namespace of the original class files passed to tiny remapper
 * @param targetNamespace the desired namespace the class files passed to tiny remapper should be remapped to
 */
public class MappingsProvider(
    private val mappings: Mappings,
    sourceNamespace: String,
    targetNamespace: String,
    private val passParameters: Boolean = true,
) : IMappingProvider {
    private val sourceIdx = mappings.namespace(sourceNamespace)
    private val targetIdx = mappings.namespace(targetNamespace)

    @Suppress("NOTHING_TO_INLINE")
    private inline fun MappedClass.load(acceptor: MappingAcceptor) {
        val owner = names[sourceIdx]
        acceptor.acceptClass(owner, names[targetIdx])

        for (method in methods) method.load(owner, acceptor)
        for (field in methods) acceptor.acceptField(
            Member(owner, field.names[sourceIdx], field.desc), field.names[targetIdx]
        )
    }

    @Suppress("NOTHING_TO_INLINE")
    private inline fun MappedMethod.load(owner: String, acceptor: MappingAcceptor) {
        val member = Member(owner, names[sourceIdx], desc)
        acceptor.acceptMethod(member, names[targetIdx])

        if (passParameters) {
            for (arg in parameters) acceptor.acceptMethodArg(member, arg.index, arg.names[targetIdx])
            for (lv in variables) acceptor.acceptMethodVar(
                member, lv.lvtIndex, lv.startOffset, lv.index, lv.names[targetIdx]
            )
        }
    }

    override fun load(acceptor: MappingAcceptor) {
        for (clz in mappings.classes) clz.load(acceptor)
    }
}