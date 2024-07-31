# Module tiny-remapper-provider

Utility for using this mappings library with [tiny-remapper](https://github.com/FabricMC/tiny-remapper),
instead of the remapper provided in this library, since it is considered more versatile and extensible.

tiny-remapper is licensed under GNU LGPL v3.0, a copy of which can be found [here](https://github.com/FabricMC/tiny-remapper/blob/master/LICENSE).

The only member this module provides is [MappingsProvider][com.grappenmaker.mappings.MappingsProvider],
which can be passed to `TinyRemapper.Builder.withMappings`.