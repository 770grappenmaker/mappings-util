package com.grappenmaker.conventions

import org.gradle.api.Project
import kotlin.text.trim

// TODO: handle nulls and errors, don't care for now.
@Suppress("UnstableApiUsage")
fun Project.sopsDecrypt(resource: String): String? = runCatching {
    providers.exec {
        commandLine("sops", "decrypt", resource)
        workingDir(rootProject.projectDir)
    }.standardOutput.asText.get().trim()
}.getOrNull()