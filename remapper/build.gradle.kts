plugins {
    id("kotlin-convention")
    application
}

dependencies {
    implementation(projects.mappingsUtil)
}

application {
    mainClass = "com.grappenmaker.mappings.remapper.RemapperKt"
}

open class InstallDistTo : Sync() {
    @Option(option = "into", description = "Directory to copy the distribution into")
    override fun into(destDir: Any): AbstractCopyTask = super.into(destDir)
}

tasks {
    val installDist by getting(Sync::class)
    val installDistTo by registering(InstallDistTo::class) {
        dependsOn(installDist)
        from(installDist.outputs.files.singleFile)
    }
}