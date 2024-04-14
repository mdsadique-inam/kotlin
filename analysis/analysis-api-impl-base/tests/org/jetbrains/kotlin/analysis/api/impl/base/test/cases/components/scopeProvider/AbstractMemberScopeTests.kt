/*
 * Copyright 2010-2023 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.analysis.api.impl.base.test.cases.components.scopeProvider

import org.jetbrains.kotlin.analysis.api.KtAnalysisSession
import org.jetbrains.kotlin.analysis.api.impl.base.test.SymbolByFqName
import org.jetbrains.kotlin.analysis.api.impl.base.test.cases.symbols.AbstractSymbolByFqNameTest
import org.jetbrains.kotlin.analysis.api.impl.base.test.cases.symbols.SymbolsData
import org.jetbrains.kotlin.analysis.api.symbols.KtDeclarationSymbol
import org.jetbrains.kotlin.analysis.api.symbols.markers.KtSymbolWithMembers
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.test.services.TestServices

abstract class AbstractMemberScopeTestBase : AbstractSymbolByFqNameTest() {
    protected abstract fun getSymbolsFromScope(analysisSession: KtAnalysisSession, symbol: KtSymbolWithMembers): Sequence<KtDeclarationSymbol>

    override fun KtAnalysisSession.collectSymbols(ktFile: KtFile, testServices: TestServices): SymbolsData {
        val symbolData = SymbolByFqName.getSymbolDataFromFile(testDataPath)
        val symbols = with(symbolData) { toSymbols(ktFile) }
        val symbolWithMembers = symbols.singleOrNull() as? KtSymbolWithMembers
            ?: error("Should be a single `${KtSymbolWithMembers::class.simpleName}`, but $symbols found.")
        return SymbolsData(getSymbolsFromScope(analysisSession, symbolWithMembers).toList())
    }
}

abstract class AbstractMemberScopeTest : AbstractMemberScopeTestBase() {
    override fun getSymbolsFromScope(analysisSession: KtAnalysisSession, symbol: KtSymbolWithMembers): Sequence<KtDeclarationSymbol> {
        with(analysisSession) {
            return symbol.getMemberScope().getAllSymbols()
        }
    }
}

abstract class AbstractStaticMemberScopeTest : AbstractMemberScopeTestBase() {
    override fun getSymbolsFromScope(analysisSession: KtAnalysisSession, symbol: KtSymbolWithMembers): Sequence<KtDeclarationSymbol> {
        with(analysisSession) {
            return symbol.getStaticMemberScope().getAllSymbols()
        }
    }
}

abstract class AbstractDeclaredMemberScopeTest : AbstractMemberScopeTestBase() {
    override fun getSymbolsFromScope(analysisSession: KtAnalysisSession, symbol: KtSymbolWithMembers): Sequence<KtDeclarationSymbol> {
        with(analysisSession) {
            return symbol.getDeclaredMemberScope().getAllSymbols()
        }
    }
}

abstract class AbstractStaticDeclaredMemberScopeTest : AbstractMemberScopeTestBase() {
    override fun getSymbolsFromScope(analysisSession: KtAnalysisSession, symbol: KtSymbolWithMembers): Sequence<KtDeclarationSymbol> {
        with(analysisSession) {
            return symbol.getStaticDeclaredMemberScope().getAllSymbols()
        }
    }
}

abstract class AbstractCombinedDeclaredMemberScopeTest : AbstractMemberScopeTestBase() {
    override fun getSymbolsFromScope(analysisSession: KtAnalysisSession, symbol: KtSymbolWithMembers): Sequence<KtDeclarationSymbol> {
        with(analysisSession) {
            return symbol.getCombinedDeclaredMemberScope().getAllSymbols()
        }
    }
}

abstract class AbstractDelegateMemberScopeTest : AbstractMemberScopeTestBase() {
    override fun getSymbolsFromScope(analysisSession: KtAnalysisSession, symbol: KtSymbolWithMembers): Sequence<KtDeclarationSymbol> {
        with(analysisSession) {
            return symbol.getDelegatedMemberScope().getCallableSymbols()
        }
    }
}
