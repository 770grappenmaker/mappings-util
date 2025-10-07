# sponge-mixin-remapper

Utility for using this mappings library to remap classes annotated with [SpongePowered Mixin](https://github.com/SpongePowered/Mixin).

Mixin is licensed under The MIT License, a copy of which is present in this directory. Usage of
Mixin is governed by its license. This library is not affiliated with SpongePowered.

## Usage
This component has a dependency on [Fabric's fork](https://github.com/FabricMC/Mixin) of Mixin, for the sake of being
compatible with Maven Central. However, it is fully compatible with the original version of Mixin. If you are using
that version, you have to remove the transitive dependency on the fork:
```kt
repositories {
    mavenCentral()
}

dependencies {
    implementation("io.github.770grappenmaker:sponge-mixin-remapper:0.2") {
        exclude(group = "net.fabricmc", module = "sponge-mixin")
    }
}
```