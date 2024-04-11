/*
 * Copyright 2010-2024 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.fir.backend

import org.jetbrains.kotlin.fir.resolve.providers.getRegularClassSymbolByClassId
import org.jetbrains.kotlin.fir.resolve.providers.impl.FirBuiltinSymbolProvider
import org.jetbrains.kotlin.fir.symbols.impl.FirFunctionSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirPropertySymbol
import org.jetbrains.kotlin.ir.ActualDeclarationExtractor
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrDeclarationWithName
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.symbols.IrSymbol
import org.jetbrains.kotlin.ir.util.classIdOrFail
import org.jetbrains.kotlin.ir.util.isAnnotation
import org.jetbrains.kotlin.ir.util.parentClassOrNull
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.StandardClassIds

/*
 * Extracts actual top-level declarations from the builtin symbol provider
 * But only for expect declarations marked with `ActualizeByJvmBuiltinProvider` annotation
 */
class FirJvmBuiltinProviderActualDeclarationExtractor(
    val provider: FirBuiltinSymbolProvider,
    val classifierStorage: Fir2IrClassifierStorage,
    val declarationStorage: Fir2IrDeclarationStorage,
) : ActualDeclarationExtractor() {
    companion object {
        val ActualizeByJvmBuiltinProviderFqName = StandardClassIds.Annotations.ActualizeByJvmBuiltinProvider.asSingleFqName()
    }

    override fun extract(expectIrClass: IrClass): IrClassSymbol? {
        fun IrClass.hasActualizeByJvmBuiltinProviderFqNameAnnotation(): Boolean {
            if (annotations.any { it.isAnnotation(ActualizeByJvmBuiltinProviderFqName) }) return true
            return parentClassOrNull?.hasActualizeByJvmBuiltinProviderFqNameAnnotation() == true
        }

        if (!expectIrClass.hasActualizeByJvmBuiltinProviderFqNameAnnotation()) return null

        val regularClassSymbol = provider.getRegularClassSymbolByClassId(expectIrClass.classIdOrFail) ?: return null
        return classifierStorage.getIrClassSymbol(regularClassSymbol)
    }

    override fun extract(expectCallables: List<IrDeclarationWithName>, expectCallableId: CallableId): List<IrSymbol> {
        if (expectCallables.none { expectCallable ->
                expectCallable.annotations.any { it.isAnnotation(ActualizeByJvmBuiltinProviderFqName) }
            }
        ) {
            return emptyList()
        }

        return provider.getTopLevelCallableSymbols(expectCallableId.packageName, expectCallableId.callableName).mapNotNull {
            when (it) {
                is FirPropertySymbol -> declarationStorage.getIrPropertySymbol(it)
                is FirFunctionSymbol<*> -> declarationStorage.getIrFunctionSymbol(it)
                else -> null
            }
        }
    }
}