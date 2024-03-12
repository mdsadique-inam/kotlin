/*
 * Copyright 2010-2024 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.gradle.plugin.mpp.apple

import org.gradle.api.DefaultTask
import org.gradle.api.file.*
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*
import org.gradle.process.ExecOperations
import org.gradle.work.DisableCachingByDefault
import org.jetbrains.kotlin.gradle.utils.getFile
import org.jetbrains.kotlin.gradle.utils.processPlist
import org.jetbrains.kotlin.incremental.createDirectory
import org.jetbrains.kotlin.incremental.deleteRecursivelyOrThrow
import org.jetbrains.kotlin.konan.target.HostManager
import java.io.File
import javax.inject.Inject

@DisableCachingByDefault
internal abstract class FrameworkTask @Inject constructor(
    private val execOperations: ExecOperations
) : DefaultTask() {
    init {
        onlyIf { HostManager.hostIsMac }
    }

    @get:SkipWhenEmpty
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val binary: RegularFileProperty

    @get:Optional
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val umbrella: RegularFileProperty

    @get:Optional
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val headers: ConfigurableFileCollection

    @get:Input
    abstract val frameworkName: Property<String>

    @get:Input
    abstract val bundleIdentifier: Property<String>

    @get:Input
    abstract val sdkName: Property<String>

    @get:OutputDirectory
    abstract val frameworkPath: DirectoryProperty

    private val frameworkRootPath: File
        get() = frameworkPath.getFile().resolve("${frameworkName.get()}.framework")

    private val modulePath: File
        get() = frameworkRootPath.resolve("Modules")

    private val headerPath: File
        get() = frameworkRootPath.resolve("Headers")

    @TaskAction
    fun assembleFramework() {
        if (frameworkRootPath.exists()) {
            frameworkRootPath.deleteRecursivelyOrThrow()
        }
        modulePath.createDirectory()
        headerPath.createDirectory()

        copyBinary()
        copyHeaders()
        createModuleMap()
        createInfoPlist()
    }

    private fun copyBinary() {
        binary.getFile().copyTo(
            frameworkRootPath.resolve(frameworkName.get())
        )
    }

    private fun createModuleMap() {
        val umbrellaHeader = umbrella.map { "umbrella header \"${it.asFile.name}\"" }.get()

        modulePath.resolve("module.modulemap").writeText(
            """
            |framework module ${frameworkName.get()} {
            |   $umbrellaHeader
            |
            |   export *
            |   module * { export * }
            | 
            |${
                headers.asFileTree.joinToString("\n") {
                    """
                    |   header "${it.name}"
                    """.trimMargin()
                }
            }
            |
            |   use Foundation
            |   requires objc    
            |}
            """.trimMargin()
        )
    }

    private fun createInfoPlist() {
        val info = mapOf(
            "CFBundleIdentifier" to bundleIdentifier.get(),
            "CFBundleInfoDictionaryVersion" to "6.0",
            "CFBundlePackageType" to "FMWK",
            "CFBundleVersion" to "1",
            "DTSDKName" to sdkName.get(),
            "CFBundleExecutable" to frameworkName.get(),
            "CFBundleName" to frameworkName.get()
        )

        val outputFile = frameworkRootPath.resolve("Info.plist")

        processPlist(outputFile, execOperations) {
            info.forEach {
                add(":${it.key}", it.value)
            }
        }
    }

    private fun copyHeaders() {
        headers.forEach {
            it.copyTo(headerPath.resolve(it.name), true)
        }

        val umbrella = this.umbrella.getFile()
        umbrella.copyTo(
            headerPath.resolve(umbrella.name),
            true
        )
    }
}