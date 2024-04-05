/*
 * Copyright 2010-2024 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.gradle.plugin.mpp.apple

import org.gradle.api.DefaultTask
import org.gradle.api.file.*
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.*
import org.gradle.work.DisableCachingByDefault
import org.jetbrains.kotlin.gradle.utils.getFile
import org.jetbrains.kotlin.gradle.utils.relativeOrAbsolute
import org.jetbrains.kotlin.gradle.utils.runCommand
import org.jetbrains.kotlin.incremental.deleteRecursivelyOrThrow
import org.jetbrains.kotlin.konan.target.HostManager
import java.io.File
import java.io.Serializable
import javax.inject.Inject

internal data class ModuleDefinition(val name: String, val header: File) : Serializable

@DisableCachingByDefault
internal abstract class FrameworkTask @Inject constructor(
    private val fileSystem: FileSystemOperations,
) : DefaultTask() {
    init {
        onlyIf { HostManager.hostIsMac }
    }

    @get:SkipWhenEmpty
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val libraries: ConfigurableFileCollection

    @get:Input
    abstract val binaryName: Property<String>

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val swiftModule: DirectoryProperty

    @get:Input
    @get:Optional
    abstract val headerDefinitions: ListProperty<ModuleDefinition>

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val workingDir: DirectoryProperty

    @get:Internal
    val libraryName: Provider<String>
        get() = binaryName.map { "lib${it}.a" }

    @get:Internal
    val frameworkName: Provider<String>
        get() = binaryName.map { "${it}.xcframework" }

    @get:OutputDirectory
    val frameworkPath: Provider<Directory>
        get() = workingDir.map { it.dir(frameworkName.get()) }

    @get:OutputFile
    val libraryPath: Provider<RegularFile>
        get() = workingDir.map { it.file(libraryName.get()) }

    @get:OutputDirectory
    val headersPath: Provider<Directory>
        get() = workingDir.map { it.dir("Headers") }

    @TaskAction
    fun assembleFramework() {
        frameworkPath.getFile().apply {
            if (exists()) {
                deleteRecursivelyOrThrow()
            }
        }

        libraries.asFileTree.forEach {
            val exists = it.exists()
            println("File: ${it.canonicalPath} exists: $exists")
        }

        println("Working dir: ${workingDir.getFile()}")
        println("Swift module: ${swiftModule.getFile()}")

        assembleBinary()
        prepareHeaders()
        createXCFramework()
        copyModule()
        cleanup()
    }

    private fun assembleBinary() {
        if (libraries.asFileTree.count() <= 1) {
            return
        }

        runCommand(
            listOf(
                "libtool",
                "-static",
                "-o", libraryPath.getFile().name
            ) + libraries.asFileTree.map { it.relativeOrAbsolute(workingDir.getFile()) },
            processConfiguration = {
                directory(workingDir.getFile())
            }
        )
    }

    private fun createXCFramework() {
        runCommand(
            listOf(
                "xcodebuild",
                "-create-xcframework",
                "-library", libraryPath.getFile().name,
                "-headers", headersPath.getFile().relativeOrAbsolute(workingDir.getFile()),
                "-allow-internal-distribution",
                "-output", binaryName.map { "$it.xcframework" }.get()
            ),
            processConfiguration = {
                directory(workingDir.getFile())
            }
        )
    }

    private fun prepareHeaders() {
        val modulemap = headersPath.getFile().resolve("module.modulemap")
        headerDefinitions.getOrElse(emptyList()).forEach { moduleDef ->
            modulemap.appendText(
                """
                |module ${moduleDef.name} {
                |   header "${moduleDef.header.name}"
                |   export *
                |}
                |
                """.trimMargin()
            )

            fileSystem.copy {
                it.from(moduleDef.header)
                it.into(headersPath)
            }
        }
    }

    private fun copyModule() {
        frameworkPath.getFile().listFiles()?.let { arch ->
            arch.filter {
                it.isDirectory
            }.forEach { targetFramework ->
                fileSystem.copy {
                    it.from(swiftModule)
                    it.into(targetFramework.resolve(swiftModule.asFile.get().name))
                }
            }
        }
    }

    private fun cleanup() {
        fileSystem.delete {
            it.delete(libraryPath)
        }
        fileSystem.delete {
            it.delete(headersPath)
        }
    }
}