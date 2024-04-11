/*
 * Copyright 2010-2024 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.ir

import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrDeclarationWithName
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.symbols.IrSymbol
import org.jetbrains.kotlin.name.CallableId

/**
 * Allows extracting extra actual declarations that are not presented in source code.
 * For instance, it allows extracting actual top-level classes and functions from builtin symbol provider (KT-65841).
 */
abstract class ActualDeclarationExtractor {
    abstract fun extract(expectIrClass: IrClass): IrClassSymbol?

    abstract fun extract(expectCallables: List<IrDeclarationWithName>, expectCallableId: CallableId): List<IrSymbol>
}