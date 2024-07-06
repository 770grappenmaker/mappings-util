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
public fun Mappings.asCompactedMappings(): CompactedMappings = CompactedMappings(namespaces, classes)