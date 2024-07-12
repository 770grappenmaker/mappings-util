package com.grappenmaker.mappings

/**
 * Converts these [Mappings] to [SRGMappings], enabling XSRG when [extended] is true.
 *
 * @sample samples.Mappings.conversions
 */
public fun Mappings.asSRGMappings(extended: Boolean): SRGMappings {
    require(namespaces.size == 2) { "mappings to convert to SRG should contain 2 namespaces!" }
    return SRGMappings(classes, extended)
}

/**
 * Converts these [Mappings] to [ProguardMappings].
 *
 * @sample samples.Mappings.conversions
 */
public fun Mappings.asProguardMappings(): ProguardMappings {
    require(namespaces.size == 2) { "mappings to convert to Proguard mappings should contain 2 namespaces!" }
    return ProguardMappings(classes)
}

/**
 * Converts these [Mappings] to [TinyMappings], enabling Tiny v2 when [v2] is true.
 *
 * @sample samples.Mappings.conversions
 */
public fun Mappings.asTinyMappings(v2: Boolean): TinyMappings = TinyMappings(namespaces, classes, v2)

/**
 * Converts these [Mappings] to [TSRGMappings], enabling TSRG v2 when [v2] is true.
 *
 * @sample samples.Mappings.conversions
 */
public fun Mappings.asTSRGMappings(v2: Boolean): TSRGMappings = TSRGMappings(namespaces, classes, v2)

/**
 * Converts these [Mappings] to [CompactedMappings].
 *
 * @sample samples.Mappings.conversions
 */
public fun Mappings.asCompactedMappings(version: Int = 2): CompactedMappings =
    CompactedMappings(namespaces, classes, version)

/**
 * Converts these [Mappings] to [GenericMappings]
 */
public fun Mappings.asGenericMappings(): GenericMappings = GenericMappings(namespaces, classes)

/**
 * Converts these [Mappings] to [CSRGMappings]
 */
public fun Mappings.asCSRGMappings(): CSRGMappings = CSRGMappings(classes)

/**
 * Converts these [Mappings] to [EnigmaMappings]
 */
public fun Mappings.asEnigmaMappings(): EnigmaMappings = EnigmaMappings(classes)

/**
 * Converts these [Mappings] to [RecafMappings]
 */
public fun Mappings.asRecafMappings(): RecafMappings = RecafMappings(classes)