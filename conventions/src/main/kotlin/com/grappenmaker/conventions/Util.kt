package com.grappenmaker.conventions

import org.gradle.api.Project
import kotlin.text.trim

@Suppress("UnstableApiUsage")
fun Project.sopsDecrypt(resource: String): String = providers.exec {
    commandLine("sops", "decrypt", resource)
    workingDir(rootProject.projectDir)
}.standardOutput.asText.get().trim()