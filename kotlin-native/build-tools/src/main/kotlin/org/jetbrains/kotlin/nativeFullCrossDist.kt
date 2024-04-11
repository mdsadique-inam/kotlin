/*
 * Copyright 2010-2024 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin

import org.gradle.api.Project
import org.gradle.api.logging.Logger
import org.gradle.api.tasks.Copy
import org.gradle.api.tasks.TaskProvider
import org.gradle.kotlin.dsl.register
import org.jetbrains.kotlin.konan.target.HostManager
import org.jetbrains.kotlin.konan.target.KonanTarget
import java.io.File

/**
 * "Full K/N Cross-Distribution" stands for the K/N distribution that contains all platform klibs, including
 * for cross-targets. Simply speaking, for MacOS "Full K/N crossdist" is the same as the usual dist, but for
 * Linux/Win it's a dist + Darwin-specific klibs like `Foundation`
 *
 * We build Full K/N Cross-Distribution iff the [PATH_TO_DARWIN_DIST_PROPERTY] is provided and points to
 * valid K/N distribution
 *
 * In such case, `setupCrossDistCopyTask` register a task that copies the klibs from the provided Darwin-dist to
 * the current dist.
 *
 * Full K/N Cross-Dist is expected to be not built by default (as it complicated the build setup by adding a
 * dependency on Mac-generated dist)
 */
fun Project.setupCrossDistCopyTask(): TaskProvider<Copy> = tasks.register<Copy>("copyDarwinLibrariesToCrossDist") {
    val distRoot = pathToDarwinDistProperty
    onlyIf { distRoot != null && distRoot.ensureIsValidPathToDarwinDist(logger) && HostManager.hostIsMac }

    if (distRoot == null) return@register
    if (HostManager.hostIsMac) {
        logger.error("Host is Mac, but $PATH_TO_DARWIN_DIST_PROPERTY is used (value is ${distRoot})")
        return@register
    }

    val platform = File(distRoot).klib.platform
    val targetNames = getDarwinOnlyTargets().map { it.name }

    from(platform) {
        into(project.kotlinNativeDist.klib.platform)
        include(targetNames)
    }
}

// Assume that all Macs have the same enabled targets and use them as "darwin-only" targets
fun getDarwinOnlyTargets(): Set<KonanTarget> {
    val enabledByHost = HostManager().enabledByHost
    val enabledOnMacosX64 = enabledByHost[KonanTarget.MACOS_X64]?.toSet() ?: emptySet()
    val enabledOnMacosArm64 = enabledByHost[KonanTarget.MACOS_ARM64]?.toSet() ?: emptySet()

    // Assert the assumption
    require(enabledOnMacosArm64 == enabledOnMacosX64) {
        val symmetricalDifference = (enabledOnMacosArm64 union enabledOnMacosX64) - (enabledOnMacosArm64 intersect enabledOnMacosX64)
        "Attention! Enabled targets for macosArm64 and macosX64 are different!\n" +
                "Diff = $symmetricalDifference\n" +
                "Please, revise the code for building Full K/N Cross-Dist"
    }
    return enabledOnMacosX64
}

private val Project.pathToDarwinDistProperty: String?
    get() = findProperty(PATH_TO_DARWIN_DIST_PROPERTY) as? String

private val File.klib: File
    get() = File(this, "klib")
private val File.platform: File
    get() = File(this, "platform")

private fun String.ensureIsValidPathToDarwinDist(log: Logger): Boolean {
    val distRoot = File(this)
    if (!distRoot.exists()) {
        log.error(
                "Value of $PATH_TO_DARWIN_DIST_PROPERTY doesn't exist.\n" +
                        "$PATH_TO_DARWIN_DIST_PROPERTY = $this\n" +
                        "File checked at ${distRoot.canonicalPath}"
        )
        return false
    }

    val klibSubfolder = distRoot.klib
    if (!klibSubfolder.exists()) {
        log.error(
                "Couldn't find 'klib' subfolder under the '$PATH_TO_DARWIN_DIST_PROPERTY'. Is it a valid K/N distribution?\n" +
                        "$PATH_TO_DARWIN_DIST_PROPERTY = $this\n" +
                        "klib folder checked at ${klibSubfolder.canonicalPath}"
        )
        return false
    }

    return true
}

const val PATH_TO_DARWIN_DIST_PROPERTY = "kotlin.native.pathToDarwinDist"
