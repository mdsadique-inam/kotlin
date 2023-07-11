/* * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.ir.backend.js.ic

import com.sun.management.HotSpotDiagnosticMXBean
import org.jetbrains.kotlin.backend.common.CommonJsKLibResolver
import org.jetbrains.kotlin.backend.common.phaser.PhaseConfig
import org.jetbrains.kotlin.backend.common.serialization.IrInterningService
import org.jetbrains.kotlin.backend.common.serialization.cityHash64
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.ir.backend.js.*
import org.jetbrains.kotlin.ir.backend.js.HeapDumper
import org.jetbrains.kotlin.ir.backend.js.codegen.JsGenerationGranularity
import org.jetbrains.kotlin.ir.backend.js.transformers.irToJs.JsIrProgramFragment
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.util.*
import org.jetbrains.kotlin.js.config.JSConfigurationKeys
import org.jetbrains.kotlin.konan.properties.propertyList
import org.jetbrains.kotlin.library.KLIB_PROPERTY_DEPENDS
import org.jetbrains.kotlin.library.KotlinLibrary
import org.jetbrains.kotlin.library.metadata.resolver.TopologicalLibraryOrder
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.utils.memoryOptimizedFilter
import org.jetbrains.kotlin.utils.memoryOptimizedMap
import org.jetbrains.kotlin.utils.newHashMapWithExpectedSize
import org.jetbrains.kotlin.utils.newHashSetWithExpectedSize
import java.io.File
import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.EnumSet

object HeapDumper {
    private val hotSpotDiagnostic: HotSpotDiagnosticMXBean by lazy {
        val connection = ManagementFactory.getPlatformMBeanServer()
        val name = "com.sun.management:type=HotSpotDiagnostic"
        ManagementFactory.newPlatformMXBeanProxy(connection, name, HotSpotDiagnosticMXBean::class.java)
    }

    fun createHeapDump(file: String, live: Boolean) {
        hotSpotDiagnostic.dumpHeap(file, live)
    }
}

fun interface JsIrCompilerICInterface {
    fun compile(
        allModules: Collection<IrModuleFragment>,
        dirtyFiles: Collection<IrFile>,
        mainArguments: List<String>?
    ): List<() -> JsIrProgramFragment>
}

fun interface JsIrCompilerICInterfaceFactory {
    fun createCompilerForIC(
        mainModule: IrModuleFragment,
        configuration: CompilerConfiguration
    ): JsIrCompilerICInterface
}

enum class DirtyFileState(val str: String) {
    ADDED_FILE("added file"),
    MODIFIED_IR("modified ir"),
    NON_MODIFIED_IR("non modified ir"),
    UPDATED_EXPORTS("updated exports"),
    UPDATED_IMPORTS("updated imports"),
    REMOVED_INVERSE_DEPENDS("removed inverse depends"),
    REMOVED_DIRECT_DEPENDS("removed direct depends"),
    REMOVED_FILE("removed file")
}

class CacheUpdater(
    mainModule: String,
    private val allModules: Collection<String>,
    private val mainModuleFriends: Collection<String>,
    cacheDir: String,
    private val compilerConfiguration: CompilerConfiguration,
    private val irFactory: () -> IrFactory,
    private val mainArguments: List<String>?,
    private val compilerInterfaceFactory: JsIrCompilerICInterfaceFactory
) {
    private val stopwatch = StopwatchIC()

    private val dirtyFileStats = KotlinSourceFileMutableMap<EnumSet<DirtyFileState>>()

    private val mainLibraryFile = KotlinLibraryFile(File(mainModule).canonicalPath)

    private val icHasher = ICHasher()

    private val internationService = IrInterningService()

    private val cacheRootDir = run {
        val configHash = icHasher.calculateConfigHash(compilerConfiguration)
        File(cacheDir, "version.${configHash.hash.lowBytes.toString(Character.MAX_RADIX)}")
    }

    fun getDirtyFileLastStats(): KotlinSourceFileMap<EnumSet<DirtyFileState>> = dirtyFileStats

    fun getStopwatchLastLaps() = stopwatch.laps

    private fun MutableMap<KotlinSourceFile, EnumSet<DirtyFileState>>.addDirtFileStat(srcFile: KotlinSourceFile, state: DirtyFileState) {
        when (val stats = this[srcFile]) {
            null -> this[srcFile] = EnumSet.of(state)
            else -> stats.add(state)
        }
    }

    private inner class CacheUpdaterInternal {
        val signatureHashCalculator = IdSignatureHashCalculator(icHasher)

        // libraries in topological order: [stdlib, ..., main]
        val libraryDependencies = stopwatch.measure("Resolving and loading klib dependencies") {
            val zipAccessor = compilerConfiguration.get(JSConfigurationKeys.ZIP_FILE_SYSTEM_ACCESSOR)
            val allResolvedDependencies = CommonJsKLibResolver.resolve(allModules, compilerConfiguration.resolverLogger, zipAccessor)

            val libraries = allResolvedDependencies.getFullList(TopologicalLibraryOrder).let { resolvedLibraries ->
                val mainLibraryIndex = resolvedLibraries.indexOfLast {
                    KotlinLibraryFile(it) == mainLibraryFile
                }.takeIf { it >= 0 } ?: notFoundIcError("main library", mainLibraryFile)

                when (mainLibraryIndex) {
                    resolvedLibraries.lastIndex -> resolvedLibraries
                    else -> resolvedLibraries.filterIndexedTo(ArrayList(resolvedLibraries.size)) { index, _ ->
                        index != mainLibraryIndex
                    }.apply { add(resolvedLibraries[mainLibraryIndex]) }
                }
            }

            val nameToKotlinLibrary = libraries.associateBy { it.moduleName }

            libraries.associateWith {
                it.manifestProperties.propertyList(KLIB_PROPERTY_DEPENDS, escapeInQuotes = true).memoryOptimizedMap { depName ->
                    nameToKotlinLibrary[depName] ?: notFoundIcError("library $depName")
                }
            }
        }

        val mainModuleFriendLibraries = libraryDependencies.keys.let { libs ->
            val friendPaths = mainModuleFriends.mapTo(newHashSetWithExpectedSize(mainModuleFriends.size)) { File(it).canonicalPath }
            libs.memoryOptimizedFilter { it.libraryFile.canonicalPath in friendPaths }
        }

        private val klibCacheDirs = libraryDependencies.keys.map { lib ->
            val libFile = KotlinLibraryFile(lib)
            val file = File(libFile.path)
            val pathHash = file.absolutePath.cityHash64().toULong().toString(Character.MAX_RADIX)
            File(cacheRootDir, "${file.name}.$pathHash")
        }

        init {
            // Enabled Partial Linkage may replace entire IR expressions with a stub during klib linking and loading.
            // This may break the incremental cache dependency graph.
            // Dropping incremental cache files after updating klibs (e.g. update klib version)
            // covers almost all cases which could not be tracked due to a broken dependency graph.
            // TODO: This code could be removed after fixing KT-57347
            val oldKlibCaches = cacheRootDir.listFiles { file: File -> file.isDirectory }?.mapTo(hashSetOf()) { it.name } ?: emptySet()
            val newKlibCaches = klibCacheDirs.mapTo(hashSetOf()) { it.name }
            if (oldKlibCaches != newKlibCaches) {
                cacheRootDir.deleteRecursively()
            }
        }

        private val incrementalCaches = libraryDependencies.keys.zip(klibCacheDirs).associate { (lib, libraryCacheDir) ->
            KotlinLibraryFile(lib) to IncrementalCache(KotlinLoadedLibraryHeader(lib, internationService), libraryCacheDir)
        }

        private val removedIncrementalCaches = buildList {
            if (cacheRootDir.isDirectory) {
                val availableCaches = incrementalCaches.values.mapTo(newHashSetWithExpectedSize(incrementalCaches.size)) { it.cacheDir }
                val allDirs = Files.walk(cacheRootDir.toPath(), 1).map { it.toFile() }
                allDirs.filter { it != cacheRootDir && it !in availableCaches }.forEach { removedCacheDir ->
                    add(IncrementalCache(KotlinRemovedLibraryHeader(removedCacheDir), removedCacheDir))
                }
            }
        }

        private fun getLibIncrementalCache(libFile: KotlinLibraryFile) =
            incrementalCaches[libFile] ?: notFoundIcError("incremental cache", libFile)

        private fun addFilesWithRemovedDependencies(
            modifiedFiles: KotlinSourceFileMutableMap<KotlinSourceFileMetadata>,
            removedFiles: KotlinSourceFileMap<KotlinSourceFileMetadata>
        ): KotlinSourceFileMap<KotlinSourceFileMetadata> {
            val extraModifiedLibFiles = KotlinSourceFileMutableMap<KotlinSourceFileMetadata>()

            fun addDependenciesToExtraModifiedFiles(dependencies: KotlinSourceFileMap<*>, dirtyState: DirtyFileState) {
                for ((dependentLib, dependentFiles) in dependencies) {
                    val dependentCache = incrementalCaches[dependentLib] ?: continue
                    val alreadyModifiedFiles = modifiedFiles[dependentLib] ?: emptyMap()
                    val alreadyRemovedFiles = removedFiles[dependentLib] ?: emptyMap()
                    val extraModifiedFiles by lazy(LazyThreadSafetyMode.NONE) { extraModifiedLibFiles.getOrPutFiles(dependentLib) }
                    val fileStats by lazy(LazyThreadSafetyMode.NONE) { dirtyFileStats.getOrPutFiles(dependentLib) }
                    for (dependentFile in dependentFiles.keys) {
                        when (dependentFile) {
                            in alreadyModifiedFiles -> continue
                            in alreadyRemovedFiles -> continue
                            in extraModifiedFiles -> continue
                            else -> {
                                val dependentMetadata = dependentCache.fetchSourceFileFullMetadata(dependentFile)
                                extraModifiedFiles[dependentFile] = dependentMetadata
                                fileStats.addDirtFileStat(dependentFile, dirtyState)
                            }
                        }
                    }
                }
            }

            removedFiles.forEachFile { _, _, removedFileMetadata ->
                addDependenciesToExtraModifiedFiles(removedFileMetadata.directDependencies, DirtyFileState.REMOVED_INVERSE_DEPENDS)
                addDependenciesToExtraModifiedFiles(removedFileMetadata.inverseDependencies, DirtyFileState.REMOVED_DIRECT_DEPENDS)
            }

            modifiedFiles.copyFilesFrom(extraModifiedLibFiles)
            return modifiedFiles
        }

        fun loadModifiedFiles(): KotlinSourceFileMap<KotlinSourceFileMetadata> {
            val removedFilesMetadata = hashMapOf<KotlinLibraryFile, Map<KotlinSourceFile, KotlinSourceFileMetadata>>()

            fun collectDirtyFiles(lib: KotlinLibraryFile, cache: IncrementalCache): MutableMap<KotlinSourceFile, KotlinSourceFileMetadata> {
                val (addedFiles, removedFiles, modifiedFiles, nonModifiedFiles) = cache.collectModifiedFiles()

                val fileStats by lazy(LazyThreadSafetyMode.NONE) { dirtyFileStats.getOrPutFiles(lib) }
                addedFiles.forEach { fileStats.addDirtFileStat(it, DirtyFileState.ADDED_FILE) }
                removedFiles.forEach { fileStats.addDirtFileStat(it.key, DirtyFileState.REMOVED_FILE) }
                modifiedFiles.forEach { fileStats.addDirtFileStat(it.key, DirtyFileState.MODIFIED_IR) }
                nonModifiedFiles.forEach { fileStats.addDirtFileStat(it, DirtyFileState.NON_MODIFIED_IR) }

                if (removedFiles.isNotEmpty()) {
                    removedFilesMetadata[lib] = removedFiles
                }

                return addedFiles.associateWithTo(modifiedFiles.toMutableMap()) { KotlinSourceFileMetadataNotExist }
            }

            for (cache in removedIncrementalCaches) {
                val libFile = cache.libraryFileFromHeader ?: notFoundIcError("removed library name; cache dir: ${cache.cacheDir}")
                val dirtyFiles = collectDirtyFiles(libFile, cache)
                if (dirtyFiles.isNotEmpty()) {
                    icError("unexpected dirty file", libFile, dirtyFiles.keys.first())
                }
            }

            val dirtyFiles = incrementalCaches.entries.associateTo(hashMapOf()) { (lib, cache) ->
                lib to collectDirtyFiles(lib, cache)
            }

            return addFilesWithRemovedDependencies(KotlinSourceFileMutableMap(dirtyFiles), KotlinSourceFileMap(removedFilesMetadata))
        }

        fun collectExportedSymbolsForDirtyFiles(
            dirtyFiles: KotlinSourceFileMap<KotlinSourceFileMetadata>
        ): KotlinSourceFileMutableMap<KotlinSourceFileExports> {
            val exportedSymbols = KotlinSourceFileMutableMap<KotlinSourceFileExports>()

            for ((libFile, srcFiles) in dirtyFiles) {
                val exportedSymbolFiles = HashMap<KotlinSourceFile, KotlinSourceFileExports>(srcFiles.size)
                for ((srcFile, srcFileMetadata) in srcFiles) {
                    val loadingFileExports = DirtyFileExports()
                    for ((dependentLib, dependentFiles) in srcFileMetadata.inverseDependencies) {
                        val dependentCache = incrementalCaches[dependentLib] ?: continue
                        val dirtyLibFiles = dirtyFiles[dependentLib] ?: emptyMap()
                        for (dependentFile in dependentFiles.keys) {
                            if (dependentFile !in dirtyLibFiles) {
                                val dependentSrcFileMetadata = dependentCache.fetchSourceFileFullMetadata(dependentFile)
                                dependentSrcFileMetadata.directDependencies[libFile, srcFile]?.let {
                                    loadingFileExports.inverseDependencies[dependentLib, dependentFile] = it.keys
                                    loadingFileExports.allExportedSignatures += it.keys
                                }
                            }
                        }
                    }
                    exportedSymbolFiles[srcFile] = loadingFileExports
                }
                if (exportedSymbolFiles.isNotEmpty()) {
                    exportedSymbols[libFile] = exportedSymbolFiles
                }
            }
            return exportedSymbols
        }

        private fun KotlinSourceFileMutableMap<DirtyFileMetadata>.getExportedSignaturesAndAddMetadata(
            symbolProviders: List<FileSignatureProvider>,
            libFile: KotlinLibraryFile,
            dirtySrcFiles: Set<KotlinSourceFile>
        ): Map<IdSignature, IdSignatureSource> {
            val idSignatureToFile = hashMapOf<IdSignature, IdSignatureSource>()
            val incrementalCache = getLibIncrementalCache(libFile)
            for (fileSymbolProvider in symbolProviders) {
                val maybeImportedSignatures = fileSymbolProvider.getReachableSignatures().toHashSet()
                val implementedSymbols = fileSymbolProvider.getImplementedSymbols()
                for ((signature, symbol) in implementedSymbols) {
                    var symbolCanBeExported = maybeImportedSignatures.remove(signature)
                    resolveFakeOverrideFunction(symbol)?.let { resolvedSignature ->
                        if (resolvedSignature !in implementedSymbols) {
                            maybeImportedSignatures.add(resolvedSignature)
                        }
                        symbolCanBeExported = true
                    }
                    if (symbolCanBeExported) {
                        idSignatureToFile[signature] = IdSignatureSource(libFile, fileSymbolProvider.irFile, symbol)
                    }
                }

                val libSrcFile = KotlinSourceFile(fileSymbolProvider.irFile)
                if (libSrcFile in dirtySrcFiles) {
                    val metadata = incrementalCache.fetchSourceFileFullMetadata(libSrcFile)
                    this[libFile, libSrcFile] = DirtyFileMetadata(maybeImportedSignatures, metadata.directDependencies)
                }
            }
            return idSignatureToFile
        }

        private fun DirtyFileMetadata.setAllDependencies(
            idSignatureToFile: Map<IdSignature, IdSignatureSource>,
            updatedMetadata: KotlinSourceFileMap<DirtyFileMetadata>,
            libFile: KotlinLibraryFile,
            srcFile: KotlinSourceFile
        ) {
            val allImportedSignatures = addParentSignatures(maybeImportedSignatures, idSignatureToFile, libFile, srcFile)
            for (importedSignature in allImportedSignatures) {
                val dependency = idSignatureToFile[importedSignature] ?: continue
                signatureHashCalculator[importedSignature]?.also { signatureHash ->
                    addDirectDependency(dependency.lib, dependency.src, importedSignature, signatureHash)
                } ?: notFoundIcError("signature $importedSignature hash", dependency.lib, dependency.src)

                updatedMetadata[dependency.lib, dependency.src]?.also { dependencyMetadata ->
                    dependencyMetadata.addInverseDependency(libFile, srcFile, importedSignature)
                }
            }
        }

        fun rebuildDirtySourceMetadata(
            loadedIr: LoadedJsIr,
            dirtySrcFiles: KotlinSourceFileMap<KotlinSourceFileExports>,
        ): KotlinSourceFileMap<DirtyFileMetadata> {
            val idSignatureToFile = hashMapOf<IdSignature, IdSignatureSource>()
            val updatedMetadata = KotlinSourceFileMutableMap<DirtyFileMetadata>()

            for (lib in loadedIr.loadedFragments.keys) {
                val libDirtySrcFiles = dirtySrcFiles[lib]?.keys ?: emptySet()
                val symbolProviders = loadedIr.getSignatureProvidersForLib(lib)
                idSignatureToFile += updatedMetadata.getExportedSignaturesAndAddMetadata(symbolProviders, lib, libDirtySrcFiles)
            }

            signatureHashCalculator.addAllSignatureSymbols(idSignatureToFile)

            for ((libFile, srcFiles) in updatedMetadata) {
                val libDirtySrcFiles = dirtySrcFiles[libFile] ?: continue
                for ((srcFile, updatedHeader) in srcFiles) {
                    val dirtySrcFile = libDirtySrcFiles[srcFile] ?: continue
                    dirtySrcFile.inverseDependencies.forEachFile { dependentLibFile, dependentSrcFile, signatures ->
                        signatures.forEach { signature ->
                            val signatureSrc = idSignatureToFile[signature]
                            val dependencyLib = signatureSrc?.lib ?: libFile
                            val dependencyFile = signatureSrc?.src ?: srcFile
                            updatedMetadata[dependencyLib, dependencyFile]?.also { dependencyMetadata ->
                                dependencyMetadata.addInverseDependency(dependentLibFile, dependentSrcFile, signature)
                            }
                        }
                    }

                    updatedHeader.setAllDependencies(idSignatureToFile, updatedMetadata, libFile, srcFile)
                }
            }

            val result = KotlinSourceFileMutableMap<DirtyFileMetadata>()

            for ((libFile, sourceFiles) in dirtySrcFiles) {
                val incrementalCache = getLibIncrementalCache(libFile)
                val srcFileUpdatedMetadata = updatedMetadata[libFile] ?: notFoundIcError("metadata", libFile)
                for (srcFile in sourceFiles.keys) {
                    val srcMetadata = srcFileUpdatedMetadata[srcFile] ?: notFoundIcError("metadata", libFile, srcFile)
                    incrementalCache.updateSourceFileMetadata(srcFile, srcMetadata)
                    result[libFile, srcFile] = srcMetadata
                }
            }

            return result
        }

        private fun KotlinSourceFileMutableMap<UpdatedDependenciesMetadata>.addDependenciesWithUpdatedSignatures(
            libFile: KotlinLibraryFile,
            srcFile: KotlinSourceFile,
            srcFileMetadata: DirtyFileMetadata
        ) {
            // go through dependencies and collect dependencies with updated signatures
            for ((dependencyLibFile, dependencySrcFiles) in srcFileMetadata.directDependencies) {
                val dependencyCache = getLibIncrementalCache(dependencyLibFile)
                for ((dependencySrcFile, newSignatures) in dependencySrcFiles) {
                    val dependencySrcMetadata = dependencyCache.fetchSourceFileFullMetadata(dependencySrcFile)
                    val oldSignatures = dependencySrcMetadata.inverseDependencies[libFile, srcFile] ?: emptySet()
                    if (oldSignatures == newSignatures) {
                        continue
                    }
                    val newMetadata = addNewMetadata(dependencyLibFile, dependencySrcFile, dependencySrcMetadata)
                    newMetadata.inverseDependencies[libFile, srcFile] = newSignatures.keys
                }
            }
        }

        private fun KotlinSourceFileMutableMap<UpdatedDependenciesMetadata>.addDependenciesWithRemovedInverseDependencies(
            libFile: KotlinLibraryFile,
            srcFile: KotlinSourceFile,
            srcFileMetadata: DirtyFileMetadata
        ) {
            // go through old dependencies and look for removed dependencies
            for ((oldDependencyLibFile, oldDependencySrcFiles) in srcFileMetadata.oldDirectDependencies) {
                val dependencyCache = incrementalCaches[oldDependencyLibFile] ?: continue
                val newDirectDependencyFiles = srcFileMetadata.directDependencies[oldDependencyLibFile] ?: emptyMap()
                for (oldDependencySrcFile in oldDependencySrcFiles.keys) {
                    if (oldDependencySrcFile in newDirectDependencyFiles) {
                        continue
                    }
                    val dependencySrcMetadata = dependencyCache.fetchSourceFileFullMetadata(oldDependencySrcFile)
                    if (dependencySrcMetadata.inverseDependencies[libFile, srcFile] != null) {
                        val newMetadata = addNewMetadata(oldDependencyLibFile, oldDependencySrcFile, dependencySrcMetadata)
                        newMetadata.inverseDependencies.removeFile(libFile, srcFile)
                    }
                }
            }
        }

        private fun KotlinSourceFileMutableMap<UpdatedDependenciesMetadata>.addDependentsWithUpdatedImports(
            libFile: KotlinLibraryFile,
            srcFile: KotlinSourceFile,
            srcFileMetadata: DirtyFileMetadata
        ) {
            // go through dependent files and check if their imports were modified
            for ((dependentLibFile, dependentSrcFiles) in srcFileMetadata.inverseDependencies) {
                val dependentCache = incrementalCaches[dependentLibFile] ?: continue
                for ((dependentSrcFile, newSignatures) in dependentSrcFiles) {
                    val dependentSrcMetadata = dependentCache.fetchSourceFileFullMetadata(dependentSrcFile)
                    val dependentSignatures = dependentSrcMetadata.directDependencies[libFile, srcFile] ?: emptyMap()
                    when {
                        // ignore if the dependent file is already dirty
                        dependentSrcMetadata is DirtyFileMetadata -> continue

                        // ignore if the dependent file imports have been modified, the metadata for it will be rebuilt later
                        this[dependentLibFile, dependentSrcFile]?.importedSignaturesState == ImportedSignaturesState.MODIFIED -> continue

                        // update metadata if the direct dependencies have been modified
                        dependentSignatures.any { signatureHashCalculator[it.key] != it.value } -> {
                            val newMetadata = addNewMetadata(dependentLibFile, dependentSrcFile, dependentSrcMetadata)
                            newMetadata.importedSignaturesState = ImportedSignaturesState.MODIFIED
                        }

                        // update metadata if the signature set of the direct dependencies has been updated
                        dependentSignatures.keys != newSignatures -> {
                            val newMetadata = addNewMetadata(dependentLibFile, dependentSrcFile, dependentSrcMetadata)

                            if (newMetadata.importedSignaturesState == ImportedSignaturesState.UNKNOWN) {
                                val isNonModified = dependentSrcMetadata.directDependencies.allFiles { _, _, signatures ->
                                    signatures.all {
                                        val newHash = signatureHashCalculator[it.key]
                                        // a new hash may be not calculated for the non-loaded symbols, it is ok
                                        newHash == null || newHash == it.value
                                    }
                                }
                                newMetadata.importedSignaturesState = if (isNonModified) {
                                    ImportedSignaturesState.NON_MODIFIED
                                } else {
                                    ImportedSignaturesState.MODIFIED
                                }
                            }

                            // if imports have been modified, metadata for the file will be rebuilt later,
                            // so if the imports haven't been modified, update the metadata manually
                            if (newMetadata.importedSignaturesState == ImportedSignaturesState.NON_MODIFIED) {
                                val newDirectDependencies = newSignatures.associateWithTo(newHashMapWithExpectedSize(newSignatures.size)) {
                                    signatureHashCalculator[it] ?: notFoundIcError("signature $it hash", libFile, srcFile)
                                }
                                newMetadata.directDependencies[libFile, srcFile] = newDirectDependencies
                            }
                        }
                    }
                }
            }
        }

        fun collectFilesWithModifiedExportsAndImports(
            loadedDirtyFiles: KotlinSourceFileMap<DirtyFileMetadata>
        ): KotlinSourceFileMap<UpdatedDependenciesMetadata> {
            val filesWithModifiedExportsAndImports = KotlinSourceFileMutableMap<UpdatedDependenciesMetadata>()

            loadedDirtyFiles.forEachFile { libFile, srcFile, srcFileMetadata ->
                filesWithModifiedExportsAndImports.addDependenciesWithUpdatedSignatures(libFile, srcFile, srcFileMetadata)
                filesWithModifiedExportsAndImports.addDependenciesWithRemovedInverseDependencies(libFile, srcFile, srcFileMetadata)
                filesWithModifiedExportsAndImports.addDependentsWithUpdatedImports(libFile, srcFile, srcFileMetadata)
            }

            return filesWithModifiedExportsAndImports
        }

        fun collectFilesToRebuildSignatures(
            filesWithModifiedExports: KotlinSourceFileMap<UpdatedDependenciesMetadata>
        ): KotlinSourceFileMap<KotlinSourceFileExports> {
            val libFilesToRebuild = KotlinSourceFileMutableMap<KotlinSourceFileExports>()

            for ((libFile, srcFiles) in filesWithModifiedExports) {
                val filesToRebuild by lazy(LazyThreadSafetyMode.NONE) { libFilesToRebuild.getOrPutFiles(libFile) }
                val fileStats by lazy(LazyThreadSafetyMode.NONE) { dirtyFileStats.getOrPutFiles(libFile) }
                val cache = getLibIncrementalCache(libFile)

                for ((srcFile, srcFileMetadata) in srcFiles) {
                    val isExportedSignatureUpdated = srcFileMetadata.isExportedSignaturesUpdated()
                    if (isExportedSignatureUpdated || srcFileMetadata.importedSignaturesState == ImportedSignaturesState.MODIFIED) {
                        // if exported signatures or imported inline functions were modified - rebuild
                        filesToRebuild[srcFile] = srcFileMetadata
                        if (isExportedSignatureUpdated) {
                            fileStats.addDirtFileStat(srcFile, DirtyFileState.UPDATED_EXPORTS)
                        }
                        if (srcFileMetadata.importedSignaturesState == ImportedSignaturesState.MODIFIED) {
                            fileStats.addDirtFileStat(srcFile, DirtyFileState.UPDATED_IMPORTS)
                        }
                    } else {
                        // if signatures and inline functions are the same - just update cache metadata
                        cache.updateSourceFileMetadata(srcFile, srcFileMetadata)
                    }
                }
            }

            return libFilesToRebuild
        }

        fun updateStdlibIntrinsicDependencies(
            loadedIr: LoadedJsIr,
            mainModule: IrModuleFragment,
            dirtyFiles: Map<KotlinLibraryFile, Set<KotlinSourceFile>>
        ) {
            val (stdlibFile, _) = findStdlib(mainModule, loadedIr.loadedFragments)
            val stdlibDirtyFiles = dirtyFiles[stdlibFile] ?: return

            val stdlibSymbolProviders = loadedIr.getSignatureProvidersForLib(stdlibFile)

            val updatedMetadata = KotlinSourceFileMutableMap<DirtyFileMetadata>()
            val idSignatureToFile = updatedMetadata.getExportedSignaturesAndAddMetadata(stdlibSymbolProviders, stdlibFile, stdlibDirtyFiles)

            signatureHashCalculator.addAllSignatureSymbols(idSignatureToFile)

            updatedMetadata.forEachFile { libFile, srcFile, updatedHeader ->
                updatedHeader.setAllDependencies(idSignatureToFile, updatedMetadata, libFile, srcFile)
            }

            val incrementalCache = getLibIncrementalCache(stdlibFile)
            updatedMetadata.forEachFile { libFile, srcFile, updatedHeader ->
                if (libFile != stdlibFile) {
                    icError("unexpected lib while parsing stdlib dependencies", libFile, srcFile)
                }

                val cachedHeader = incrementalCache.fetchSourceFileFullMetadata(srcFile)

                val needUpdate = when {
                    !updatedHeader.directDependencies.allFiles { lib, file, dependencies ->
                        cachedHeader.directDependencies[lib, file]?.keys?.containsAll(dependencies.keys) ?: dependencies.isEmpty()
                    } -> true

                    !updatedHeader.inverseDependencies.allFiles { lib, file, invDependencies ->
                        cachedHeader.inverseDependencies[lib, file]?.containsAll(invDependencies) ?: invDependencies.isEmpty()
                    } -> true

                    else -> false
                }
                if (needUpdate) {
                    cachedHeader.directDependencies.forEachFile { lib, file, dependencies ->
                        val updatedDependencies = updatedHeader.directDependencies[lib, file]
                        if (updatedDependencies != null) {
                            updatedDependencies += dependencies
                        } else {
                            updatedHeader.directDependencies[lib, file] = HashMap(dependencies)
                        }
                    }
                    cachedHeader.inverseDependencies.forEachFile { lib, file, dependencies ->
                        val updatedDependencies = updatedHeader.inverseDependencies[lib, file]
                        if (updatedDependencies != null) {
                            updatedDependencies += dependencies
                        } else {
                            updatedHeader.inverseDependencies[lib, file] = HashSet(dependencies)
                        }
                    }
                    incrementalCache.updateSourceFileMetadata(srcFile, updatedHeader)
                }
            }
        }

        fun buildAndCommitCacheArtifacts(loadedIr: LoadedJsIr): Map<KotlinLibraryFile, IncrementalCacheArtifact> {
            removedIncrementalCaches.forEach {
                if (!it.cacheDir.deleteRecursively()) {
                    icError("can not delete cache directory ${it.cacheDir.absolutePath}")
                }
            }
            return libraryDependencies.keys.associate { library ->
                val libFile = KotlinLibraryFile(library)
                val incrementalCache = getLibIncrementalCache(libFile)
                val providers = loadedIr.getSignatureProvidersForLib(libFile)
                val signatureToIndexMapping = providers.associate { KotlinSourceFile(it.irFile) to it.getSignatureToIndexMapping() }

                val cacheArtifact = incrementalCache.buildIncrementalCacheArtifact(signatureToIndexMapping)

                val libFragment = loadedIr.loadedFragments[libFile] ?: notFoundIcError("loaded fragment", libFile)
                val sourceFilesFromCache = cacheArtifact.getSourceFiles()
                for (irFile in libFragment.files) {
                    if (KotlinSourceFile(irFile) !in sourceFilesFromCache) {
                        // IC doesn't support cases when extra IrFiles (which don't exist in klib) are added into IrModuleFragment
                        icError("file ${irFile.fileEntry.name} is absent in incremental cache and klib", libFile)
                    }
                }

                libFile to cacheArtifact
            }
        }
    }

    private fun commitCacheAndBuildModuleArtifacts(
        incrementalCacheArtifacts: Map<KotlinLibraryFile, IncrementalCacheArtifact>,
        moduleNames: Map<KotlinLibraryFile, String>,
        rebuiltFileFragments: KotlinSourceFileMap<JsIrProgramFragment>
    ): List<ModuleArtifact> = stopwatch.measure("Incremental cache - committing artifacts") {
        incrementalCacheArtifacts.map { (libFile, incrementalCacheArtifact) ->
            incrementalCacheArtifact.buildModuleArtifactAndCommitCache(
                moduleName = moduleNames[libFile] ?: notFoundIcError("module name", libFile),
                rebuiltFileFragments = rebuiltFileFragments[libFile] ?: emptyMap()
            )
        }
    }

    private fun compileDirtyFiles(
        compilerForIC: JsIrCompilerICInterface,
        loadedFragments: Map<KotlinLibraryFile, IrModuleFragment>,
        dirtyFiles: Map<KotlinLibraryFile, Set<KotlinSourceFile>>
    ): MutableList<Triple<KotlinLibraryFile, KotlinSourceFile, () -> JsIrProgramFragment>> =
        stopwatch.measure("Processing IR - lowering") {
            val dirtyFilesForCompiling = mutableListOf<IrFile>()
            val dirtyFilesForRestoring = mutableListOf<Pair<KotlinLibraryFile, KotlinSourceFile>>()
            for ((libFile, libFragment) in loadedFragments) {
                val dirtySrcFiles = dirtyFiles[libFile] ?: continue
                for (irFile in libFragment.files) {
                    val srcFile = KotlinSourceFile(irFile)
                    if (srcFile in dirtySrcFiles) {
                        dirtyFilesForCompiling += irFile
                        dirtyFilesForRestoring += libFile to srcFile
                    }
                }
            }

            val fragmentGenerators = compilerForIC.compile(loadedFragments.values, dirtyFilesForCompiling, mainArguments)

            dirtyFilesForRestoring.mapIndexedTo(ArrayList(dirtyFilesForRestoring.size)) { i, libFileAndSrcFile ->
                Triple(libFileAndSrcFile.first, libFileAndSrcFile.second, fragmentGenerators[i])
            }
        }

    private data class IrForDirtyFilesAndCompiler(
        val incrementalCacheArtifacts: Map<KotlinLibraryFile, IncrementalCacheArtifact>,
        val loadedFragments: Map<KotlinLibraryFile, IrModuleFragment>,
        val dirtyFiles: Map<KotlinLibraryFile, Set<KotlinSourceFile>>,
        val irCompiler: JsIrCompilerICInterface
    )

    private fun loadIrForDirtyFilesAndInitCompiler(): IrForDirtyFilesAndCompiler {
        val updater = CacheUpdaterInternal()

        stopwatch.startNext("Modified files - checking hashes and collecting")
        val modifiedFiles = updater.loadModifiedFiles()

        stopwatch.startNext("Modified files - collecting exported signatures")
        val dirtyFileExports = updater.collectExportedSymbolsForDirtyFiles(modifiedFiles)

        stopwatch.startNext("Modified files - loading and linking IR")
        val jsIrLinkerLoader = JsIrLinkerLoader(
            compilerConfiguration = compilerConfiguration,
            dependencyGraph = updater.libraryDependencies,
            mainModuleFriends = updater.mainModuleFriendLibraries,
            irFactory = irFactory()
        )
        var loadedIr = jsIrLinkerLoader.loadIr(dirtyFileExports)

        val moduleName = loadedIr.loadedFragments[mainLibraryFile]?.name?.asString()
        if (moduleName in setOf("<space.app:app-web>", "<space:all-js>")) {
            HeapDumper.createHeapDump(
                "/Users/sergej.jaskiewicz/Developer/kotlin/heap-dumps/after-serialization-IC-v1-${moduleName}-${
                    DateTimeFormatter.ISO_INSTANT.format(
                        Instant.now()
                    )
                }.hprof",
                false
            )
        }

        var iterations = 0
        var lastDirtyFiles: KotlinSourceFileMap<KotlinSourceFileExports> = dirtyFileExports

        while (true) {
            stopwatch.startNext("Dependencies ($iterations) - updating a dependency graph")
            val dirtyMetadata = updater.rebuildDirtySourceMetadata(loadedIr, lastDirtyFiles)

            stopwatch.startNext("Dependencies ($iterations) - collecting files with updated exports and imports")
            val filesWithModifiedExportsOrImports = updater.collectFilesWithModifiedExportsAndImports(dirtyMetadata)

            stopwatch.startNext("Dependencies ($iterations) - collecting exported signatures for files with updated exports and imports")
            val filesToRebuild = updater.collectFilesToRebuildSignatures(filesWithModifiedExportsOrImports)

            if (filesToRebuild.isEmpty()) {
                break
            }

            lastDirtyFiles = filesToRebuild
            dirtyFileExports.copyFilesFrom(filesToRebuild)

            stopwatch.startNext("Dependencies ($iterations) - loading and linking IR for files with modified exports and imports")
            loadedIr = jsIrLinkerLoader.loadIr(filesToRebuild)
            iterations++
        }

        if (iterations != 0) {
            stopwatch.startNext("Loading and linking all IR")
            loadedIr = jsIrLinkerLoader.loadIr(dirtyFileExports)
        }

        stopwatch.startNext("Processing IR - initializing backend context")
        val mainModuleFragment = loadedIr.loadedFragments[mainLibraryFile] ?: notFoundIcError("main module fragment", mainLibraryFile)
        val compilerForIC = compilerInterfaceFactory.createCompilerForIC(mainModuleFragment, compilerConfiguration)

        // Load declarations referenced during `context` initialization
        loadedIr.loadUnboundSymbols()

        val dirtyFiles = dirtyFileExports.entries.associateTo(newHashMapWithExpectedSize(dirtyFileExports.size)) { it.key to HashSet(it.value.keys) }

        stopwatch.startNext("Processing IR - updating intrinsics and builtins dependencies")
        updater.updateStdlibIntrinsicDependencies(loadedIr, mainModuleFragment, dirtyFiles)

        stopwatch.startNext("Incremental cache - building artifacts")
        val incrementalCachesArtifacts = updater.buildAndCommitCacheArtifacts(loadedIr)

        stopwatch.stop()
        return IrForDirtyFilesAndCompiler(incrementalCachesArtifacts, loadedIr.loadedFragments, dirtyFiles, compilerForIC)
    }

    private data class FragmentGenerators(
        val incrementalCacheArtifacts: Map<KotlinLibraryFile, IncrementalCacheArtifact>,
        val moduleNames: Map<KotlinLibraryFile, String>,
        val generators: MutableList<Triple<KotlinLibraryFile, KotlinSourceFile, () -> JsIrProgramFragment>>
    )

    private fun loadIrAndMakeIrFragmentGenerators(): FragmentGenerators {
        val (incrementalCachesArtifacts, irFragments, dirtyFiles, irCompiler) = loadIrForDirtyFilesAndInitCompiler()

        val moduleNames = irFragments.entries.associate { it.key to it.value.name.asString() }

        val rebuiltFragmentGenerators = compileDirtyFiles(irCompiler, irFragments, dirtyFiles)

        return FragmentGenerators(incrementalCachesArtifacts, moduleNames, rebuiltFragmentGenerators)
    }

    private fun generateIrFragments(
        generators: MutableList<Triple<KotlinLibraryFile, KotlinSourceFile, () -> JsIrProgramFragment>>
    ): KotlinSourceFileMap<JsIrProgramFragment> = stopwatch.measure("Processing IR - generating program fragments") {
        val rebuiltFragments = KotlinSourceFileMutableMap<JsIrProgramFragment>()
        while (generators.isNotEmpty()) {
            val (libFile, srcFile, fragmentGenerator) = generators.removeFirst()
            rebuiltFragments[libFile, srcFile] = fragmentGenerator()
        }
        rebuiltFragments
    }

    fun actualizeCaches(): List<ModuleArtifact> {
        stopwatch.clear()
        dirtyFileStats.clear()

        val (incrementalCachesArtifacts, moduleNames, generators) = loadIrAndMakeIrFragmentGenerators()

        val rebuiltFragments = generateIrFragments(generators)

        return commitCacheAndBuildModuleArtifacts(incrementalCachesArtifacts, moduleNames, rebuiltFragments)
    }
}

// Used for tests only
fun rebuildCacheForDirtyFiles(
    library: KotlinLibrary,
    configuration: CompilerConfiguration,
    dependencyGraph: Map<KotlinLibrary, List<KotlinLibrary>>,
    dirtyFiles: Collection<String>?,
    irFactory: IrFactory,
    exportedDeclarations: Set<FqName>,
    mainArguments: List<String>?,
    es6mode: Boolean
): Pair<IrModuleFragment, List<Pair<IrFile, JsIrProgramFragment>>> {
    val internationService = IrInterningService()
    val emptyMetadata = object : KotlinSourceFileExports() {
        override val inverseDependencies = KotlinSourceFileMap<Set<IdSignature>>(emptyMap())
    }

    val libFile = KotlinLibraryFile(library)
    val dirtySrcFiles = dirtyFiles?.memoryOptimizedMap { KotlinSourceFile(it) } ?: KotlinLoadedLibraryHeader(library, internationService).sourceFileFingerprints.keys

    val modifiedFiles = mapOf(libFile to dirtySrcFiles.associateWith { emptyMetadata })

    val jsIrLoader = JsIrLinkerLoader(configuration, dependencyGraph, emptyList(), irFactory)
    val loadedIr = jsIrLoader.loadIr(KotlinSourceFileMap<KotlinSourceFileExports>(modifiedFiles), true)

    val currentIrModule = loadedIr.loadedFragments[libFile] ?: notFoundIcError("loaded fragment", libFile)
    val dirtyIrFiles = dirtyFiles?.let {
        val files = it.toSet()
        currentIrModule.files.memoryOptimizedFilter { irFile -> irFile.fileEntry.name in files }
    } ?: currentIrModule.files

    val compilerWithIC = JsIrCompilerWithIC(
        currentIrModule,
        configuration,
        JsGenerationGranularity.PER_MODULE,
        PhaseConfig(jsPhases),
        exportedDeclarations,
        es6mode
    )

    // Load declarations referenced during `context` initialization
    loadedIr.loadUnboundSymbols()
    internationService.clear()

    val fragments = compilerWithIC.compile(loadedIr.loadedFragments.values, dirtyIrFiles, mainArguments).memoryOptimizedMap { it() }

    return currentIrModule to dirtyIrFiles.zip(fragments)
}
