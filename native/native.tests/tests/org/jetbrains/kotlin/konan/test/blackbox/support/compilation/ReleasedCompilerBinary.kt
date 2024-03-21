/*
 * Copyright 2010-2024 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.konan.test.blackbox.support.compilation

import org.jetbrains.kotlin.klib.PartialLinkageTestUtils
import org.jetbrains.kotlin.konan.target.TargetSupportException
import org.jetbrains.kotlin.konan.util.ArchiveType
import org.jetbrains.kotlin.konan.util.DependencyDownloader
import org.jetbrains.kotlin.konan.util.DependencyExtractor
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.net.URL

private const val DISTRIBS_LOCATION = "https://cache-redirector.jetbrains.com/download.jetbrains.com/kotlin/native/builds/releases"
private val konanDirectory = File(System.getProperty("user.home"), ".konan")
private val compilerExecutableSubDir = "bin" + File.separator + "kotlinc-native"
private val stdlibSubDir = "klib" + File.separator + "common" + File.separator + "stdlib"
private val lock: Any = Any()

internal fun getReleasedCompiler(version: String): ReleasedCompiler {
    val nativePrebuildsDir = kotlinNativeDistributionDir(version)
    return ReleasedCompiler(nativePrebuildsDir)
}

internal fun downloadReleasedCompiler(version: String) {
    downloadAndUnpackCompilerBinaries(version)
}

internal class ReleasedCompiler(private val nativePrebuildsDir: File) {
    fun buildKlib(sourceFiles: List<File>, dependencies: PartialLinkageTestUtils.Dependencies, outputFile: File) {
        execute(sourceFiles.map { it.absolutePath }
                        + dependencies.toCompilerArgs()
                        + listOf("-produce", "library", "-o", outputFile.absolutePath))
    }

    private fun execute(args: List<String>) {
        val builder = ProcessBuilder()
        builder.command(buildCommand(args))
        val process = builder.start()
        val exitCode = process.waitFor()
        if (exitCode != 0) {
            println("Exit code $exitCode")
            process.logOutput()
        }
    }

    private fun buildCommand(args: List<String>) =
        listOf(File(nativePrebuildsDir, compilerExecutableSubDir).absolutePath) + args

    private fun PartialLinkageTestUtils.Dependencies.toCompilerArgs() =
        regularDependencies.replaceStdlib().flatMap { listOf("-library", it.libraryFile.absolutePath) } +
                friendDependencies.flatMap { listOf("-friend-modules", it.libraryFile.absolutePath) }

    private fun Set<PartialLinkageTestUtils.Dependency>.replaceStdlib() = map {
        val stdlibPath = File(nativePrebuildsDir, stdlibSubDir)
        if (it.moduleName == "stdlib")
            PartialLinkageTestUtils.Dependency("stdlib", stdlibPath)
        else it
    }.toSet()

    private fun Process.logOutput() {
        printStream(inputStream)
        printStream(errorStream)
    }

    private fun printStream(stream: InputStream) {
        InputStreamReader(stream).useLines { lines ->
            lines.forEach { println(it) }
        }
    }
}

private fun downloadAndUnpackCompilerBinaries(version: String): File = synchronized(lock) {
    val targetDirectory = kotlinNativeDistributionDir(version)
    if (isCompilerDownloaded(targetDirectory)) return targetDirectory

    val artifactFileName = kotlinNativeDistributionName(version)

    val compilerArchive = downloadCompiler(artifactFileName, version)
    extractCompiler(compilerArchive, artifactFileName, targetDirectory)
    return targetDirectory
}

private fun kotlinNativeDistributionName(version: String) = "kotlin-native-prebuilt-$host-$version"
private fun kotlinNativeDistributionDir(version: String) = File(konanDirectory, kotlinNativeDistributionName(version))

private fun isCompilerDownloaded(targetDirectory: File) = File(targetDirectory, compilerExecutableSubDir).exists()

private fun downloadCompiler(artifactFileName: String, version: String): File {
    val artifactFileNameWithExtension = artifactFileName + hostSpecificExtension

    val tempLocation = File.createTempFile(artifactFileName, hostSpecificExtension)
    val url = URL("$DISTRIBS_LOCATION/$version/$host/$artifactFileNameWithExtension")

    return DependencyDownloader(customProgressCallback = { _, _, _ -> }).download(
        url,
        tempLocation,
        DependencyDownloader.ReplacingMode.REPLACE
    )
}

private fun extractCompiler(archive: File, unpackedFolderName: String, targetDirectory: File) {
    DependencyExtractor().extract(archive, konanDirectory, ArchiveType.systemDefault)
    val unpackedDir = File(konanDirectory, unpackedFolderName)
    unpackedDir.renameTo(targetDirectory)
}

private val hostSpecificExtension: String
    get() {
        val javaOsName = System.getProperty("os.name")
        return when {
            javaOsName.startsWith("Windows") -> ".zip"
            else -> ".tar.gz"
        }
    }

private val host = "${hostOs()}-${hostArch()}"

private fun hostOs(): String {
    val javaOsName = System.getProperty("os.name")
    return when {
        javaOsName == "Mac OS X" -> "macos"
        javaOsName == "Linux" -> "linux"
        javaOsName.startsWith("Windows") -> "windows"
        else -> throw TargetSupportException("Unknown operating system: $javaOsName")
    }
}

private fun hostArch(): String = when (val arch = System.getProperty("os.arch")) {
    "x86_64" -> "x86_64"
    "amd64" -> "x86_64"
    "arm64" -> "aarch64"
    "aarch64" -> "aarch64"
    else -> throw TargetSupportException("Unknown hardware platform: $arch")
}

