/*
 * Copyright 2010-2023 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.analysis.api.fir.components

import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import org.jetbrains.kotlin.analysis.api.components.KtResolveExtensionInfoProvider
import org.jetbrains.kotlin.analysis.api.fir.KtFirAnalysisSession
import org.jetbrains.kotlin.analysis.api.impl.base.scopes.KtEmptyScope
import org.jetbrains.kotlin.analysis.api.lifetime.KtLifetimeToken
import org.jetbrains.kotlin.analysis.api.lifetime.withValidityAssertion
import org.jetbrains.kotlin.analysis.api.scopes.KtScope
import org.jetbrains.kotlin.analysis.api.scopes.KtScopeNameFilter
import org.jetbrains.kotlin.analysis.api.symbols.*
import org.jetbrains.kotlin.analysis.low.level.api.fir.resolve.extensions.LLFirResolveExtensionTool
import org.jetbrains.kotlin.analysis.low.level.api.fir.resolve.extensions.LLFirResolveExtensionToolDeclarationProvider
import org.jetbrains.kotlin.analysis.low.level.api.fir.resolve.extensions.navigationTargetsProvider
import org.jetbrains.kotlin.analysis.project.structure.KtModuleStructureInternals
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtNamedDeclaration

internal class KtFirResolveExtensionInfoProvider(
    override val analysisSession: KtFirAnalysisSession,
) : KtResolveExtensionInfoProvider(), KtFirAnalysisSessionComponent {
    override val token: KtLifetimeToken
        get() = analysisSession.token

    override fun getResolveExtensionScopeWithTopLevelDeclarations(): KtScope {
        val tools = analysisSession.extensionTools
        if (tools.isEmpty()) return KtEmptyScope(token)
        return KtFirResolveExtensionScope(analysisSession, tools)
    }

    @OptIn(KtModuleStructureInternals::class)
    override fun isResolveExtensionFile(file: VirtualFile): Boolean =
        file.navigationTargetsProvider != null

    @OptIn(KtModuleStructureInternals::class)
    override fun getResolveExtensionNavigationElements(originalPsi: KtElement): Collection<PsiElement> {
        val targetsProvider = originalPsi.containingFile?.virtualFile?.navigationTargetsProvider ?: return emptyList()
        return with(targetsProvider) { analysisSession.getNavigationTargets(originalPsi) }
    }
}

private class KtFirResolveExtensionScope(
    private val analysisSession: KtFirAnalysisSession,
    private val tools: List<LLFirResolveExtensionTool>,
) : KtScope {
    init {
        require(tools.isNotEmpty())
    }

    override val token: KtLifetimeToken get() = analysisSession.token

    override fun getCallableSymbols(nameFilter: KtScopeNameFilter): Sequence<KtCallableSymbol> = withValidityAssertion {
        getTopLevelDeclarations(nameFilter) { it.getTopLevelCallables() }
    }

    override fun getCallableSymbols(names: Collection<Name>): Sequence<KtCallableSymbol> = withValidityAssertion {
        if (names.isEmpty()) return emptySequence()
        val namesSet = names.toSet()
        return getCallableSymbols { it in namesSet }
    }

    override fun getClassifierSymbols(nameFilter: KtScopeNameFilter): Sequence<KtClassifierSymbol> = withValidityAssertion {
        getTopLevelDeclarations(nameFilter) { it.getTopLevelClassifiers() }
    }

    override fun getClassifierSymbols(names: Collection<Name>): Sequence<KtClassifierSymbol> = withValidityAssertion {
        if (names.isEmpty()) return emptySequence()
        val namesSet = names.toSet()
        return getClassifierSymbols { it in namesSet }
    }

    private inline fun <D : KtNamedDeclaration, reified S : KtDeclaration> getTopLevelDeclarations(
        crossinline nameFilter: KtScopeNameFilter,
        crossinline getDeclarationsByProvider: (LLFirResolveExtensionToolDeclarationProvider) -> Sequence<D>,
    ): Sequence<S> = sequence {
        for (tool in tools) {
            for (declaration in getDeclarationsByProvider(tool.declarationProvider)) {
                val declarationName = declaration.nameAsName ?: continue
                if (!nameFilter(declarationName)) continue
                with(analysisSession) {
                    yield(declaration.getSymbol() as S)
                }
            }
        }
    }

    override fun getConstructors(): Sequence<KtConstructorSymbol> = withValidityAssertion {
        emptySequence()
    }

    override fun getPackageSymbols(nameFilter: KtScopeNameFilter): Sequence<KtPackageSymbol> = withValidityAssertion {
        sequence {
            // Only emit package symbols for top-level packages (subpackages of root). This matches the behavior
            // of the root-level KtFirPackageScope.
            val seenTopLevelPackages = mutableSetOf<Name>()
            for (tool in tools) {
                for (packageName in tool.packageFilter.getAllSubPackages(FqName.ROOT)) {
                    if (seenTopLevelPackages.add(packageName) && nameFilter(packageName)) {
                        yield(analysisSession.firSymbolBuilder.createPackageSymbol(FqName.ROOT.child(packageName)))
                    }
                }
            }
        }
    }

    override fun getPossibleCallableNames(): Set<Name> = withValidityAssertion {
        tools.flatMapTo(mutableSetOf()) { it.declarationProvider.getTopLevelCallableNames() }
    }

    override fun getPossibleClassifierNames(): Set<Name> = withValidityAssertion {
        tools.flatMapTo(mutableSetOf()) { it.declarationProvider.getTopLevelClassifierNames() }
    }
}